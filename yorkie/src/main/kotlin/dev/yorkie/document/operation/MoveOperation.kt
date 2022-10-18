package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [MoveOperation] is an operation representing moving an element to an [CrdtArray].
 */
internal class MoveOperation(
    val prevCreatedAt: TimeTicket,
    val createdAt: TimeTicket,
    parentCreatedAt: TimeTicket,
    executedAt: TimeTicket,
) : Operation(parentCreatedAt, executedAt) {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = createdAt

    /**
     * Executes this [MoveOperation] on the given [Document.root].
     */
    override fun execute(root: CrdtRoot) {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        if (parentObject is CrdtArray) {
            parentObject.moveAfter(
                prevCreatedAt,
                createdAt,
                executedAt,
            )
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "fail to execute, only array can execute move")
        }
    }

    /**
     * Returns a string containing the meta data.
     */
    override fun toString(): String {
        return "$parentCreatedAt.MOVE"
    }

    companion object {
        private const val TAG = "MoveOperation"
    }
}
