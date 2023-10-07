package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.operation.OperationInfo.TreeStyleOpInfo
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [TreeStyleOperation] represents an operation that modifies the style of the node in the Tree.
 */
internal data class TreeStyleOperation(
    override val parentCreatedAt: TimeTicket,
    val fromPos: CrdtTreePos,
    val toPos: CrdtTreePos,
    val attributes: Map<String, String>,
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
        val changes = tree.style(fromPos to toPos, attributes.toMap(), executedAt)

        return changes.map {
            TreeStyleOpInfo(
                it.from,
                it.to,
                it.fromPath,
                it.toPath,
                it.attributes.orEmpty(),
                root.createPath(parentCreatedAt),
            )
        }
    }

    companion object {
        private const val TAG = "TreeStyleOperation"
    }
}
