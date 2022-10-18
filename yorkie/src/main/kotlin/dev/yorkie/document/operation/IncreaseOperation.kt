package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.Primitive
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [IncreaseOperation] represents an operation that increments a numeric value to [CrdtCounter].
 * Among [Primitive] elements, numeric types Integer, Long, and Double are used as values.
 */
internal class IncreaseOperation(
    val value: CrdtElement,
    parentCreatedAt: TimeTicket,
    executedAt: TimeTicket,
) : Operation(parentCreatedAt, executedAt) {

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
            val copiedValue = value.deepCopy() as Primitive
            parentObject.increase(copiedValue)
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "fail to execute, only Counter can execute increase")
        }
    }

    /**
     * Returns a string containing the meta data.
     */
    override fun toString(): String {
        return "$parentCreatedAt.INCREASE"
    }

    companion object {
        private const val TAG = "IncreaseOperation"
    }
}
