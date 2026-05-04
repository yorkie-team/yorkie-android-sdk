package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

internal data class StyleOperation(
    val fromPos: RgaTreeSplitPos,
    val toPos: RgaTreeSplitPos,
    val attributes: Map<String, String>,
    override var parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
    val attributesToRemove: List<String> = emptyList(),
) : Operation() {

    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    override fun execute(
        root: CrdtRoot,
        source: OpSource,
        versionVector: VersionVector?,
    ): ExecutionResult {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtText) {
            val allChanges = mutableListOf<dev.yorkie.document.crdt.TextChange>()
            val reversePrevAttributes = mutableMapOf<String, String>()
            val reverseAttrsToRemove = mutableListOf<String>()

            if (attributesToRemove.isNotEmpty()) {
                val result = parentObject.removeStyle(
                    RgaTreeSplitPosRange(fromPos, toPos),
                    attributesToRemove,
                    executedAt,
                    versionVector,
                )
                root.acc(result.dataSize)
                result.gcPairs.forEach(root::registerGCPair)
                allChanges.addAll(result.textChanges)
                reversePrevAttributes.putAll(result.prevAttributes)
            }

            if (attributes.isNotEmpty()) {
                val result = parentObject.style(
                    RgaTreeSplitPosRange(fromPos, toPos),
                    attributes,
                    executedAt,
                    versionVector,
                )
                root.acc(result.dataSize)
                result.gcPairs.forEach(root::registerGCPair)
                allChanges.addAll(result.textChanges)
                reversePrevAttributes.putAll(result.prevAttributes)
                reverseAttrsToRemove.addAll(result.attributesToRemove)
            }

            val reverseOps = if (source == OpSource.Local || source == OpSource.UndoRedo) {
                buildReverseOps(reversePrevAttributes, reverseAttrsToRemove)
            } else {
                emptyList()
            }

            ExecutionResult(
                opInfos = allChanges.map {
                    OperationInfo.StyleOpInfo(
                        it.from,
                        it.to,
                        it.attributes.orEmpty(),
                        root.createPath(parentCreatedAt),
                    )
                },
                reverseOps = reverseOps,
            )
        } else {
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only Text can execute style")
            ExecutionResult(opInfos = emptyList())
        }
    }

    private fun buildReverseOps(
        prevAttributes: Map<String, String>,
        attrsToRemove: List<String>,
    ): List<Operation> {
        if (prevAttributes.isEmpty() && attrsToRemove.isEmpty()) return emptyList()
        val reverseOp = when {
            prevAttributes.isNotEmpty() && attrsToRemove.isNotEmpty() -> StyleOperation(
                fromPos = fromPos,
                toPos = toPos,
                attributes = prevAttributes,
                parentCreatedAt = parentCreatedAt,
                executedAt = executedAt,
                attributesToRemove = attrsToRemove,
            )
            attrsToRemove.isNotEmpty() -> StyleOperation(
                fromPos = fromPos,
                toPos = toPos,
                attributes = emptyMap(),
                parentCreatedAt = parentCreatedAt,
                executedAt = executedAt,
                attributesToRemove = attrsToRemove,
            )
            else -> StyleOperation(
                fromPos = fromPos,
                toPos = toPos,
                attributes = prevAttributes,
                parentCreatedAt = parentCreatedAt,
                executedAt = executedAt,
                attributesToRemove = emptyList(),
            )
        }
        return listOf(reverseOp)
    }

    companion object {
        private const val TAG = "StyleOperation"
    }
}
