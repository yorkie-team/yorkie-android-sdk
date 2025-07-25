package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.operation.OperationInfo.TreeStyleOpInfo
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [TreeStyleOperation] represents an operation that modifies the style of the node in the Tree.
 */
internal data class TreeStyleOperation(
    override val parentCreatedAt: TimeTicket,
    val fromPos: CrdtTreePos,
    val toPos: CrdtTreePos,
    override var executedAt: TimeTicket,
    val maxCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    val attributes: Map<String, String>? = null,
    val attributesToRemove: List<String>? = null,
) : Operation() {
    override val effectedCreatedAt = parentCreatedAt

    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
        val tree = root.findByCreatedAt(parentCreatedAt)
        if (tree == null) {
            logError(TAG, "fail to find $parentCreatedAt")
            return emptyList()
        }
        if (tree !is CrdtTree) {
            logError(TAG, "fail to execute, only Tree can execute edit")
            return emptyList()
        }

        return when {
            attributes?.isNotEmpty() == true -> {
                val (changes, gcPairs) = tree.style(
                    fromPos to toPos,
                    attributes,
                    executedAt,
                    maxCreatedAtMapByActor,
                    versionVector,
                )
                gcPairs.forEach(root::registerGCPair)
                changes.map {
                    TreeStyleOpInfo(
                        it.from,
                        it.to,
                        it.fromPath,
                        it.toPath,
                        it.attributes.orEmpty(),
                        path = root.createPath(parentCreatedAt),
                    )
                }
            }

            attributesToRemove?.isNotEmpty() == true -> {
                val (changes, gcPairs) = tree.removeStyle(
                    fromPos to toPos,
                    attributesToRemove,
                    executedAt,
                    maxCreatedAtMapByActor,
                    versionVector,
                )
                gcPairs.forEach(root::registerGCPair)
                changes.map {
                    TreeStyleOpInfo(
                        it.from,
                        it.to,
                        it.fromPath,
                        it.toPath,
                        attributesToRemove = it.attributesToRemove.orEmpty(),
                        path = root.createPath(parentCreatedAt),
                    )
                }
            }

            else -> emptyList()
        }
    }

    companion object {
        private const val TAG = "TreeStyleOperation"
    }
}
