package dev.yorkie.document.operation

/**
 * Represents the source of an operation. Used to handle corner cases
 * in undo/redo (e.g., allowing removed elements to be restored).
 */
internal enum class OpSource {
    Local,
    Remote,
    UndoRedo,
}
