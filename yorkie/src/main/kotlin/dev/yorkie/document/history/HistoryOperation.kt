package dev.yorkie.document.history

import dev.yorkie.document.operation.Operation

/**
 * Represents an operation stored in the undo/redo history.
 */
internal sealed interface HistoryOperation {
    data class Op(val operation: Operation) : HistoryOperation
    data class Presence(val value: Map<String, String>) : HistoryOperation
}
