package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector

/**
 * Result of executing an [Operation].
 *
 * [executed] is false only when the operation found no target to act on (its
 * parent/element lookup failed, or an explicit precondition rejected it
 * before any mutation) -- the JS SDK's counterpart signals this by having
 * `execute` return `undefined` instead of a result (document.ts /
 * change.ts). Kotlin's `execute` always returns an [ExecutionResult], so
 * [executed] carries the same distinction: a style or tree-style landing
 * only on a tombstone genuinely ran (canStyle admits it) and produces empty
 * [opInfos] with [executed] staying true, which is NOT the same condition.
 */
internal data class ExecutionResult(
    val opInfos: List<OperationInfo>,
    val reverseOps: List<Operation> = emptyList(),
    val executed: Boolean = true,
)

/**
 * Represents an operation to be executed on a document.
 * [parentCreatedAt] is the creation time of the target element to execute the operation.
 * [executedAt] is the execution time of this operation.
 */
internal abstract class Operation {
    abstract var parentCreatedAt: TimeTicket

    abstract var executedAt: TimeTicket

    /**
     * Returns the created time of the effected element.
     */
    abstract val effectedCreatedAt: TimeTicket

    /**
     * Executes this [Operation] on the given [root].
     */
    abstract fun execute(
        root: CrdtRoot,
        source: OpSource = OpSource.Local,
        versionVector: VersionVector? = null,
    ): ExecutionResult

    /**
     * Sets the given actorId to this [Operation].
     *
     * Open so [dev.yorkie.document.operation.TreeEditOperation] can also
     * re-stamp the split tickets it records alongside [executedAt] (port
     * JS SDK `document.ts`/`tree_edit_operation.ts` b69169c0, #1404): a
     * caller holding only this [Operation]-typed reference (every call site
     * today -- [dev.yorkie.document.change.Change.setActor], in turn
     * [dev.yorkie.document.Document.setActor]) must still reach the override
     * through ordinary virtual dispatch, the Kotlin analogue of the
     * iOS-side static-dispatch finding this same port fixed there via a
     * protocol requirement.
     */
    open fun setActor(actorID: String) {
        executedAt = executedAt.setActor(actorID)
    }
}
