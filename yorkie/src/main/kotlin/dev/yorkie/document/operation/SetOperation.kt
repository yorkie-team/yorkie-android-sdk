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
            root.registerElement(copiedValue, parentObject)
            removed?.let(root::registerRemovedElement)

            val reverseOp = if (previousValue != null) {
                SetOperation(key, previousValue.deepCopy(), parentCreatedAt, executedAt)
            } else {
                RemoveOperation(copiedValue.createdAt, parentCreatedAt, executedAt)
            }

            ExecutionResult(
                opInfos = listOf(
                    OperationInfo.SetOpInfo(key, root.createPath(parentCreatedAt)),
                ),
                reverseOps = listOf(reverseOp),
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only object can execute set")
            ExecutionResult(opInfos = emptyList())
        }
    }

    companion object {
        private const val TAG = "SetOperation"
    }
}
