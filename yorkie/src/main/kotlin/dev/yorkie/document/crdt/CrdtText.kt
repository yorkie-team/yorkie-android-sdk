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
    private val selectionMap = mutableMapOf<ActorID, Selection>()

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
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): Triple<Map<ActorID, TimeTicket>, List<TextChange>, RgaTreeSplitPosRange> {
        val textValue = if (value.isNotEmpty()) {
            TextValue(value).apply {
                attributes?.forEach { setAttribute(it.key, it.value, executedAt) }
            }
        } else {
            null
        }

        val (caretPos, latestCreatedAtMap, contentChanges) = rgaTreeSplit.edit(
            range,
            executedAt,
            textValue,
            latestCreatedAtMapByActor,
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
        return Triple(latestCreatedAtMap, changes, caretPos to caretPos)
    }

    private fun selectPrev(range: RgaTreeSplitPosRange, executedAt: TimeTicket): TextChange? {
        val prevSelection = selectionMap[executedAt.actorID]
        return if (prevSelection == null || prevSelection.executedAt < executedAt) {
            selectionMap[executedAt.actorID] = Selection(range.first, range.second, executedAt)
            val (from, to) = rgaTreeSplit.findIndexesFromRange(range)
            TextChange(TextChangeType.Selection, executedAt.actorID, from, to)
        } else {
            null
        }
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
    ): List<TextChange> {
        // 1. Split nodes with from and to.
        val toRight = rgaTreeSplit.findNodeWithSplit(range.second, executedAt).second
        val fromRight = rgaTreeSplit.findNodeWithSplit(range.first, executedAt).second

        // 2. Style nodes between from and to.
        return rgaTreeSplit.findBetween(fromRight, toRight)
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
    }

    /**
     * Stores that the given [range] has been selected.
     */
    fun select(range: RgaTreeSplitPosRange, executedAt: TimeTicket): TextChange? {
        return selectPrev(range, executedAt)
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
