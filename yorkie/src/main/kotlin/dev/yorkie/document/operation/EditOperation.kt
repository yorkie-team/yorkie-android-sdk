package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.crdt.RgaTreeSplitNodeRange
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [EditOperation] is an operations representing editing rich text.
 */
internal data class EditOperation(
    val fromPos: RgaTreeSplitNodePos,
    val toPos: RgaTreeSplitNodePos,
    val maxCreatedAtMapByActor: Map<ActorID, TimeTicket>,
    val content: String,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
    val attributes: Map<String, String>? = null,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    override fun execute(root: CrdtRoot) {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        if (parentObject is CrdtText) {
            parentObject.edit(
                RgaTreeSplitNodeRange(fromPos, toPos),
                content,
                executedAt,
                maxCreatedAtMapByActor,
                attributes,
            )
            if (fromPos != toPos) {
                root.registerTextWithGarbage(parentObject)
            }
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "fail to execute, only Text can execute edit")
        }
    }

    companion object {
        private const val TAG = "EditOperation"
    }
}
