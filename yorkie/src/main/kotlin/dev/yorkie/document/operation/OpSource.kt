package dev.yorkie.document.operation

/**
 * Represents the source of an operation. Used to handle corner cases
 * in undo/redo (e.g., allowing removed elements to be restored).
 */
internal enum class OpSource {
    Local,
    Remote,
    UndoRedo,

    /**
     * A local change executed with `skipHistory = true`. Behaves like [Local] for
     * the document mutation itself, but is treated as history-exempt: it produces
     * no reverse operations (see [producesReverseOps]) since it is never pushed
     * onto the undo stack.
     */
    LocalNoHistory,
    ;

    /**
     * Returns true when operations executed with this source should generate
     * reverse operations for the undo/redo stack. Only [Local] and [UndoRedo]
     * changes are ever pushed onto the undo stack, so only they need reverse ops.
     */
    val producesReverseOps: Boolean
        get() = this == Local || this == UndoRedo
}
