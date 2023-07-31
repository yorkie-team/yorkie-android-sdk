package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

internal data class SelectOperation(
    val fromPos: RgaTreeSplitPos,
    val toPos: RgaTreeSplitPos,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    /**
     * Returns the created time of the effected element.
     */
    override fun execute(root: CrdtRoot): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtText) {
            val change = parentObject.select(RgaTreeSplitPosRange(fromPos, toPos), executedAt)
                ?: return emptyList()
            listOf(
                OperationInfo.SelectOpInfo(from = change.from, to = change.to).apply {
                    executedAt = parentCreatedAt
                },
            )
        } else {
            parentObject ?: YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            YorkieLogger.e(TAG, "fail to execute, only Text, RichText can execute select")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "SelectOperation"
    }
}
