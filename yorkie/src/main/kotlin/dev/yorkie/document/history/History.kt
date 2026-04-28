package dev.yorkie.document.history

import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.EditOperation
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

    fun clearUndo() {
        undoStack.clear()
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

    /**
     * Scans both undo and redo stacks for any [EditOperation] targeting the text
     * element identified by [parentCreatedAt] and calls [EditOperation.reconcileOperation]
     * with the integer offsets of the remote edit that just landed.
     */
    fun reconcileTextEdit(
        parentCreatedAt: TimeTicket,
        remoteFrom: Int,
        remoteTo: Int,
        remoteContentLen: Int,
    ) {
        reconcileTextStack(undoStack, parentCreatedAt, remoteFrom, remoteTo, remoteContentLen)
        reconcileTextStack(redoStack, parentCreatedAt, remoteFrom, remoteTo, remoteContentLen)
    }

    private fun reconcileTextStack(
        stack: ArrayDeque<List<HistoryOperation>>,
        parentCreatedAt: TimeTicket,
        remoteFrom: Int,
        remoteTo: Int,
        remoteContentLen: Int,
    ) {
        for (ops in stack) {
            for (historyOp in ops) {
                if (historyOp !is HistoryOperation.Op) continue
                val op = historyOp.operation
                if (op is EditOperation && op.parentCreatedAt == parentCreatedAt) {
                    op.reconcileOperation(remoteFrom, remoteTo, remoteContentLen)
                }
            }
        }
    }

    internal fun getUndoStackForTest() = undoStack.toList()
    internal fun getRedoStackForTest() = redoStack.toList()
}
