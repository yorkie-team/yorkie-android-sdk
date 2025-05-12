package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtPrimitive.Type
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

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
     * Executes this [IncreaseOperation] on the given [root].
     */
    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtCounter) {
            val copiedValue = value.deepCopy() as CrdtPrimitive
            parentObject.increase(copiedValue)
            val increasedValue = if (copiedValue.type == Type.Integer) {
                copiedValue.value as Int
            } else {
                copiedValue.value as Long
            }
            listOf(OperationInfo.IncreaseOpInfo(increasedValue, root.createPath(parentCreatedAt)))
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only Counter can execute increase")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "IncreaseOperation"
    }
}
