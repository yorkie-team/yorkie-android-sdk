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
        range: RgaTreeSplitNodeRange,
        value: String,
        executedAt: TimeTicket,
        attributes: Map<String, String>? = null,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): Pair<Map<ActorID, TimeTicket>, List<TextChange>> {
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
        selectPrev(RgaTreeSplitNodeRange(caretPos, caretPos), executedAt)?.let { changes.add(it) }
        return latestCreatedAtMap to changes
    }

    private fun selectPrev(range: RgaTreeSplitNodeRange, executedAt: TimeTicket): TextChange? {
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
        range: RgaTreeSplitNodeRange,
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
                val (fromIndex, toIndex) = rgaTreeSplit.findIndexesFromRange(node.createRange())
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
    fun select(range: RgaTreeSplitNodeRange, executedAt: TimeTicket): TextChange? {
        return selectPrev(range, executedAt)
    }

    /**
     * Returns a pair of [RgaTreeSplitNodePos] of the given integer offsets.
     */
    fun createRange(fromIndex: Int, toIndex: Int): RgaTreeSplitNodeRange {
        val fromPos = rgaTreeSplit.findNodePos(fromIndex)
        return if (fromIndex == toIndex) {
            RgaTreeSplitNodeRange(fromPos, fromPos)
        } else {
            RgaTreeSplitNodeRange(fromPos, rgaTreeSplit.findNodePos(toIndex))
        }
    }

    override fun deleteRemovedNodesBefore(executedAt: TimeTicket): Int {
        return rgaTreeSplit.deleteTextNodesWithGarbage(executedAt)
    }

    override fun deepCopy(): CrdtElement {
        return copy(rgaTreeSplit = rgaTreeSplit.deepCopy())
    }

    override fun toString(): String {
        return rgaTreeSplit.toString()
    }
}
