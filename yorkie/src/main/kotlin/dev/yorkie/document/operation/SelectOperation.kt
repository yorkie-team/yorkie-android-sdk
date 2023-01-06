package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.crdt.RgaTreeSplitNodeRange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

internal data class SelectOperation(
    val fromPos: RgaTreeSplitNodePos,
    val toPos: RgaTreeSplitNodePos,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    /**
     * Returns the created time of the effected element.
     */
    override fun execute(root: CrdtRoot) {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        if (parentObject is CrdtText) {
            parentObject.select(RgaTreeSplitNodeRange(fromPos, toPos), executedAt)
        } else {
            parentObject ?: YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            YorkieLogger.e(TAG, "fail to execute, only Text, RichText can execute select")
        }
    }

    companion object {
        private const val TAG = "SelectOperation"
    }
}
