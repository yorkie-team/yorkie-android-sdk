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
    val createdAt: TimeTicket,
    val value: CrdtElement,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {
    override val effectedCreatedAt: TimeTicket
        get() = createdAt

    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
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

        val value = value.deepCopy()
        parentObject.insertAfter(createdAt, value, executedAt)
        parentObject.delete(createdAt, executedAt)

        // TODO(junseo): GC logic is not implemented here
        // because there is no way to distinguish between old and new element with same `createdAt`.
        root.registerElement(value, null)

        // TODO(emplam27): The reverse operation is not implemented yet.
        val reverseOp = null

        return listOf(
            OperationInfo.ArraySetOpInfo(
                path = root.createPath(parentCreatedAt),
            ),
        )
    }
}
