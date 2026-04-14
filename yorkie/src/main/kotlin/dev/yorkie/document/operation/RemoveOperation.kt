package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [RemoveOperation] is an operation that removes an element from [CrdtContainer].
 */
internal data class RemoveOperation(
    var createdAt: TimeTicket,
    override var parentCreatedAt: TimeTicket,
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

            val reverseOps = when (parentObject) {
                is CrdtArray -> {
                    val elementCopy = element.deepCopy()
                    val addOp = AddOperation(
                        prevCreatedAt = prevCreatedAtForArray!!,
                        value = stripChildren(elementCopy),
                        parentCreatedAt = parentCreatedAt,
                        executedAt = executedAt,
                    )
                    listOf(addOp) + childSetOps(element, element.createdAt, executedAt)
                }

                is CrdtObject -> {
                    listOf(
                        SetOperation(
                            key = key ?: "",
                            value = element.deepCopy(),
                            parentCreatedAt = parentCreatedAt,
                            executedAt = executedAt,
                        ),
                    )
                }

                else -> emptyList()
            }

            ExecutionResult(
                opInfos = listOf(
                    OperationInfo.RemoveOpInfo(key, index, root.createPath(parentCreatedAt)),
                ),
                reverseOps = reverseOps,
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "only object and array can execute remove: $parentObject")
            ExecutionResult(opInfos = emptyList())
        }
    }

    companion object {
        private const val TAG = "RemoveOperation"

        /**
         * Returns a deep copy of the element with children removed.
         * For CrdtObject, clears memberNodes. For other types, returns deepCopy as-is.
         */
        private fun stripChildren(element: CrdtElement): CrdtElement {
            return when (element) {
                is CrdtObject -> element.copy(memberNodes = dev.yorkie.document.crdt.ElementRht())
                else -> element
            }
        }

        /**
         * Generates SetOperations for each child of a CrdtObject so they are
         * individually serialized for remote sync during undo/redo.
         */
        private fun childSetOps(
            element: CrdtElement,
            parentCreatedAt: TimeTicket,
            executedAt: TimeTicket,
        ): List<Operation> {
            if (element !is CrdtObject) return emptyList()
            val ops = mutableListOf<Operation>()
            for ((childKey, child) in element) {
                if (child.isRemoved) continue
                ops.add(
                    SetOperation(
                        key = childKey,
                        value = child.deepCopy(),
                        parentCreatedAt = parentCreatedAt,
                        executedAt = executedAt,
                    ),
                )
            }
            return ops
        }
    }
}
