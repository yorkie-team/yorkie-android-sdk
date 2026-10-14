package dev.yorkie.document.history

import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class HistoryTest {

    private fun createDummyOp(): HistoryOperation.Op {
        val ticket = InitialTimeTicket
        val primitive = CrdtPrimitive(1, ticket)
        return HistoryOperation.Op(
            IncreaseOperation(primitive, ticket, ticket),
        )
    }

    /** Builds a history entry whose [IncreaseOperation.parentCreatedAt] lamport is [n], so eviction order is observable. */
    private fun markedOp(n: Long): HistoryOperation.Op {
        val ticket = InitialTimeTicket.copy(lamport = n)
        val primitive = CrdtPrimitive(1, ticket)
        return HistoryOperation.Op(
            IncreaseOperation(primitive, ticket, ticket),
        )
    }

    /** Reads back the marker lamport of each entry on a stack built from [markedOp]. */
    private fun marks(stack: List<List<HistoryOperation>>): List<Long> {
        return stack.map { ops ->
            ((ops.first() as HistoryOperation.Op).operation as IncreaseOperation)
                .parentCreatedAt.lamport
        }
    }

    @Test
    fun `undo stack push and pop`() {
        val history = History()
        assertFalse(history.hasUndo())

        val ops = listOf(createDummyOp())
        history.pushUndo(ops)
        assertTrue(history.hasUndo())

        val popped = history.popUndo()
        assertNotNull(popped)
        assertEquals(1, popped.size)
        assertFalse(history.hasUndo())
    }

    @Test
    fun `redo stack push and pop`() {
        val history = History()
        assertFalse(history.hasRedo())

        val ops = listOf(createDummyOp())
        history.pushRedo(ops)
        assertTrue(history.hasRedo())

        val popped = history.popRedo()
        assertNotNull(popped)
        assertFalse(history.hasRedo())
    }

    @Test
    fun `clearRedo empties redo stack`() {
        val history = History()
        history.pushRedo(listOf(createDummyOp()))
        assertTrue(history.hasRedo())

        history.clearRedo()
        assertFalse(history.hasRedo())
    }

    @Test
    fun `clearRedo on empty stack is a no-op`() {
        val history = History()
        assertFalse(history.hasRedo())
        history.clearRedo()
        assertFalse(history.hasRedo())
    }

    @Test
    fun `undo stack at exactly 50 retains all`() {
        val history = History()
        repeat(MaxUndoRedoStackDepth) {
            history.pushUndo(listOf(createDummyOp()))
        }
        assertEquals(MaxUndoRedoStackDepth, history.getUndoStackForTest().size)
    }

    @Test
    fun `undo stack at 51 evicts oldest`() {
        val history = History()
        repeat(MaxUndoRedoStackDepth + 1) {
            history.pushUndo(listOf(createDummyOp()))
        }
        assertEquals(MaxUndoRedoStackDepth, history.getUndoStackForTest().size)
    }

    @Test
    fun `redo stack depth limit`() {
        val history = History()
        repeat(60) {
            history.pushRedo(listOf(createDummyOp()))
        }
        assertEquals(MaxUndoRedoStackDepth, history.getRedoStackForTest().size)
    }

    @Test
    fun `popUndo returns null when empty`() {
        val history = History()
        assertNull(history.popUndo())
    }

    @Test
    fun `popRedo returns null when empty`() {
        val history = History()
        assertNull(history.popRedo())
    }

    @Test
    fun `push after pop preserves ordering`() {
        val history = History()
        val op1 = listOf(createDummyOp())
        val op2 = listOf(createDummyOp())
        val op3 = listOf(createDummyOp())

        history.pushUndo(op1)
        history.pushUndo(op2)
        history.pushUndo(op3)

        // Pop last
        val popped = history.popUndo()
        assertEquals(op3, popped)

        // Push new
        val op4 = listOf(createDummyOp())
        history.pushUndo(op4)

        // Pop should return op4, then op2, then op1
        assertEquals(op4, history.popUndo())
        assertEquals(op2, history.popUndo())
        assertEquals(op1, history.popUndo())
        assertNull(history.popUndo())
    }

    @Test
    fun `getMaxDepth falls back to the default depth when constructed without one`() {
        assertEquals(MaxUndoRedoStackDepth, History().getMaxDepth())
    }

    @Test
    fun `getMaxDepth reports the depth it was constructed with`() {
        assertEquals(7, History(maxDepth = 7).getMaxDepth())
    }

    @Test
    fun `undo stack drops the oldest entry once a custom depth is exceeded`() {
        val history = History(maxDepth = 3)
        for (n in 0 until 5) {
            history.pushUndo(listOf(markedOp(n.toLong())))
        }
        assertEquals(listOf(2L, 3L, 4L), marks(history.getUndoStackForTest()))
    }

    @Test
    fun `redo stack drops the oldest entry once a custom depth is exceeded`() {
        val history = History(maxDepth = 3)
        for (n in 0 until 5) {
            history.pushRedo(listOf(markedOp(n.toLong())))
        }
        assertEquals(listOf(2L, 3L, 4L), marks(history.getRedoStackForTest()))
    }

    @Test
    fun `redo stack bounds independently of the default depth when custom depth exceeds it`() {
        val customDepth = MaxUndoRedoStackDepth + 5
        val history = History(maxDepth = customDepth)
        for (n in 0 until customDepth) {
            history.pushRedo(listOf(markedOp(n.toLong())))
        }
        assertEquals(customDepth, history.getRedoStackForTest().size)
        assertEquals(0L, marks(history.getRedoStackForTest()).first())
    }
}
