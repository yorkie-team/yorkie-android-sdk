package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

internal data class StyleOperation(
    val fromPos: RgaTreeSplitPos,
    val toPos: RgaTreeSplitPos,
    val maxCreatedAtMapByActor: Map<ActorID, TimeTicket>,
    val attributes: Map<String, String>,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    override fun execute(root: CrdtRoot): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtText) {
            val changes = parentObject.style(
                RgaTreeSplitPosRange(fromPos, toPos),
                attributes,
                executedAt,
                maxCreatedAtMapByActor,
            ).textChanges
            changes.map {
                OperationInfo.StyleOpInfo(
                    it.from,
                    it.to,
                    it.attributes.orEmpty(),
                    root.createPath(parentCreatedAt),
                )
            }
        } else {
            parentObject ?: YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            YorkieLogger.e(TAG, "fail to execute, only Text can execute style")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "StyleOperation"
    }
}
