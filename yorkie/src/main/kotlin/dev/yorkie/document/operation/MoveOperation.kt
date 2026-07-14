package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.GCPair
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [MoveOperation] is an operation representing moving an element to an [CrdtArray].
 */
internal data class MoveOperation(
    var prevCreatedAt: TimeTicket,
    var createdAt: TimeTicket,
    override var parentCreatedAt: TimeTicket,
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
    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtArray) {
            val prevCreatedAtBefore = parentObject.getPrevCreatedAt(createdAt)
            val previousIndex = parentObject.subPathOf(createdAt).toInt()
            val deadNode = parentObject.moveAfter(prevCreatedAt, createdAt, executedAt)
            if (deadNode != null) {
                root.registerGCPair(GCPair(parentObject.getRGATreeList(), deadNode))
            }
            val index = parentObject.subPathOf(createdAt).toInt()

            val reverseOp = MoveOperation(
                prevCreatedAt = prevCreatedAtBefore,
                createdAt = createdAt,
                parentCreatedAt = parentCreatedAt,
                executedAt = executedAt,
            )

            ExecutionResult(
                opInfos = listOf(
                    OperationInfo.MoveOpInfo(
                        previousIndex = previousIndex,
                        index = index,
                        path = root.createPath(parentCreatedAt),
                    ),
                ),
                reverseOps = listOf(reverseOp),
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only array can execute move")
            ExecutionResult(opInfos = emptyList())
        }
    }

    companion object {
        private const val TAG = "MoveOperation"
    }
}
