package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [SetOperation] represents an operation that stores the value corresponding to the
 * given key in [CrdtObject].
 */
internal data class SetOperation(
    val key: String,
    val value: CrdtElement,
    override var parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = value.createdAt

    /**
     * Executes this [SetOperation] on the given [root].
     */
    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtObject) {
            val previousValue = if (parentObject.has(key)) {
                parentObject[key].takeIf { !it.isRemoved }
            } else {
                null
            }
            val copiedValue = value.deepCopy()
            copiedValue.removedAt = null
            val removed = parentObject.set(key, copiedValue, executedAt)
            // An undo of a removal restores a copy under the tombstone's
            // createdAt; retire the tombstone's stale GC entry on EVERY
            // replica — this is a condition on the tree, not on who applies
            // it (yorkie-js-sdk#1341). An ordinary set carries a fresh
            // createdAt, so this is one map miss. Tombstones inside the copy,
            // and the losing side of a concurrent set, are booked into gc by
            // registerElement itself (yorkie-js-sdk#1350).
            root.unregisterRemovedElementPair(copiedValue.createdAt)
            root.registerElement(copiedValue, parentObject)
            removed?.let(root::registerRemovedElement)

            val reverseOps = if (source.producesReverseOps) {
                val reverseOp = if (previousValue != null) {
                    SetOperation(key, previousValue.deepCopy(), parentCreatedAt, executedAt)
                } else {
                    RemoveOperation(copiedValue.createdAt, parentCreatedAt, executedAt)
                }
                listOf(reverseOp)
            } else {
                emptyList()
            }

            ExecutionResult(
                opInfos = listOf(
                    OperationInfo.SetOpInfo(key, root.createPath(parentCreatedAt)),
                ),
                reverseOps = reverseOps,
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only object can execute set")
            ExecutionResult(opInfos = emptyList(), executed = false)
        }
    }

    companion object {
        private const val TAG = "SetOperation"
    }
}
