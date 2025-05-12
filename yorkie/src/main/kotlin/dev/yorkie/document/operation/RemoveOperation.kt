package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [RemoveOperation] is an operation that removes an element from [CrdtContainer].
 */
internal data class RemoveOperation(
    val createdAt: TimeTicket,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    /**
     * Executes this [RemoveOperation] on the given [root].
     */
    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtContainer) {
            val key = parentObject.subPathOf(createdAt)
            val element = parentObject.remove(createdAt, executedAt)
            root.registerRemovedElement(element)
            val index = if (parentObject is CrdtArray) key?.toInt() else null
            listOf(OperationInfo.RemoveOpInfo(key, index, root.createPath(parentCreatedAt)))
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "only object and array can execute remove: $parentObject")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "RemoveOperation"
    }
}
