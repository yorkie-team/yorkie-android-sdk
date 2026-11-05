package dev.yorkie.api

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
}
