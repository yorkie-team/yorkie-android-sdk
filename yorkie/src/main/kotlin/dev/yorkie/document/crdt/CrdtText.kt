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
) : CrdtElement() {
    private val selectionMap = mutableMapOf<ActorID, Selection>()

    private var onChangesHandler: ((List<TextChange>) -> Unit)? = null

    @Volatile
    private var remoteChangeLock: Boolean = false

    val removedNodesLength: Int
        get() = rgaTreeSplit.removedNodesLength

    val values
        get() = rgaTreeSplit.filterNot { it.isRemoved }
            .map { it.value.content to it.value.attributes }

    /**
     * Edits the given [range] with the given [content] and [attributes].
     */
    fun edit(
        range: RgaTreeSplitNodeRange,
        content: String,
        executedAt: TimeTicket,
        attributes: Map<String, String>? = null,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): Map<ActorID, TimeTicket> {
        val value = if (content.isNotEmpty()) {
            TextValue(content).apply {
                attributes?.forEach { setAttribute(it.key, it.value, executedAt) }
            }
        } else {
            null
        }

        val (caretPos, latestCreatedAtMap, changes) = rgaTreeSplit.edit(
            range,
            executedAt,
            value,
            latestCreatedAtMapByActor,
        )

        if (content.isNotEmpty() && attributes != null) {
            changes.last().attributes = attributes
        }
        selectPrev(RgaTreeSplitNodeRange(caretPos, caretPos), executedAt)?.let { changes.add(it) }
        handleChanges(changes)
        return latestCreatedAtMap
    }

    private fun selectPrev(range: RgaTreeSplitNodeRange, executedAt: TimeTicket): TextChange? {
        val prevSelection = selectionMap[executedAt.actorID] ?: run {
            selectionMap[executedAt.actorID] = Selection(range.first, range.second, executedAt)
            return null
        }
        return if (prevSelection.executedAt < executedAt) {
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
    ) {
        // 1. Split nodes with from and to.
        val toRight = rgaTreeSplit.findNodeWithSplit(range.second, executedAt).second
        val fromRight = rgaTreeSplit.findNodeWithSplit(range.first, executedAt).second

        // 2. Style nodes between from and to.
        val changes = rgaTreeSplit.findBetween(fromRight, toRight)
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

        handleChanges(changes)
    }

    /**
     * Stores that the given [range] has been selected.
     */
    fun select(range: RgaTreeSplitNodeRange, executedAt: TimeTicket) {
        if (remoteChangeLock) return

        val change = selectPrev(range, executedAt) ?: return
        handleChanges(listOf(change))
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

    fun deleteTextNodesWithGarbage(executedAt: TimeTicket): Int {
        return rgaTreeSplit.deleteTextNodesWithGarbage(executedAt)
    }

    override fun deepCopy(): CrdtElement {
        return copy(rgaTreeSplit = rgaTreeSplit.deepCopy())
    }

    /**
     * Registers a handler of onChanges event.
     */
    fun onChanges(handler: ((List<TextChange>) -> Unit)) {
        onChangesHandler = handler
    }

    private fun handleChanges(changes: List<TextChange>) {
        onChangesHandler ?: return
        remoteChangeLock = true
        onChangesHandler?.invoke(changes)
        remoteChangeLock = false
    }
}
