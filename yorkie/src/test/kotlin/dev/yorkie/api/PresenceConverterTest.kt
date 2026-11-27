package dev.yorkie.api

import dev.yorkie.api.v1.change
import dev.yorkie.api.v1.presenceChange
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrUnimplemented
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers [PresenceChange] <-> [PBPresenceChange] conversion, incl. the
 * `CHANGE_TYPE_PATCH` arm added in JS `b50f8900` (#1477): a patch is sent with
 * only the changed keys (`full`/`removed_keys` are never written, D5) and a
 * decoded patch never carries `full` (it is populated only when the change is
 * produced locally).
 */
class PresenceConverterTest {

    // generator/025: these four cases build a PB message via the builder and read it back
    // without crossing bytes (no `toByteArray()`/`parseFrom()`) -- they pin the generated
    // accessor's shape, not the wire decode path. The two byte-crossing round trips below
    // ("Patch round trips through serialized bytes", "a PATCH decoded from bytes carrying
    // removed_keys...") are what actually exercises serialization.

    @Test
    fun `Put accessor shape matches the domain object (no bytes crossed)`() {
        val change = PresenceChange.Put(mapOf("a" to "b"))

        val converted = change.toPBPresenceChange().toPresenceChange()

        assertEquals(change, converted)
    }

    @Test
    fun `Clear accessor shape matches the domain object (no bytes crossed)`() {
        val change = PresenceChange.Clear

        val converted = change.toPBPresenceChange().toPresenceChange()

        assertEquals(change, converted)
    }

    @Test
    fun `Patch builder shape encodes only the changed keys and never full (no bytes crossed)`() {
        val change = PresenceChange.Patch(
            presence = mapOf("cursor" to "10"),
            full = mapOf("cursor" to "10", "color" to "red"),
        )

        val pb = change.toPBPresenceChange()

        assertEquals(PBPresenceChangeType.CHANGE_TYPE_PATCH, pb.type)
        assertEquals(mapOf("cursor" to "10"), pb.presence.dataMap)
        assertTrue(pb.removedKeysList.isEmpty())
    }

    @Test
    fun `Patch accessor shape decodes to a domain Patch with full null (no bytes crossed)`() {
        val change = PresenceChange.Patch(
            presence = mapOf("cursor" to "10"),
            full = mapOf("cursor" to "10", "color" to "red"),
        )

        val decoded = change.toPBPresenceChange().toPresenceChange()

        assertEquals(PresenceChange.Patch(mapOf("cursor" to "10"), full = null), decoded)
    }

    @Test
    fun `Patch round trips through serialized bytes`() {
        val change = PresenceChange.Patch(presence = mapOf("cursor" to "10"), full = null)

        val bytes = change.toPBPresenceChange().toByteArray()
        val reparsed = PBPresenceChange.parseFrom(bytes)
        val decoded = reparsed.toPresenceChange()

        assertEquals(PBPresenceChangeType.CHANGE_TYPE_PATCH, reparsed.type)
        assertEquals(PresenceChange.Patch(mapOf("cursor" to "10"), full = null), decoded)
    }

    @Test
    fun `a PATCH decoded from bytes carrying removed_keys is accepted and removed_keys ignored`() {
        val pb = presenceChange {
            type = PBPresenceChangeType.CHANGE_TYPE_PATCH
            presence = mapOf("cursor" to "10").toPBPresence()
            removedKeys.addAll(listOf("color"))
        }

        val bytes = pb.toByteArray()
        val reparsed = PBPresenceChange.parseFrom(bytes)
        val decoded = reparsed.toPresenceChange()

        assertEquals(listOf("color"), reparsed.removedKeysList)
        assertEquals(PresenceChange.Patch(mapOf("cursor" to "10"), full = null), decoded)
    }

    // Ported from JS #1502 (`dcb74d27`): a v0.7.24-or-earlier server stores a patch it
    // cannot fold with an empty change type and serves it back as UNSPECIFIED
    // (Document.kt presencePatchSent KDoc); DELETE is the server's own tombstone type.
    // Both decode as an empty Put rather than throwing, as Go's FromPresenceChange
    // default branch does, so a pack carrying one still applies instead of wedging.

    @Test
    fun `CHANGE_TYPE_UNSPECIFIED decodes as an empty presence, as Go does`() {
        val pb = presenceChange {
            type = PBPresenceChangeType.CHANGE_TYPE_UNSPECIFIED
        }

        val decoded = pb.toPresenceChange()

        assertEquals(PresenceChange.Put(emptyMap()), decoded)
    }

    @Test
    fun `CHANGE_TYPE_DELETE decodes as an empty presence, as Go does`() {
        val pb = presenceChange {
            type = PBPresenceChangeType.CHANGE_TYPE_DELETE
        }

        val decoded = pb.toPresenceChange()

        assertEquals(PresenceChange.Put(emptyMap()), decoded)
    }

    @Test
    fun `a PATCH that carries no presence decodes as an empty one`() {
        val pb = presenceChange {
            type = PBPresenceChangeType.CHANGE_TYPE_PATCH
            // `presence` left unset -- the proto3 default is an empty Presence
            // message, whose dataMap is already an empty map (no code change).
        }

        val decoded = pb.toPresenceChange()

        assertEquals(PresenceChange.Patch(emptyMap(), full = null), decoded)
    }

    @Test
    fun `a presence change outside the enum still throws ErrUnimplemented`() {
        val pb = presenceChange {
            // An int the generated enum has no constant for decodes to UNRECOGNIZED --
            // genuinely outside the wire contract, unlike UNSPECIFIED/DELETE above.
            typeValue = 42
        }

        val exception = assertThrows(YorkieException::class.java) { pb.toPresenceChange() }

        assertEquals(ErrUnimplemented, exception.code)
    }

    // --- RTCOLLABPLATFORM-805: the presence value wire contract (yorkie#2386) -------------

    private fun rawPresence(vararg entries: Pair<String, String>) =
        dev.yorkie.api.v1.presence { entries.forEach { (k, v) -> data[k] = v } }

    @Test
    fun `PresenceConverter encodes values on write and decodes them on read`() {
        val change = PresenceChange.Put(
            mapOf("name" to "a", "cursor" to "{\"x\":0,\"y\":0}", "n" to "1", "q" to "\"x\""),
        )

        val pb = change.toPBPresenceChange()
        val bytes = pb.toByteArray()

        // wire: bare text and a string literal are JSON strings, typed JSON is untouched
        assertEquals(
            mapOf(
                "name" to "\"a\"",
                "cursor" to "{\"x\":0,\"y\":0}",
                "n" to "1",
                "q" to "\"\\\"x\\\"\"",
            ),
            PBPresenceChange.parseFrom(bytes).presence.dataMap,
        )
        // read: exactly what was set
        assertEquals(change, PBPresenceChange.parseFrom(bytes).toPresenceChange())

        val patch = PresenceChange.Patch(mapOf("cursor" to "1,1"), full = null)
        val patchPb = PBPresenceChange.parseFrom(patch.toPBPresenceChange().toByteArray())
        assertEquals(mapOf("cursor" to "\"1,1\""), patchPb.presence.dataMap)
        assertEquals(patch, patchPb.toPresenceChange())
    }

    @Test
    fun `PresenceConverter reads a raw value and its server-repaired form the same way`() {
        for (type in listOf(
            PBPresenceChangeType.CHANGE_TYPE_PUT,
            PBPresenceChangeType.CHANGE_TYPE_PATCH,
        )) {
            val raw =
                presenceChange {
                    this.type = type
                    presence = rawPresence("cursor" to "moved")
                }
            val repaired = presenceChange {
                this.type = type
                presence = rawPresence("cursor" to "\"moved\"")
            }
            val rawChange = raw.toPresenceChange()
            assertEquals(repaired.toPresenceChange(), rawChange)
            val decoded = when (rawChange) {
                is PresenceChange.Put -> rawChange.presence
                is PresenceChange.Patch -> rawChange.presence
                else -> error("unexpected $rawChange")
            }
            assertEquals(mapOf("cursor" to "moved"), decoded)
        }
    }

    @Test
    fun `a legacy raw pending change decodes leniently and is re-encoded on push`() {
        // an older SDK persisted this pending change with the value raw on the wire
        val legacy = change {
            presenceChange = presenceChange {
                type = PBPresenceChangeType.CHANGE_TYPE_PUT
                presence = rawPresence("name" to "b1", "cursor" to "{\"x\":0}", "q" to "\"x\"")
            }
        }

        val change = legacy.toChange()

        // lenient read: raw text is text; an old raw value that was itself a string literal
        // reads as its content (the one documented legacy edge)
        assertEquals(
            PresenceChange.Put(mapOf("name" to "b1", "cursor" to "{\"x\":0}", "q" to "x")),
            change.presenceChange,
        )
        // pushed again, it is encoded as the wire contract requires
        assertEquals(
            mapOf("name" to "\"b1\"", "cursor" to "{\"x\":0}", "q" to "\"x\""),
            change.toPBChange().presenceChange.presence.dataMap,
        )
    }

    @Test
    fun `a legacy raw snapshot presence decodes leniently and is re-encoded`() {
        val actor = "0000000000000000000000aa"
        val emptyRoot = dev.yorkie.document.crdt.CrdtObject(
            dev.yorkie.document.time.TimeTicket.InitialTimeTicket,
            memberNodes = dev.yorkie.document.crdt.ElementRht(),
        )
        val bytes = dev.yorkie.api.v1.Snapshot.parseFrom(snapshotToBytes(emptyRoot, emptyMap()))
            .toBuilder()
            .putPresences(actor, rawPresence("name" to "b1", "cursor" to "1,1"))
            .build()
            .toByteString()

        val (_, presences) = bytes.toSnapshot()

        assertEquals(mapOf(actor to mapOf("name" to "b1", "cursor" to "1,1")), presences)
        val reencoded = dev.yorkie.api.v1.Snapshot.parseFrom(
            snapshotToBytes(
                dev.yorkie.document.crdt.CrdtObject(
                    dev.yorkie.document.time.TimeTicket.InitialTimeTicket,
                    memberNodes = dev.yorkie.document.crdt.ElementRht(),
                ),
                presences,
            ),
        )
        assertEquals(
            mapOf("name" to "\"b1\"", "cursor" to "\"1,1\""),
            reencoded.presencesMap.getValue(actor).dataMap,
        )
    }

    @Test
    fun `text that is itself non-string JSON goes on the wire as that JSON type`() {
        // "123" is a JSON number, so a JS or iOS peer reads a number (unchanged from before);
        // to send the string 123 an app passes the JSON string literal "\"123\"".
        val pb = PresenceChange.Put(
            mapOf("n" to "123", "b" to "true", "z" to "null", "a" to "[1]", "s" to "\"123\""),
        ).toPBPresenceChange()

        assertEquals(
            mapOf(
                "n" to "123",
                "b" to "true",
                "z" to "null",
                "a" to "[1]",
                "s" to "\"\\\"123\\\"\"",
            ),
            pb.presence.dataMap,
        )
    }
}
