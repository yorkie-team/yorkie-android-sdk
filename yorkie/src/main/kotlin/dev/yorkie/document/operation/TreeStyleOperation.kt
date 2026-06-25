package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.operation.OperationInfo.TreeStyleOpInfo
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

/**
 * [TreeStyleOperation] represents an operation that modifies the style of the node in the Tree.
 */
internal data class TreeStyleOperation(
    override var parentCreatedAt: TimeTicket,
    val fromPos: CrdtTreePos,
    val toPos: CrdtTreePos,
    override var executedAt: TimeTicket,
    val attributes: Map<String, String>? = null,
    val attributesToRemove: List<String>? = null,
) : Operation() {
    override val effectedCreatedAt = parentCreatedAt

    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val tree = root.findByCreatedAt(parentCreatedAt)
        if (tree == null) {
            logError(TAG, "fail to find $parentCreatedAt")
            return ExecutionResult(opInfos = emptyList())
        }
        if (tree !is CrdtTree) {
            logError(TAG, "fail to execute, only Tree can execute edit")
            return ExecutionResult(opInfos = emptyList())
        }

        val reversePrevAttributes = mutableMapOf<String, String>()
        val reverseAttrsToRemove = mutableListOf<String>()

        val opInfos = when {
            attributes?.isNotEmpty() == true -> {
                val result = tree.style(
                    fromPos to toPos,
                    attributes,
                    executedAt,
                    versionVector,
                )

                root.acc(result.dataSize)
                result.gcPairs.forEach(root::registerGCPair)

                reversePrevAttributes.putAll(result.prevAttributes)
                reverseAttrsToRemove.addAll(result.attributesToRemove)

                result.changes.map {
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
                val result = tree.removeStyle(
                    fromPos to toPos,
                    attributesToRemove,
                    executedAt,
                    versionVector,
                )
                result.gcPairs.forEach(root::registerGCPair)

                reversePrevAttributes.putAll(result.prevAttributes)

                result.changes.map {
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

        val reverseOps = if (source == OpSource.Local || source == OpSource.UndoRedo) {
            buildReverseOps(reversePrevAttributes, reverseAttrsToRemove)
        } else {
            emptyList()
        }

        return ExecutionResult(opInfos = opInfos, reverseOps = reverseOps)
    }

    private fun buildReverseOps(
        prevAttributes: Map<String, String>,
        attrsToRemove: List<String>,
    ): List<Operation> {
        if (prevAttributes.isEmpty() && attrsToRemove.isEmpty()) return emptyList()
        val reverseOp = when {
            prevAttributes.isNotEmpty() && attrsToRemove.isNotEmpty() -> TreeStyleOperation(
                parentCreatedAt = parentCreatedAt,
                fromPos = fromPos,
                toPos = toPos,
                executedAt = executedAt,
                attributes = prevAttributes,
                attributesToRemove = attrsToRemove,
            )
            attrsToRemove.isNotEmpty() -> TreeStyleOperation(
                parentCreatedAt = parentCreatedAt,
                fromPos = fromPos,
                toPos = toPos,
                executedAt = executedAt,
                attributes = null,
                attributesToRemove = attrsToRemove,
            )
            else -> TreeStyleOperation(
                parentCreatedAt = parentCreatedAt,
                fromPos = fromPos,
                toPos = toPos,
                executedAt = executedAt,
                attributes = prevAttributes,
                attributesToRemove = null,
            )
        }
        return listOf(reverseOp)
    }

    companion object {
        private const val TAG = "TreeStyleOperation"
    }
}
