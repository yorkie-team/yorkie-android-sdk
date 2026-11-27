package dev.yorkie.document

import dev.yorkie.api.PBPresenceChange
import dev.yorkie.api.toPBPresenceChange
import dev.yorkie.api.toPresenceChange
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
import kotlin.test.assertNotEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins "Presence values that are not JSON" of yorkie-js-sdk v0.7.29 (js#1559, server
 * yorkie#2386) as a regression contract, not a source port: JS used to `JSON.parse` every
 * presence value and threw out of the pull on a non-JSON one. Android never decodes
 * presence (`PBPresence.toPresence()` hands the protobuf string map through verbatim and
 * `P` is `Map<String, String>`), so there is nothing to fix; these tests pin that a
 * non-JSON value travels as its raw text and cannot break a pull.
 *
 * One difference from JS is intentional: JS normalises a raw `moved` and the server's
 * repaired `"moved"` to the same value; Android keeps both strings as they are and leaves
 * decoding to the application.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceRawValueTest {

    private val remoteActor = "0000000000000000000000bb"

    private fun roundTrip(change: PresenceChange): PresenceChange {
        val bytes = change.toPBPresenceChange().toByteArray()
        return PBPresenceChange.parseFrom(bytes).toPresenceChange()
    }

    @Test
    fun `decodes a non-JSON value to its raw text`() {
        // given a presence whose values are a JSON string and a bare word
        val change = PresenceChange.Put(mapOf("name" to "\"a\"", "cursor" to "moved"))

        // when it crosses the wire
        val decoded = roundTrip(change)

        // then both values come back verbatim; the JSON one keeps its quotes
        assertEquals(PresenceChange.Put(mapOf("name" to "\"a\"", "cursor" to "moved")), decoded)
        val patch = roundTrip(PresenceChange.Patch(mapOf("cursor" to "moved"), full = null))
        assertEquals(PresenceChange.Patch(mapOf("cursor" to "moved"), full = null), patch)
    }

    @Test
    fun `keeps the value the server repair writes distinct from the raw one`() {
        val raw = (roundTrip(PresenceChange.Put(mapOf("cursor" to "moved"))) as PresenceChange.Put)
        val repaired = (
            roundTrip(PresenceChange.Put(mapOf("cursor" to "\"moved\""))) as PresenceChange.Put
            )

        assertEquals("moved", raw.presence["cursor"])
        assertEquals("\"moved\"", repaired.presence["cursor"])
        assertNotEquals(raw.presence["cursor"], repaired.presence["cursor"])
    }

    @Test
    fun `applies a pulled pack that carries one`() = runTest {
        val target = Document("d")
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
            presenceChange = roundTrip(
                PresenceChange.Put(mapOf("name" to "\"a\"", "cursor" to "moved")),
            ),
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
            mapOf("name" to "\"a\"", "cursor" to "moved"),
            target.allPresences.value[remoteActor],
        )
        assertEquals(1L, target.checkPoint.serverSeq)
    }
}
