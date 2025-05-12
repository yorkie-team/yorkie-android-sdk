package dev.yorkie.document.crdt

import android.annotation.SuppressLint
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MAX_LAMPORT
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.SplayTreeSet
import java.util.TreeMap

/**
 * [CrdtText] is a custom CRDT data type to represent the contents of text editors.
 */
internal data class CrdtText(
    val rgaTreeSplit: RgaTreeSplit<TextValue>,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtElement(), GCCrdtElement {

    override val gcPairs: List<GCPair<*>>
        get() = buildList {
            rgaTreeSplit.forEach { node ->
                if (node.removedAt != null) {
                    add(GCPair(rgaTreeSplit, node))
                }
                addAll(node.value.gcPairs)
            }
        }

    val values: List<TextWithAttributes>
        get() = rgaTreeSplit.filterNot {
            it.isRemoved
        }.map {
            TextWithAttributes(it.value.content to it.value.attributes)
        }

    val length: Int
        get() = rgaTreeSplit.length

    val treeByIndex: SplayTreeSet<RgaTreeSplitNode<TextValue>>
        get() = rgaTreeSplit.treeByIndex

    val treeByID: TreeMap<RgaTreeSplitNodeID, RgaTreeSplitNode<TextValue>>
        get() = rgaTreeSplit.treeByID

    /**
     * Edits the given [range] with the given [value] and [attributes].
     */
    fun edit(
        range: RgaTreeSplitPosRange,
        value: String,
        executedAt: TimeTicket,
        attributes: Map<String, String>? = null,
        maxCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
        versionVector: VersionVector? = null,
    ): TextEditResult {
        val textValue = if (value.isNotEmpty()) {
            TextValue(value).apply {
                attributes?.forEach { setAttribute(it.key, it.value, executedAt) }
            }
        } else {
            null
        }

        val (caretPos, maxCreatedAtMap, contentChanges, gcPairs) = rgaTreeSplit.edit(
            range,
            executedAt,
            textValue,
            maxCreatedAtMapByActor,
            versionVector,
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
        return TextEditResult(maxCreatedAtMap, changes, caretPos to caretPos, gcPairs)
    }

    /**
     * Applies the style of the given [range].
     * 1. Split nodes with from and to.
     * 2. Style nodes between from and to.
     */
    @SuppressLint("VisibleForTests")
    fun style(
        range: RgaTreeSplitPosRange,
        attributes: Map<String, String>,
        executedAt: TimeTicket,
        maxCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
        versionVector: VersionVector? = null,
    ): TextStyleResult {
        // 1. Split nodes with from and to.
        val toRight = rgaTreeSplit.findNodeWithSplit(range.second, executedAt).second
        val fromRight = rgaTreeSplit.findNodeWithSplit(range.first, executedAt).second

        // 2. Style nodes between from and to.
        val nodes = rgaTreeSplit.findBetween(fromRight, toRight)
        val createdAtMapByActor = mutableMapOf<ActorID, TimeTicket>()
        val toBeStyleds = nodes.mapNotNull { node ->
            val actorID = node.createdAt.actorID
            var maxCreatedAt: TimeTicket? = null
            var clientLamportAtChange = 0L

            if (versionVector == null && maxCreatedAtMapByActor.isNullOrEmpty()) {
                clientLamportAtChange = MAX_LAMPORT
            } else if (versionVector != null && versionVector.size() > 0) {
                clientLamportAtChange = versionVector.get(actorID.value) ?: 0
            } else {
                maxCreatedAt = maxCreatedAtMapByActor?.get(actorID) ?: InitialTimeTicket
            }

            node.takeIf {
                it.canStyle(executedAt, maxCreatedAt, clientLamportAtChange)
            }?.also {
                val updatedMaxCreatedAt = createdAtMapByActor[actorID]
                val updatedCreatedAt = node.createdAt
                if (updatedMaxCreatedAt == null || updatedMaxCreatedAt < updatedCreatedAt) {
                    createdAtMapByActor[actorID] = updatedCreatedAt
                }
            }
        }

        val gcPairs = mutableListOf<GCPair<RhtNode>>()
        val changes = toBeStyleds
            .filterNot { it.isRemoved }
            .map { node ->
                val (fromIndex, toIndex) = rgaTreeSplit.findIndexesFromRange(node.createPosRange())
                attributes.forEach {
                    val prev = node.value.setAttribute(it.key, it.value, executedAt).prev
                    prev?.let {
                        gcPairs.add(GCPair(node.value, prev))
                    }
                }
                TextChange(
                    TextChangeType.Style,
                    executedAt.actorID,
                    fromIndex,
                    toIndex,
                    null,
                    attributes,
                )
            }

        return TextStyleResult(createdAtMapByActor, changes, gcPairs)
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

    override fun deepCopy(): CrdtElement {
        return copy(rgaTreeSplit = rgaTreeSplit.deepCopy())
    }

    override fun toString(): String {
        return rgaTreeSplit.toString()
    }
}
