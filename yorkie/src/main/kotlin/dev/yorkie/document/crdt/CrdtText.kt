package dev.yorkie.document.crdt

import android.annotation.SuppressLint
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MAX_LAMPORT
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.SplayTreeSet
import dev.yorkie.util.addDataSizes
import java.util.TreeMap

/**
 * [CrdtText] is a custom CRDT data type to represent the contents of text editors.
 */
internal data class CrdtText(
    val rgaTreeSplit: RgaTreeSplit<TextValue>,
    override val createdAt: TimeTicket,
    override var movedAt: TimeTicket? = null,
    override var removedAt: TimeTicket? = null,
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
        versionVector: VersionVector? = null,
    ): TextEditResult {
        val textValue = if (value.isNotEmpty()) {
            TextValue(value).apply {
                attributes?.forEach { setAttribute(it.key, it.value, executedAt) }
            }
        } else {
            null
        }

        val (caretPos, contentChanges, gcPairs, dataSize) = rgaTreeSplit.edit(
            range,
            executedAt,
            textValue,
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
        return TextEditResult(changes, caretPos to caretPos, gcPairs, dataSize)
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
        versionVector: VersionVector? = null,
    ): TextStyleResult {
        var diff = DataSize(
            data = 0,
            meta = 0,
        )

        // 1. Split nodes with from and to.
        val (_, toRight, diffTo) = rgaTreeSplit.findNodeWithSplit(range.second, executedAt)
        val (_, fromRight, diffFrom) = rgaTreeSplit.findNodeWithSplit(range.first, executedAt)

        diff = addDataSizes(diff, diffTo, diffFrom)

        // 2. Style nodes between from and to.
        val nodes = rgaTreeSplit.findBetween(fromRight, toRight)
        val toBeStyleds = nodes.mapNotNull { node ->
            val actorID = node.createdAt.actorID
            val clientLamportAtChange = versionVector?.let {
                versionVector.get(actorID.value) ?: 0L
            } ?: MAX_LAMPORT

            node.takeIf {
                it.canStyle(executedAt, clientLamportAtChange)
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

                    val curr = node.value.getAttrs().getNodeMapByKey()[it.key]
                    if (curr != null) {
                        diff = addDataSizes(diff, curr.dataSize)
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

        return TextStyleResult(changes, gcPairs, diff)
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
        return copy(
            rgaTreeSplit = rgaTreeSplit.deepCopy(),
        )
    }

    override fun getDataSize(): DataSize {
        var data = 0
        var meta = 0

        for (node in rgaTreeSplit) {
            if (node.isRemoved) {
                continue
            }

            val dataSize = node.dataSize
            data += dataSize.data
            meta += dataSize.meta
        }

        return DataSize(
            data = data,
            meta = meta + getMetaUsage(),
        )
    }

    override fun toString(): String {
        return rgaTreeSplit.toString()
    }
}
