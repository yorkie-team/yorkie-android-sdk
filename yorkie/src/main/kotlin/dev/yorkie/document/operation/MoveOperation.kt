package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [MoveOperation] is an operation representing moving an element to an [CrdtArray].
 */
internal data class MoveOperation(
    val prevCreatedAt: TimeTicket,
    val createdAt: TimeTicket,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = createdAt

    /**
     * Executes this [MoveOperation] on the given [root].
     */
    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtArray) {
            val previousIndex = parentObject.subPathOf(createdAt).toInt()
            parentObject.moveAfter(prevCreatedAt, createdAt, executedAt)
            val index = parentObject.subPathOf(createdAt).toInt()
            listOf(
                OperationInfo.MoveOpInfo(
                    previousIndex = previousIndex,
                    index = index,
                    path = root.createPath(parentCreatedAt),
                ),
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only array can execute move")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "MoveOperation"
    }
}
