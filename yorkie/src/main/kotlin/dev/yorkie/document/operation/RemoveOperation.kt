package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [RemoveOperation] is an operation that removes an element from [CrdtContainer].
 */
internal data class RemoveOperation(
    var createdAt: TimeTicket,
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
    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtContainer) {
            // Capture BEFORE delete for reverse op
            val key = parentObject.subPathOf(createdAt)
            val prevCreatedAtForArray = if (parentObject is CrdtArray) {
                parentObject.getPrevCreatedAt(createdAt)
            } else {
                null
            }

            val element = parentObject.delete(createdAt, executedAt)
            root.registerRemovedElement(element)
            val index = if (parentObject is CrdtArray) key?.toInt() else null

            val reverseOp = when (parentObject) {
                is CrdtArray -> {
                    AddOperation(
                        prevCreatedAt = prevCreatedAtForArray!!,
                        value = element.deepCopy(),
                        parentCreatedAt = parentCreatedAt,
                        executedAt = executedAt,
                    )
                }

                is CrdtObject -> {
                    SetOperation(
                        key = key ?: "",
                        value = element.deepCopy(),
                        parentCreatedAt = parentCreatedAt,
                        executedAt = executedAt,
                    )
                }

                else -> null
            }

            ExecutionResult(
                opInfos = listOf(
                    OperationInfo.RemoveOpInfo(key, index, root.createPath(parentCreatedAt)),
                ),
                reverseOp = reverseOp,
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "only object and array can execute remove: $parentObject")
            ExecutionResult(opInfos = emptyList())
        }
    }

    companion object {
        private const val TAG = "RemoveOperation"
    }
}
