package dev.yorkie.document

import dev.yorkie.api.PBPresenceChange
import dev.yorkie.api.PBPresenceChangeType
import dev.yorkie.api.toPBPresenceChange
import dev.yorkie.api.toPresenceChange
import dev.yorkie.api.v1.presenceChange
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins "Presence values that are not JSON" of yorkie-js-sdk v0.7.29 (js#1559, server
 * yorkie#2386). Since RTCOLLABPLATFORM-805 Android honours the presence wire contract like
 * Go and JS: a value is JSON-encoded on write and decoded on read
 * ([dev.yorkie.document.presence.PresenceValue]), so a non-JSON value on the wire is read as
 * its raw text, the server repair `"moved"` reads the same as a raw `moved`, and neither can
 * break a pull. The converter-level cases are in [dev.yorkie.api.PresenceConverterTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceRawValueTest {

    private val remoteActor = "0000000000000000000000bb"

    @Test
    fun `decodes a non-JSON value to its raw text`() {
        // given a wire presence with a JSON string and a bare word (JS test 1 input)
        val pb = presenceChange {
            type = PBPresenceChangeType.CHANGE_TYPE_PUT
            presence = dev.yorkie.api.v1.presence {
                data["name"] = "\"a\""
                data["cursor"] = "moved"
            }
        }

        // when it crosses the wire and is decoded
        val decoded = PBPresenceChange.parseFrom(pb.toByteArray()).toPresenceChange()

        // then the JSON string reads as its content and the bare word as its raw text
        assertEquals(PresenceChange.Put(mapOf("name" to "a", "cursor" to "moved")), decoded)
        val patch = PBPresenceChange.parseFrom(
            PresenceChange.Patch(mapOf("cursor" to "moved"), full = null)
                .toPBPresenceChange().toByteArray(),
        ).toPresenceChange()
        assertEquals(PresenceChange.Patch(mapOf("cursor" to "moved"), full = null), patch)
    }

    @Test
    fun `reads the value the server repair writes the same way`() {
        val raw = presenceChange {
            type = PBPresenceChangeType.CHANGE_TYPE_PUT
            presence = dev.yorkie.api.v1.presence { data["cursor"] = "moved" }
        }
        val repaired = presenceChange {
            type = PBPresenceChangeType.CHANGE_TYPE_PUT
            presence = dev.yorkie.api.v1.presence { data["cursor"] = "\"moved\"" }
        }

        assertEquals(PresenceChange.Put(mapOf("cursor" to "moved")), raw.toPresenceChange())
        assertEquals(raw.toPresenceChange(), repaired.toPresenceChange())
    }

    @Test
    fun `applies a pulled pack that carries one`() = runTest {
        Document("d").use { target ->
            val change = Change(
                ChangeID(1u, 1, remoteActor, VersionVector()),
                listOf(
                    SetOperation(
                        key = "k",
                        value = CrdtPrimitive("v", TimeTicket(1, 0u, remoteActor)),
                        parentCreatedAt = TimeTicket.InitialTimeTicket,
                        executedAt = TimeTicket(1, 1u, remoteActor),
                    ),
                ),
                presenceChange = PBPresenceChange.parseFrom(
                    presenceChange {
                        type = PBPresenceChangeType.CHANGE_TYPE_PUT
                        presence = dev.yorkie.api.v1.presence {
                            data["name"] = "\"a\""
                            data["cursor"] = "moved"
                        }
                    }.toByteArray(),
                ).toPresenceChange(),
            )

            target.applyChangePack(
                ChangePack(
                    target.getKey(),
                    CheckPoint(1, 0u),
                    listOf(change),
                    null,
                    false,
                    VersionVector(),
                ),
            )

            assertEquals("v", target.getRoot().getAs<JsonPrimitive>("k").value)
            assertEquals(
                mapOf("name" to "a", "cursor" to "moved"),
                target.allPresences.value[remoteActor],
            )
            assertEquals(1L, target.checkPoint.serverSeq)
        }
    }
}
