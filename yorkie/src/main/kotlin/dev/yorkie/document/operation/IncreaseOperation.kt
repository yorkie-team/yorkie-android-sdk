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
 *
 * When [actor] is non-empty and the target counter is in dedup mode, this operation routes
 * through [CrdtCounter.increaseDedup] so the HLL sketch is updated for unique-visitor counting.
 */
internal data class IncreaseOperation(
    val value: CrdtElement,
    override var parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
    val actor: String = "",
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    /**
     * Executes this [IncreaseOperation] on the given [root].
     */
    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtCounter) {
            val copiedValue = value.deepCopy() as CrdtPrimitive
            if (parentObject.isDedup()) {
                if (actor.isEmpty()) {
                    logError(TAG, "dedup counter requires actor")
                    return ExecutionResult(opInfos = emptyList())
                }
                parentObject.increaseDedup(copiedValue, actor)
                val newValue = parentObject.value
                ExecutionResult(
                    opInfos = listOf(
                        OperationInfo.IncreaseOpInfo(
                            newValue,
                            root.createPath(parentCreatedAt),
                        ),
                    ),
                    // Dedup increments are not reversible since HLL cannot un-add an actor.
                    reverseOps = emptyList(),
                )
            } else {
                parentObject.increase(copiedValue)
                val increasedValue = if (copiedValue.type == Type.Integer) {
                    copiedValue.value as Int
                } else {
                    copiedValue.value as Long
                }

                val negatedValue = if (copiedValue.type == Type.Integer) {
                    CrdtPrimitive(-(copiedValue.value as Int), copiedValue.createdAt)
                } else {
                    CrdtPrimitive(-(copiedValue.value as Long), copiedValue.createdAt)
                }
                val reverseOp = IncreaseOperation(
                    value = negatedValue,
                    parentCreatedAt = parentCreatedAt,
                    executedAt = executedAt,
                )

                ExecutionResult(
                    opInfos = listOf(
                        OperationInfo.IncreaseOpInfo(
                            increasedValue,
                            root.createPath(parentCreatedAt),
                        ),
                    ),
                    reverseOps = listOf(reverseOp),
                )
            }
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only Counter can execute increase")
            ExecutionResult(opInfos = emptyList())
        }
    }

    companion object {
        private const val TAG = "IncreaseOperation"
    }
}
