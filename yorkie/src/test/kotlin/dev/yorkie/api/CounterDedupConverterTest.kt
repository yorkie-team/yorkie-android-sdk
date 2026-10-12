package dev.yorkie.api

import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.v1.JSONElementKt.counter
import dev.yorkie.api.v1.JSONElementKt.jSONObject
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.rHTNode
import dev.yorkie.api.v1.snapshot
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.Hll
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.Logger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterDedupConverterTest {

    @After
    fun tearDown() {
        // Reset the process-wide Logger singleton so a recorder captured by
        // one test does not keep receiving messages from a later test.
        Logger.init(RecordingLogger())
    }

    @Test
    fun `dedup counter survives proto round-trip with HLL state`() {
        // given - a dedup counter with some unique actors
        val source = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)
        listOf("alice", "bob", "carol", "dave").forEach { source.increaseDedup(unit, it) }
        val sourceHll = checkNotNull(source.hllBytes())

        // when - serialize and deserialize through the JsonElement proto
        val pb = source.toPBCounter().counter
        val roundTripped = pb.toCrdtCounter()
        val rtHll = checkNotNull(roundTripped.hllBytes())

        // then - HLL bytes and cardinality match
        assertEquals(CrdtCounter.CounterType.IntDedup, roundTripped.type)
        assertEquals(source.value, roundTripped.value)
        assertArrayEquals(sourceHll, rtHll)
        assertEquals(Hll.HllRegisterCount, rtHll.size)
    }

    @Test
    fun `normal counter proto round-trip leaves hll bytes empty`() {
        // given
        val source = CrdtCounter(42, InitialTimeTicket)

        // when
        val pb = source.toPBCounter().counter
        val roundTripped = pb.toCrdtCounter()

        // then
        assertEquals(CrdtCounter.CounterType.Int, roundTripped.type)
        assertEquals(42, roundTripped.value)
        // No HLL state for non-dedup counters.
        assertTrue(pb.hllRegisters.isEmpty)
        assertNotNull(roundTripped) // sanity
    }

    // ac60f3d9 (#1422; D8): `restoreHll` rejects an HLL payload whose length
    // or register values `add` could not have produced. The converter's one
    // call site (ElementConverter.kt toCrdtCounter) must not throw out of
    // decode on rejection -- it logs and keeps going.
    @Test
    fun `converter discards wrong-length HLL, logs once, keeps create-state value (D11)`() {
        // given - a dedup counter whose wire `value` is NOT zero: D11 pins
        // that Android's dedup decode never reads it (createDedup always
        // starts the counter at value 0), unlike JS which keeps the value it
        // decoded from the wire on a rejected HLL payload.
        val logger = RecordingLogger()
        Logger.init(logger)
        val pb = counter {
            type = PBValueType.VALUE_TYPE_INTEGER_DEDUP_CNT
            createdAt = InitialTimeTicket.toPBTimeTicket()
            value = byteArrayOf(99, 0, 0, 0).toByteString()
            hllRegisters = ByteArray(10).toByteString() // wrong length
        }

        // when
        val decoded = pb.toCrdtCounter()

        // then
        assertEquals(CrdtCounter.CounterType.IntDedup, decoded.type)
        assertEquals(0, decoded.value)
        assertArrayEquals(ByteArray(Hll.HllRegisterCount), checkNotNull(decoded.hllBytes()))
        assertEquals(1, logger.errorMessages.size)
        val message = logger.errorMessages.single()
        assertTrue(message.contains("discarding malformed HLL registers"))
        assertTrue(message.contains("10 bytes"))
    }

    @Test
    fun `converter discards out-of-range HLL register, logs once, keeps create-state (D11)`() {
        // given - exactly HllRegisterCount bytes (so length alone would pass)
        // but one register value (52) is above the maximum `add` can produce.
        val logger = RecordingLogger()
        Logger.init(logger)
        val malformedRegisters = ByteArray(Hll.HllRegisterCount).also { it[100] = 52 }
        val pb = counter {
            type = PBValueType.VALUE_TYPE_INTEGER_DEDUP_CNT
            createdAt = InitialTimeTicket.toPBTimeTicket()
            value = byteArrayOf(7, 0, 0, 0).toByteString()
            hllRegisters = malformedRegisters.toByteString()
        }

        // when
        val decoded = pb.toCrdtCounter()

        // then
        assertEquals(0, decoded.value)
        assertArrayEquals(ByteArray(Hll.HllRegisterCount), checkNotNull(decoded.hllBytes()))
        assertEquals(1, logger.errorMessages.size)
        assertTrue(
            logger.errorMessages.single().contains(
                "discarding malformed HLL registers for counter " +
                    "${InitialTimeTicket.toTestString()}: ${Hll.HllRegisterCount} bytes",
            ),
        )
    }

    // The decode-level case: a full ChangePack snapshot whose root holds a
    // dedup counter with a malformed HLL payload must decode through the
    // real Document.applyChangePack path without throwing (same pattern as
    // CrdtPrimitiveTest."applyChangePack with a snapshot holding a short
    // Double no longer throws").
    @Test
    fun `applyChangePack with a snapshot holding a malformed HLL counter no longer throws`() =
        runTest {
            val docKey = "counter-dedup-decode-level-test"
            val document = Document(docKey)
            val rootElement = jSONElement {
                jsonObject = jSONObject {
                    createdAt = InitialTimeTicket.toPBTimeTicket()
                    nodes.add(
                        rHTNode {
                            key = "c"
                            element = jSONElement {
                                counter = counter {
                                    type = PBValueType.VALUE_TYPE_INTEGER_DEDUP_CNT
                                    createdAt = InitialTimeTicket.toPBTimeTicket()
                                    value = byteArrayOf(1, 0, 0, 0).toByteString()
                                    hllRegisters = ByteArray(10).toByteString() // wrong length
                                }
                            }
                        },
                    )
                }
            }
            val snapshotBytes = snapshot { root = rootElement }.toByteString()

            document.applyChangePack(
                ChangePack(
                    docKey,
                    CheckPoint(0, 0u),
                    emptyList(),
                    snapshotBytes,
                    false,
                    INITIAL_VERSION_VECTOR,
                ),
            )

            val decoded = document.getRootObject()["c"] as CrdtCounter
            assertEquals(0, decoded.value)
        }
}
