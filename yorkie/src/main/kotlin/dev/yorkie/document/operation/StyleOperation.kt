package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

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

    override fun execute(root: CrdtRoot, versionVector: VersionVector?): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtText) {
            val changes = parentObject.style(
                RgaTreeSplitPosRange(fromPos, toPos),
                attributes,
                executedAt,
                maxCreatedAtMapByActor,
                versionVector,
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
            parentObject ?: logError(TAG, "fail to find $parentCreatedAt")
            logError(TAG, "fail to execute, only Text can execute style")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "StyleOperation"
    }
}
