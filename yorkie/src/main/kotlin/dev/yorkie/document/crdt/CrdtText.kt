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
    override var createdAt: TimeTicket,
    override var movedAt: TimeTicket? = null,
    override var removedAt: TimeTicket? = null,
) : CrdtElement(), GCCrdtElement {

    override val gcPairs: List<GCPair<*>>
        get() = buildList {
            // Only reached when a root is built from a snapshot, where
            // docSize.live counted visible nodes only. Tombstoned nodes (and
            // the attribute tombstones inside them) were never part of live,
            // so their pairs carry gcOnlySize. Attribute tombstones of
            // visible nodes ARE counted in live (getDataSize does not skip
            // them), so their pairs use the normal live -> gc accounting.
            rgaTreeSplit.forEach { node ->
                if (node.removedAt != null) {
                    add(GCPair(rgaTreeSplit, node, gcOnlySize = node.dataSize))
                }
                node.value.gcPairs.forEach { pair ->
                    add(
                        if (node.removedAt != null) {
                            pair.copy(
                                gcOnlySize = pair.child.dataSize,
                            )
                        } else {
                            pair
                        },
                    )
                }
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
     * Returns [TextEditResult] including [TextEditResult.removedValues] for undo support.
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

        val editResult = rgaTreeSplit.edit(
            range,
            executedAt,
            textValue,
            versionVector,
        )
        val (caretPos, contentChanges, gcPairs, dataSize, removedValues, removedSpans) = editResult

        val changes = toTextChanges(contentChanges).toMutableList()

        if (value.isNotEmpty() && attributes != null) {
            changes[changes.lastIndex] = changes.last().copy(attributes = attributes)
        }
        return TextEditResult(
            changes,
            caretPos to caretPos,
            gcPairs,
            dataSize,
            removedValues,
            removedSpans,
        )
    }

    /**
     * Re-establishes removed characters under their original identities
     * (identity-preserving undo of a deletion). Delegates to
     * [RgaTreeSplit.restore].
     */
    fun restore(
        spans: List<RestoreSpan<TextValue>>,
        executedAt: TimeTicket,
        fallbackAnchor: RgaTreeSplitPos? = null,
    ): TextRestoreResult {
        val result = rgaTreeSplit.restore(spans, executedAt, fallbackAnchor)
        return TextRestoreResult(
            result.untombstoned,
            result.recreated,
            toTextChanges(result.changes),
            result.liveDiff,
            result.pendingGcPairs,
        )
    }

    /**
     * Re-deletes previously restored characters (redo). Delegates to
     * [RgaTreeSplit.retombstone].
     */
    fun retombstone(
        spans: List<RestoreSpan<TextValue>>,
        executedAt: TimeTicket,
    ): TextRetombstoneResult {
        val result = rgaTreeSplit.retombstone(spans, executedAt)
        return TextRetombstoneResult(result.gcPairs, toTextChanges(result.changes), result.dataSize)
    }

    /**
     * Wraps raw [RgaTreeSplit.ContentChange]s into [TextChange]s, mirroring
     * the mapping [edit] has always used.
     */
    private fun toTextChanges(changes: List<RgaTreeSplit.ContentChange>): List<TextChange> {
        return changes.map {
            TextChange(
                TextChangeType.Content,
                it.actorID,
                it.from,
                it.to,
                it.content,
            )
        }
    }

    /**
     * Returns the integer index of the given [pos].
     */
    internal fun posToIndex(pos: RgaTreeSplitPos, preferToLeft: Boolean): Int =
        rgaTreeSplit.posToIndex(pos, preferToLeft)

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
                versionVector.get(actorID) ?: 0L
            } ?: MAX_LAMPORT

            node.takeIf {
                it.canStyle(executedAt, clientLamportAtChange)
            }
        }

        // Widened to GCPair<*>: drained pending pairs below are
        // GCPair<RgaTreeSplitNode<TextValue>>, a different type parameter
        // than the GCPair<RhtNode> attribute pairs added by this loop.
        val gcPairs = mutableListOf<GCPair<*>>()
        val prevAttributes = mutableMapOf<String, String>()
        val newAttributeKeys = mutableListOf<String>()
        var capturedPrev = false
        val changes = toBeStyleds
            .filterNot { it.isRemoved }
            .map { node ->
                val (fromIndex, toIndex) = rgaTreeSplit.findIndexesFromRange(node.createPosRange())
                if (!capturedPrev) {
                    val attrs = node.value.getAttrs()
                    for ((key, _) in attributes) {
                        if (attrs.has(key)) {
                            prevAttributes[key] = attrs[key]!!
                        } else {
                            newAttributeKeys.add(key)
                        }
                    }
                    capturedPrev = true
                }
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
        // A style operation's boundary splits (step 1) can land inside an
        // already-tombstoned node and buffer a born-dead piece; drain it so
        // it is not left unregistered for GC.
        gcPairs.addAll(rgaTreeSplit.drainPendingGcPairs())

        return TextStyleResult(changes, gcPairs, diff, prevAttributes, newAttributeKeys)
    }

    /**
     * Removes style attributes in [attributesToRemove] from nodes in [range].
     * Returns [TextStyleResult] with previous values of removed attributes for reverse op construction.
     */
    @SuppressLint("VisibleForTests")
    fun removeStyle(
        range: RgaTreeSplitPosRange,
        attributesToRemove: List<String>,
        executedAt: TimeTicket,
        versionVector: VersionVector? = null,
    ): TextStyleResult {
        var diff = DataSize(data = 0, meta = 0)

        val (_, toRight, diffTo) = rgaTreeSplit.findNodeWithSplit(range.second, executedAt)
        val (_, fromRight, diffFrom) = rgaTreeSplit.findNodeWithSplit(range.first, executedAt)

        diff = addDataSizes(diff, diffTo, diffFrom)

        val nodes = rgaTreeSplit.findBetween(fromRight, toRight)
        val toBeStyleds = nodes.mapNotNull { node ->
            val actorID = node.createdAt.actorID
            val clientLamportAtChange = versionVector?.let {
                versionVector.get(actorID) ?: 0L
            } ?: MAX_LAMPORT

            node.takeIf { it.canStyle(executedAt, clientLamportAtChange) }
        }

        // Widened to GCPair<*>: drained pending pairs below are
        // GCPair<RgaTreeSplitNode<TextValue>>, a different type parameter
        // than the GCPair<RhtNode> attribute pairs added by this loop.
        val gcPairs = mutableListOf<GCPair<*>>()
        val prevAttributes = mutableMapOf<String, String>()
        var capturedPrev = false
        val changes = toBeStyleds
            .filterNot { it.isRemoved }
            .map { node ->
                val (fromIndex, toIndex) = rgaTreeSplit.findIndexesFromRange(node.createPosRange())
                if (!capturedPrev) {
                    val attrs = node.value.getAttrs()
                    for (key in attributesToRemove) {
                        if (attrs.has(key)) {
                            prevAttributes[key] = attrs[key]!!
                        }
                    }
                    capturedPrev = true
                }
                for (key in attributesToRemove) {
                    val removedNodes = node.value.getAttrs().remove(key, executedAt)
                    for (rhtNode in removedNodes) {
                        gcPairs.add(GCPair(node.value, rhtNode))
                        diff = addDataSizes(diff, rhtNode.dataSize)
                    }
                }
                TextChange(
                    TextChangeType.Style,
                    executedAt.actorID,
                    fromIndex,
                    toIndex,
                    null,
                    emptyMap(),
                )
            }
        // A remove-style operation's boundary splits (step 1) can land inside
        // an already-tombstoned node and buffer a born-dead piece; drain it
        // so it is not left unregistered for GC.
        gcPairs.addAll(rgaTreeSplit.drainPendingGcPairs())

        return TextStyleResult(changes, gcPairs, diff, prevAttributes)
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
