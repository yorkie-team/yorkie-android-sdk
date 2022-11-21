package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [IncreaseOperation] represents an operation that increments a numeric value to [CrdtCounter].
 * Among [CrdtPrimitive] elements, numeric types Integer and Long are used as values.
 */
internal data class IncreaseOperation(
    val value: CrdtElement,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    /**
     * Executes this [IncreaseOperation] on the given [Document.root].
     */
    override fun execute(root: CrdtRoot) {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        if (parentObject is CrdtCounter) {
            val copiedValue = value.deepCopy() as CrdtPrimitive
            parentObject.increase(copiedValue)
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "fail to execute, only Counter can execute increase")
        }
    }

    companion object {
        private const val TAG = "IncreaseOperation"
    }
}
