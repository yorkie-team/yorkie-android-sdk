package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [AddOperation] is an operation representing adding an element to an [CrdtArray].
 */
internal data class AddOperation(
    val prevCreatedAt: TimeTicket,
    val value: CrdtElement,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = value.createdAt

    /**
     * Executes this [AddOperation] on the given [Document.root].
     */
    override fun execute(root: CrdtRoot) {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        if (parentObject is CrdtArray) {
            val copiedValue = value.deepCopy()
            parentObject.insertAfter(prevCreatedAt, copiedValue)
            root.registerElement(copiedValue, parentObject)
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "fail to execute, only array can execute add")
        }
    }

    companion object {
        private const val TAG = "AddOperation"
    }
}
