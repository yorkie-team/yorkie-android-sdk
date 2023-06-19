package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.crdt.RgaTreeSplitNodeRange
import dev.yorkie.document.crdt.TextChangeType
import dev.yorkie.document.crdt.TextWithAttributes
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
                RgaTreeSplitNodeRange(fromPos, toPos),
                content,
                executedAt,
                attributes,
                maxCreatedAtMapByActor,
            ).second
            if (fromPos != toPos) {
                root.registerTextWithGarbage(parentObject)
            }
            changes.map { (type, _, from, to, content, attributes) ->
                if (type == TextChangeType.Content) {
                    OperationInfo.EditOpInfo(
                        from,
                        to,
                        TextWithAttributes(content.orEmpty() to attributes.orEmpty()),
                    )
                } else {
                    OperationInfo.SelectOpInfo(from, to)
                }.apply {
                    executedAt = parentCreatedAt
                }
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
