package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

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

    companion object {
        private const val TAG = "MoveOperation"
    }
}
