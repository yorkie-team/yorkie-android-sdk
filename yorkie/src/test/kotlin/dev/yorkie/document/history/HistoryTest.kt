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
}
