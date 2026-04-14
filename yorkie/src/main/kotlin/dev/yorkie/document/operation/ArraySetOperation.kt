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
        parentObject.insertAfter(createdAt, value, executedAt)
        parentObject.delete(createdAt, executedAt)

        root.registerElement(value, null)

        val reverseOp = previousValue?.let {
            ArraySetOperation(
                createdAt = createdAt,
                value = it.deepCopy(),
                parentCreatedAt = parentCreatedAt,
                executedAt = executedAt,
            )
        }

        return ExecutionResult(
            opInfos = listOf(
                OperationInfo.ArraySetOpInfo(
                    path = root.createPath(parentCreatedAt),
                ),
            ),
            reverseOp = reverseOp,
        )
    }
}
