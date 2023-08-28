package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [TreeEditOperation] is an operation representing Tree editing.
 */
internal data class TreeEditOperation(
    override val parentCreatedAt: TimeTicket,
    val fromPos: CrdtTreePos,
    val toPos: CrdtTreePos,
    val maxCreatedAtMapByActor: Map<ActorID, TimeTicket>,
    val contents: List<CrdtTreeNode>?,
    override var executedAt: TimeTicket,
) : Operation() {
    override val effectedCreatedAt = parentCreatedAt

    override fun execute(root: CrdtRoot): List<OperationInfo> {
        val tree = root.findByCreatedAt(parentCreatedAt)
        if (tree == null) {
            YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            return emptyList()
        }
        if (tree !is CrdtTree) {
            YorkieLogger.e(TAG, "fail to execute, only Tree can execute edit")
            return emptyList()
        }
        val changes =
            tree.edit(
                fromPos to toPos,
                contents?.map(CrdtTreeNode::deepCopy),
                executedAt,
                maxCreatedAtMapByActor,
            ).first

        if (fromPos != toPos) {
            root.registerElementHasRemovedNodes(tree)
        }

        return changes.map {
            OperationInfo.TreeEditOpInfo(
                it.from,
                it.to,
                it.fromPath,
                it.toPath,
                it.value,
            ).apply {
                executedAt = parentCreatedAt
            }
        }
    }

    companion object {
        private const val TAG = "TreeEditOperation"
    }
}
