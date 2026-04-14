package dev.yorkie.document.history

import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.time.TimeTicket

/**
 * Maximum depth of undo/redo stack.
 */
@Suppress("ktlint:standard:property-naming")
internal const val MaxUndoRedoStackDepth = 50

/**
 * Stores the undo/redo history of a [Document][dev.yorkie.document.Document].
 */
internal class History {
    private val undoStack = ArrayDeque<List<HistoryOperation>>()
    private val redoStack = ArrayDeque<List<HistoryOperation>>()

    fun hasUndo(): Boolean = undoStack.isNotEmpty()

    fun hasRedo(): Boolean = redoStack.isNotEmpty()

    fun pushUndo(ops: List<HistoryOperation>) {
        if (undoStack.size >= MaxUndoRedoStackDepth) {
            undoStack.removeFirst()
        }
        undoStack.addLast(ops)
    }

    fun popUndo(): List<HistoryOperation>? {
        return if (undoStack.isNotEmpty()) undoStack.removeLast() else null
    }

    fun pushRedo(ops: List<HistoryOperation>) {
        if (redoStack.size >= MaxUndoRedoStackDepth) {
            redoStack.removeFirst()
        }
        redoStack.addLast(ops)
    }

    fun popRedo(): List<HistoryOperation>? {
        return if (redoStack.isNotEmpty()) redoStack.removeLast() else null
    }

    fun clearRedo() {
        redoStack.clear()
    }

    /**
     * Scans both stacks and replaces old createdAt/prevCreatedAt references
     * when elements are replaced during undo. Ensures consistency when an
     * element receives a new createdAt (executedAt) during undo/redo.
     */
    fun reconcileCreatedAt(prevCreatedAt: TimeTicket, currCreatedAt: TimeTicket) {
        reconcileStack(undoStack, prevCreatedAt, currCreatedAt)
        reconcileStack(redoStack, prevCreatedAt, currCreatedAt)
    }

    private fun reconcileStack(
        stack: ArrayDeque<List<HistoryOperation>>,
        prevCreatedAt: TimeTicket,
        currCreatedAt: TimeTicket,
    ) {
        for (ops in stack) {
            for (historyOp in ops) {
                if (historyOp !is HistoryOperation.Op) continue
                val op = historyOp.operation

                // Update parentCreatedAt for all operation types
                if (op.parentCreatedAt === prevCreatedAt) {
                    op.parentCreatedAt = currCreatedAt
                }

                // Update type-specific createdAt references
                when (op) {
                    is ArraySetOperation -> {
                        if (op.createdAt === prevCreatedAt) {
                            op.createdAt = currCreatedAt
                        }
                    }

                    is RemoveOperation -> {
                        if (op.createdAt === prevCreatedAt) {
                            op.createdAt = currCreatedAt
                        }
                    }

                    is MoveOperation -> {
                        if (op.createdAt === prevCreatedAt) {
                            op.createdAt = currCreatedAt
                        }
                        if (op.prevCreatedAt === prevCreatedAt) {
                            op.prevCreatedAt = currCreatedAt
                        }
                    }

                    is AddOperation -> {
                        if (op.prevCreatedAt === prevCreatedAt) {
                            op.prevCreatedAt = currCreatedAt
                        }
                    }

                    else -> {
                        // No additional reconciliation needed
                    }
                }
            }
        }
    }

    internal fun getUndoStackForTest() = undoStack.toList()
    internal fun getRedoStackForTest() = redoStack.toList()
}
