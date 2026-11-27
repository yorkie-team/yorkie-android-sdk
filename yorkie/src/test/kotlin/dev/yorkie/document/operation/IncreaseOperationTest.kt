package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins how [IncreaseOperation] applies a Double delta to a plain counter, mirroring Go
 * `Counter.Increase` and JS `counter.ts` (yorkie-js-sdk v0.7.29), and that a NaN or an
 * infinite delta is a silent no-op as in Go (`isNonFinite`).
 */
class IncreaseOperationTest {

    private val actor = "0000000000000000000000aa"
    private lateinit var recordingLogger: RecordingLogger

    @Before
    fun setUp() {
        recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)
    }

    @After
    fun tearDown() {
        // Logger is a process-wide singleton: leave a fresh, non-capturing instance behind.
        Logger.init(RecordingLogger())
    }

    private fun rootWithCounter(counter: CrdtCounter): CrdtRoot {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        SetOperation("c", counter, obj.createdAt, counter.createdAt)
            .execute(root, OpSource.Local, null)
        return root
    }

    private fun increase(counterCreatedAt: TimeTicket, delta: Double) = IncreaseOperation(
        value = CrdtPrimitive(delta, TimeTicket(2, 0u, actor)),
        parentCreatedAt = counterCreatedAt,
        executedAt = TimeTicket(2, 1u, actor),
    )

    @Test
    fun `applies a Double delta and reverses it with its negation`() {
        val counter = CrdtCounter(0, TimeTicket(1, 0u, actor))
        val root = rootWithCounter(counter)

        val result = increase(counter.createdAt, 1.9).execute(root, OpSource.Local, null)

        assertEquals(1, (root.findByCreatedAt(counter.createdAt) as CrdtCounter).value)
        assertTrue(result.executed)
        assertEquals(1.9, (result.opInfos.single() as OperationInfo.IncreaseOpInfo).value)
        // JS negates the Double (Go derives the reverse from the change for an Int counter).
        val reverse = result.reverseOps.single() as IncreaseOperation
        assertEquals(-1.9, (reverse.value as CrdtPrimitive).value)
    }

    @Test
    fun `ignores a NaN or infinite Double delta for every source without a reverse or a log`() {
        for (delta in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (source in OpSource.entries) {
                val counter = CrdtCounter(7, TimeTicket(1, 0u, actor))
                val root = rootWithCounter(counter)

                val result = increase(counter.createdAt, delta).execute(root, source, null)

                assertEquals(false, result.executed)
                assertTrue(result.opInfos.isEmpty())
                assertTrue(result.reverseOps.isEmpty())
                assertEquals(7, (root.findByCreatedAt(counter.createdAt) as CrdtCounter).value)
            }
        }
        assertTrue(recordingLogger.errorMessages.isEmpty())
        assertTrue(recordingLogger.debugMessages.isEmpty())
    }
}
