package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.crdt.RgaTreeSplitNodeRange
import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

internal data class StyleOperation(
    val fromPos: RgaTreeSplitNodePos,
    val toPos: RgaTreeSplitNodePos,
    val attributes: Map<String, String>,
    override val parentCreatedAt: TimeTicket,
    override var executedAt: TimeTicket,
) : Operation() {

    override val effectedCreatedAt: TimeTicket
        get() = parentCreatedAt

    override fun execute(root: CrdtRoot): List<InternalOpInfo> {
        val parentObject = root.findByCreatedAt(parentCreatedAt)
        return if (parentObject is CrdtText) {
            val changes =
                parentObject.style(RgaTreeSplitNodeRange(fromPos, toPos), attributes, executedAt)
            changes.map {
                InternalOpInfo(
                    parentCreatedAt,
                    OperationInfo.StyleOpInfo(
                        it.from,
                        it.to,
                        TextWithAttributes(it.content.orEmpty() to it.attributes.orEmpty()),
                    ),
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
