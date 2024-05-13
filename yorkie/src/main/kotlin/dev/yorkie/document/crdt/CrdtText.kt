package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtText] is a custom CRDT data type to represent the contents of text editors.
 */
internal data class CrdtText(
    val rgaTreeSplit: RgaTreeSplit<TextValue>,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtGCElement() {
    override val removedNodesLength: Int
        get() = rgaTreeSplit.removedNodesLength

    val values: List<TextWithAttributes>
        get() = rgaTreeSplit.filterNot {
            it.isRemoved
        }.map {
            TextWithAttributes(it.value.content to it.value.attributes)
        }

    val length: Int
        get() = rgaTreeSplit.length

    /**
     * Edits the given [range] with the given [value] and [attributes].
     */
    fun edit(
        range: RgaTreeSplitPosRange,
        value: String,
        executedAt: TimeTicket,
        attributes: Map<String, String>? = null,
        maxCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): TextOperationResult {
        val textValue = if (value.isNotEmpty()) {
            TextValue(value).apply {
                attributes?.forEach { setAttribute(it.key, it.value, executedAt) }
            }
        } else {
            null
        }

        val (caretPos, maxCreatedAtMap, contentChanges) = rgaTreeSplit.edit(
            range,
            executedAt,
            textValue,
            maxCreatedAtMapByActor,
        )

        val changes = contentChanges.map {
            TextChange(
                TextChangeType.Content,
                it.actorID,
                it.from,
                it.to,
                it.content,
            )
        }.toMutableList()

        if (value.isNotEmpty() && attributes != null) {
            changes[changes.lastIndex] = changes.last().copy(attributes = attributes)
        }
        return TextOperationResult(maxCreatedAtMap, changes, caretPos to caretPos)
    }

    /**
     * Applies the style of the given [range].
     * 1. Split nodes with from and to.
     * 2. Style nodes between from and to.
     */
    fun style(
        range: RgaTreeSplitPosRange,
        attributes: Map<String, String>,
        executedAt: TimeTicket,
        maxCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): TextOperationResult {
        // 1. Split nodes with from and to.
        val toRight = rgaTreeSplit.findNodeWithSplit(range.second, executedAt).second
        val fromRight = rgaTreeSplit.findNodeWithSplit(range.first, executedAt).second

        // 2. Style nodes between from and to.
        val nodes = rgaTreeSplit.findBetween(fromRight, toRight)
        val createdAtMapByActor = mutableMapOf<ActorID, TimeTicket>()
        val toBeStyleds = nodes.mapNotNull { node ->
            val actorID = node.createdAt.actorID
            val maxCreatedAt = if (maxCreatedAtMapByActor?.isNotEmpty() == true) {
                maxCreatedAtMapByActor[actorID] ?: TimeTicket.InitialTimeTicket
            } else {
                TimeTicket.MaxTimeTicket
            }

            node.takeIf {
                it.canStyle(executedAt, maxCreatedAt)
            }?.also {
                val updatedMaxCreatedAt = createdAtMapByActor[actorID]
                val updatedCreatedAt = node.createdAt
                if (updatedMaxCreatedAt == null || updatedMaxCreatedAt < updatedCreatedAt) {
                    createdAtMapByActor[actorID] = updatedCreatedAt
                }
            }
        }
        val changes = toBeStyleds
            .filterNot { it.isRemoved }
            .map { node ->
                val (fromIndex, toIndex) = rgaTreeSplit.findIndexesFromRange(node.createPosRange())
                attributes.forEach { node.value.setAttribute(it.key, it.value, executedAt) }
                TextChange(
                    TextChangeType.Style,
                    executedAt.actorID,
                    fromIndex,
                    toIndex,
                    null,
                    attributes,
                )
            }

        return TextOperationResult(createdAtMapByActor, changes)
    }

    /**
     * Returns a pair of [RgaTreeSplitPos] of the given integer offsets.
     */
    fun indexRangeToPosRange(fromIndex: Int, toIndex: Int): RgaTreeSplitPosRange {
        val fromPos = rgaTreeSplit.indexToPos(fromIndex)
        return if (fromIndex == toIndex) {
            RgaTreeSplitPosRange(fromPos, fromPos)
        } else {
            RgaTreeSplitPosRange(fromPos, rgaTreeSplit.indexToPos(toIndex))
        }
    }

    /**
     * Returns pair of integer offsets of the given [range].
     */
    fun findIndexesFromRange(range: RgaTreeSplitPosRange): Pair<Int, Int> {
        return rgaTreeSplit.findIndexesFromRange(range)
    }

    override fun deleteRemovedNodesBefore(executedAt: TimeTicket): Int {
        return rgaTreeSplit.deleteRemovedNodesBefore(executedAt)
    }

    override fun deepCopy(): CrdtElement {
        return copy(rgaTreeSplit = rgaTreeSplit.deepCopy())
    }

    override fun toString(): String {
        return rgaTreeSplit.toString()
    }
}
