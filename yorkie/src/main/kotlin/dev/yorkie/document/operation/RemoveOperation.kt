package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [RemoveOperation] is an operation representing removes an element from [CrdtContainer].
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
     * Executes this [RemoveOperation] on the given [Document.root].
     */
    override fun execute(root: CrdtRoot) {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        if (parentObject is CrdtContainer) {
            val element = parentObject.remove(createdAt, executedAt)
            root.registerRemovedElement(element)
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "only object and array can execute remove: $parentObject")
        }
    }

    companion object {
        private const val TAG = "RemoveOperation"
    }
}
