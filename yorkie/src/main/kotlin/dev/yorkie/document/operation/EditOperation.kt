package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [EditOperation] is an operations representing editing rich text.
 */
internal data class EditOperation(
    val fromPos: RgaTreeSplitPos,
    val toPos: RgaTreeSplitPos,
    val maxCreatedAtMapByActor: Map<ActorID, TimeTicket>,
    val content: String,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
    val attributes: Map<String, String>,
) : Operation() {

    /**
     * Returns the created time of the effected element.
     */
    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    override fun execute(root: CrdtRoot): List<OperationInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtText) {
            val changes = parentObject.edit(
                RgaTreeSplitPosRange(fromPos, toPos),
                content,
                executedAt,
                attributes,
                maxCreatedAtMapByActor,
            ).second
            if (fromPos != toPos) {
                root.registerElementHasRemovedNodes(parentObject)
            }
            changes.map { (_, _, from, to, content, attributes) ->
                OperationInfo.EditOpInfo(
                    from,
                    to,
                    TextWithAttributes(content.orEmpty() to attributes.orEmpty()),
                    root.createPath(parentCreatedAt),
                )
            }
        } else {
            if (parentObject == null) {
                YorkieLogger.e(TAG, "fail to find $parentCreatedAt")
            }
            YorkieLogger.e(TAG, "fail to execute, only Text can execute edit")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "EditOperation"
    }
}
