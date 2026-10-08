package dev.yorkie.document.crdt

import android.annotation.SuppressLint
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MAX_LAMPORT
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
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
            // docSize.live counted visible nodes only. Tombstoned nodes were
            // never part of live, so their pairs carry gcOnlySize.
            //
            // node.value.gcPairs (TextValue.getDataSize skips removed
            // attributes) never covers a node's OWN removed attributes
            // regardless of whether the node itself is live or tombstoned,
            // so it is registered for EVERY node, not only live ones (the
            // old single-outer-pair-for-removed-nodes shortcut relied on
            // getDataSize summing ALL attributes, including removed ones,
            // which is no longer true). No double-count: the outer pair
            // below covers content + still-live
            // attribute bytes only; node.value.gcPairs covers exactly the
            // removed attributes, a disjoint set.
            rgaTreeSplit.forEach { node ->
                if (node.removedAt != null) {
                    add(GCPair(rgaTreeSplit, node, gcOnlySize = node.dataSize))
                }
                node.value.gcPairs.forEach { pair -> add(pair) }
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
            // A boundary split inside rgaTreeSplit.edit() may have copied
            // already-removed attributes onto the new piece; those pairs are
            // buffered separately because they are GCPair<RhtNode>, not
            // GCPair<RgaTreeSplitNode<TextValue>>.
            gcPairs + rgaTreeSplit.drainPendingAttributeGcPairs(),
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
        // A recreated node's subSequence-copied attribute tombstones (see
        // TextValue.gcPairs, same pattern as CrdtText.gcPairs's own live-node
        // case) must also be registered, or their bytes are never reachable
        // by any future GC pass (F13). subSequence deliberately preserves
        // their exact original state for LWW arbitration, so this is the
        // one place that can harvest them without touching subSequence.
        val attributeGcPairs = result.recreated.flatMap { it.value.gcPairs }
        return TextRestoreResult(
            result.untombstoned,
            result.recreated,
            toTextChanges(result.changes),
            result.liveDiff,
            // isolateRange's un-tombstone branch can also split a piece, so
            // drain its copied attribute tombstones too.
            result.pendingGcPairs + attributeGcPairs + rgaTreeSplit.drainPendingAttributeGcPairs(),
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
        return TextRetombstoneResult(
            // isolateRange splits the live piece being re-tombstoned, so
            // drain its copied attribute tombstones too.
            result.gcPairs + rgaTreeSplit.drainPendingAttributeGcPairs(),
            toTextChanges(result.changes),
            result.dataSize,
        )
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
                (it.value as? TextValue)?.attributes,
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
        // DocSize: accAttrWrite folds each RhtWrite's
        // install/supersede/revive into live or gc depending on whether the
        // node it landed on is still live (see GC.kt). `diff` (the two
        // boundary splits above) is always live-bound and is folded in once,
        // below, after the loop.
        var size = DocSize(live = DataSize(0, 0), gc = DataSize(0, 0))
        val changes = mutableListOf<TextChange>()
        toBeStyleds.forEach { node ->
            // canStyle (unchanged this commit) can admit a node whose
            // removal this write is newer than; such a node is not part of
            // the rendered text, so it reports no change but its bytes
            // still move through the ledger — into gc, not live.
            val nodeIsLive = !node.isRemoved
            if (nodeIsLive && !capturedPrev) {
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
            attributes.forEach { (key, value) ->
                val write = node.value.setAttribute(key, value, executedAt)
                size = accAttrWrite(write, node.value, nodeIsLive, gcPairs, size)
            }
            if (nodeIsLive) {
                val (fromIndex, toIndex) = rgaTreeSplit.findIndexesFromRange(node.createPosRange())
                changes.add(
                    TextChange(
                        TextChangeType.Style,
                        executedAt.actorID,
                        fromIndex,
                        toIndex,
                        null,
                        attributes,
                    ),
                )
            }
        }
        // A style operation's boundary splits (step 1) can land inside an
        // already-tombstoned node and buffer a born-dead piece, and/or copy a
        // removed attribute's tombstone onto the new piece; drain both
        // buffers via the shared helper so this success path and the F11
        // catch-recovery path (StyleOperation) cannot drift apart. Mirrors
        // JS SDK `text.ts:509` (e0609c7a #1368), which drains the one shared
        // pendingGCPairs.
        gcPairs.addAll(rgaTreeSplit.drainAllPendingGcPairs())

        return TextStyleResult(
            changes,
            gcPairs,
            DocSize(live = addDataSizes(diff, size.live), gc = size.gc),
            prevAttributes,
            newAttributeKeys,
        )
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
        val changes = mutableListOf<TextChange>()
        toBeStyleds.forEach { node ->
            // canStyle (unchanged this commit) can admit a node whose
            // removal this write is newer than; such a node is not part of
            // the rendered text, so it reports no change but the tombstone
            // this mints is still registered for GC below.
            val nodeIsLive = !node.isRemoved
            if (nodeIsLive && !capturedPrev) {
                val attrs = node.value.getAttrs()
                for (key in attributesToRemove) {
                    if (attrs.has(key)) {
                        prevAttributes[key] = attrs[key]!!
                    }
                }
                capturedPrev = true
            }
            for (key in attributesToRemove) {
                // The node holding the attribute may itself be a tombstone
                // (canStyle admits one) — attrGcPair's third question.
                var wasLive = node.value.getAttrs().has(key)
                node.value.getAttrs().remove(key, executedAt).forEach { rhtNode ->
                    gcPairs.add(attrGcPair(node.value, rhtNode, wasLive, nodeIsLive))
                    // Only the node replacing the live value takes a size
                    // out of live; a second one in the same call is the
                    // tombstone it superseded, already gc.
                    wasLive = false
                }
            }
            if (nodeIsLive) {
                val (fromIndex, toIndex) = rgaTreeSplit.findIndexesFromRange(node.createPosRange())
                changes.add(
                    TextChange(
                        TextChangeType.Style,
                        executedAt.actorID,
                        fromIndex,
                        toIndex,
                        null,
                        emptyMap(),
                    ),
                )
            }
        }
        // A remove-style operation's boundary splits (step 1) can land inside
        // an already-tombstoned node and buffer a born-dead piece, and/or
        // copy a removed attribute's tombstone onto the new piece; drain both
        // buffers via the shared helper so this success path and the F11
        // catch-recovery path (StyleOperation) cannot drift apart. Mirrors
        // JS SDK `text.ts:617` (e0609c7a #1368), which drains the one shared
        // pendingGCPairs.
        gcPairs.addAll(rgaTreeSplit.drainAllPendingGcPairs())

        // removeStyle's attribute accounting moves entirely through the
        // attrGcPair-registered gcPairs above (CrdtRoot.registerGCPair moves
        // the bytes live -> gc when they are registered); diff here is only
        // the two boundary splits, always live-bound.
        return TextStyleResult(
            changes,
            gcPairs,
            DocSize(live = diff, gc = DataSize(0, 0)),
            prevAttributes,
        )
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
