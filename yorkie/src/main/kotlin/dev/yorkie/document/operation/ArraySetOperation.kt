package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.YorkieException

/**
 * `ArraySetOperation` is an operation representing setting an element in Array.
 */
internal data class ArraySetOperation(
    var createdAt: TimeTicket,
    val value: CrdtElement,
    override var parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {
    override val effectedCreatedAt: TimeTicket
        get() = createdAt

    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "fail to find $parentCreatedAt",
            )

        if (parentObject !is CrdtArray) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "fail to execute, only array can execute set",
            )
        }

        val previousValue = parentObject[createdAt]
        val value = value.deepCopy()
        value.removedAt = null
        parentObject.insertAfter(createdAt, value, executedAt)
        parentObject.delete(createdAt, executedAt)

        root.registerElement(value, null)

        val reverseOp = if (source.producesReverseOps) {
            previousValue?.let {
                ArraySetOperation(
                    // Targets the element this op INSTALLED (`value`'s own createdAt,
                    // preserved by deepCopy), not the element it displaced: undo must
                    // remove the installed element and restore the displaced one,
                    // which is exactly what `value` (this reverse's own installed
                    // value) carries as its creation identity (JS `28f4ad26`/#1059).
                    createdAt = value.createdAt,
                    value = it.deepCopy(),
                    parentCreatedAt = parentCreatedAt,
                    executedAt = executedAt,
                )
            }
        } else {
            null
        }

        return ExecutionResult(
            opInfos = listOf(
                OperationInfo.ArraySetOpInfo(
                    path = root.createPath(parentCreatedAt),
                ),
            ),
            reverseOps = listOfNotNull(reverseOp),
        )
    }
}
