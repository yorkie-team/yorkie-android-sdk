package dev.yorkie.document.crdt

import android.annotation.SuppressLint
import dev.yorkie.document.json.ensureNoLoneSurrogate
import dev.yorkie.document.json.ensureUtf16Boundary
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import dev.yorkie.util.SplayTreeSet
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize
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
        val toBeStyleds = nodes.filter { it.canStyle(versionVector) }

        // Widened to GCPair<*>: drained pending pairs below are
        // GCPair<RgaTreeSplitNode<TextValue>>, a different type parameter
        // than the GCPair<RhtNode> attribute pairs added by this loop.
        val gcPairs = mutableListOf<GCPair<*>>()
        val prevAttributes = mutableMapOf<String, String>()
        val newAttributeKeys = mutableListOf<String>()
        var capturedPrev = false
        // The reverse operation restores what the VISIBLE text held, so the
        // prior values come from the first LIVE node in the range. canStyle
        // now admits tombstones, and the first node in the range can be one
        // — capturing from it made an undo write an attribute onto text
        // that never carried it, out of a run the user had already
        // deleted. The fallback to the first node keeps an all-tombstone
        // range undoable. Mirrors JS SDK `text.ts:461-467` (e0609c7a #1368).
        val captureFrom = toBeStyleds.firstOrNull { !it.isRemoved } ?: toBeStyleds.firstOrNull()
        // DocSize: accAttrWrite folds each RhtWrite's
        // install/supersede/revive into live or gc depending on whether the
        // node it landed on is still live (see GC.kt). `diff` (the two
        // boundary splits above) is always live-bound and is folded in once,
        // below, after the loop.
        var size = DocSize(live = DataSize(0, 0), gc = DataSize(0, 0))
        val changes = mutableListOf<TextChange>()
        toBeStyleds.forEach { node ->
            // canStyle can admit a node removed CONCURRENTLY with this
            // style; such a node is not part of the rendered text, so it
            // reports no change but its bytes still move through the
            // ledger — into gc, not live.
            val nodeIsLive = !node.isRemoved
            if (!capturedPrev && node === captureFrom) {
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
        // #1392: a removeStyle landing on a node that is itself a tombstone
        // drops its attribute's value out of gc, not live — the text's own
        // canStyle admits such a node, but it was never counted into live.
        var gcDiff = DataSize(data = 0, meta = 0)

        val (_, toRight, diffTo) = rgaTreeSplit.findNodeWithSplit(range.second, executedAt)
        val (_, fromRight, diffFrom) = rgaTreeSplit.findNodeWithSplit(range.first, executedAt)

        diff = addDataSizes(diff, diffTo, diffFrom)

        val nodes = rgaTreeSplit.findBetween(fromRight, toRight)
        val toBeStyleds = nodes.filter { it.canStyle(versionVector) }

        // Widened to GCPair<*>: drained pending pairs below are
        // GCPair<RgaTreeSplitNode<TextValue>>, a different type parameter
        // than the GCPair<RhtNode> attribute pairs added by this loop.
        val gcPairs = mutableListOf<GCPair<*>>()
        val prevAttributes = mutableMapOf<String, String>()
        var capturedPrev = false
        // See style(): the reverse operation restores what the VISIBLE text
        // held, so the prior values come from the first LIVE node in the
        // range, falling back to the first node for an all-tombstone range.
        // Mirrors JS SDK `text.ts:551-557` (e0609c7a #1368).
        val captureFrom = toBeStyleds.firstOrNull { !it.isRemoved } ?: toBeStyleds.firstOrNull()
        val changes = mutableListOf<TextChange>()
        toBeStyleds.forEach { node ->
            // canStyle can admit a node removed CONCURRENTLY with this
            // change; such a node is not part of the rendered text, so it
            // reports no change but the tombstone this mints is still
            // registered for GC below.
            val nodeIsLive = !node.isRemoved
            if (!capturedPrev && node === captureFrom) {
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
                val (gcNodes, valueDropped) = node.value.getAttrs().remove(key, executedAt)
                // The tombstone is not charged for its value (#1392); those
                // bytes leave whichever side was holding them. See
                // attrGcPair for the same live/gc split.
                if (nodeIsLive) {
                    diff = subDataSize(diff, valueDropped)
                } else {
                    gcDiff = subDataSize(gcDiff, valueDropped)
                }
                gcNodes.forEach { rhtNode ->
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

        // Most of removeStyle's attribute accounting moves through the
        // attrGcPair-registered gcPairs above (CrdtRoot.registerGCPair moves
        // the TOMBSTONE's own, now key-only, size live -> gc when it is
        // registered) plus the two boundary splits (live-bound, folded into
        // `diff` up front). The dropped VALUE bytes are a separate leg
        // (#1392): a tombstone is never charged for its value, so
        // registerGCPair's node-dataSize-based move does not reach them —
        // `diff`/`gcDiff` carry that subtraction out of whichever side
        // (live for a live node, gc for a node that is itself a tombstone)
        // was still holding them.
        return TextStyleResult(
            changes,
            gcPairs,
            DocSize(live = diff, gc = gcDiff),
            prevAttributes,
        )
    }

    /**
     * Returns a pair of [RgaTreeSplitPos] of the given integer offsets.
     * Unchecked: an index inside a UTF-16 surrogate pair resolves to
     * whichever position [RgaTreeSplit.indexToPos] gives it. Remote
     * operations, undo/redo and selection conversions all read a position
     * through this function and must never throw; [createRange] is the
     * checked counterpart local edits/styles go through instead.
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
     * Returns the position range of the given index range for a LOCAL edit
     * or style. Unlike [indexRangeToPosRange], it throws
     * [dev.yorkie.util.YorkieException] when an index splits a UTF-16
     * surrogate pair.
     *
     * [content] is given for an edit and omitted for a style. It is also
     * checked for a lone surrogate: storing one already diverges across SDKs
     * (Go turns it into U+FFFD, this SDK keeps the raw code unit), and the
     * lone half then pairs with whatever code unit it is stored next to,
     * turning the index at that seam into one [validateUtf16Boundary] would
     * refuse for as long as the text lives. A peer running an SDK without
     * this check can still send such content over the wire — this is a
     * local-edit contract, not a trust boundary.
     *
     * Ported from yorkie-js-sdk `6932da6a` (#1447) `CRDTText.createRange`.
     */
    fun createRange(
        fromIndex: Int,
        toIndex: Int,
        content: String? = null,
    ): RgaTreeSplitPosRange {
        if (!content.isNullOrEmpty()) {
            ensureNoLoneSurrogate(content)
        }
        val range = indexRangeToPosRange(fromIndex, toIndex)
        validateUtf16Boundary(range.first)
        if (fromIndex != toIndex) {
            validateUtf16Boundary(range.second)
        }
        return range
    }

    /**
     * Throws when [pos] splits a surrogate pair. At either end of the node
     * it resolves to, this reads the nearest neighbouring node that still
     * contributes content: a local split can leave one half of a pair in
     * this node and the other half in the node it was split from, and
     * [RgaTreeSplit.indexToPos] always resolves a seam to the node on its
     * LEFT, so `offset == content.length` is the side an ordinary index
     * normally reaches.
     *
     * Ported from yorkie-js-sdk `6932da6a` (#1447)
     * `CRDTText.validateUTF16Boundary`.
     */
    private fun validateUtf16Boundary(pos: RgaTreeSplitPos) {
        val node = rgaTreeSplit.findNode(pos.id)
        val offset = pos.relativeOffSet
        val content = indexedContent(node)

        var before = content.getOrNull(offset - 1)
        var after = content.getOrNull(offset)
        if (offset == 0) {
            before = neighborContent(node) { it.prev }.lastOrNull()
        }
        if (offset == content.length) {
            after = neighborContent(node) { it.next }.firstOrNull()
        }
        ensureUtf16Boundary(before, after)
    }

    /**
     * Returns the text [node] contributes to the index: empty for the head
     * sentinel (which carries no real [TextValue] — its [RgaTreeSplitNode.value]
     * is an unrelated placeholder object no [T] actually owns) and for a
     * tombstone, whose text the index no longer counts.
     */
    private fun indexedContent(node: RgaTreeSplitNode<TextValue>): String {
        return if (node.isRemoved || node === rgaTreeSplit.head) "" else node.value.content
    }

    /**
     * Returns the content of the nearest node on the [step] side that still
     * contributes text, or an empty string when there is none.
     */
    private fun neighborContent(
        node: RgaTreeSplitNode<TextValue>,
        step: (RgaTreeSplitNode<TextValue>) -> RgaTreeSplitNode<TextValue>?,
    ): String {
        var current = step(node)
        while (current != null) {
            val content = indexedContent(current)
            if (content.isNotEmpty()) return content
            current = step(current)
        }
        return ""
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
