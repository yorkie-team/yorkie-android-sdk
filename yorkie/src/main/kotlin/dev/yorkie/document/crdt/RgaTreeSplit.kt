package dev.yorkie.document.crdt

import dev.yorkie.document.JsonSerializable
import dev.yorkie.document.RgaTreeSplitNodeIDStruct
import dev.yorkie.document.RgaTreeSplitPosStruct
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.document.time.VersionVector
import dev.yorkie.document.time.ticketKnown
import dev.yorkie.util.DataSize
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.SplayTreeSet
import dev.yorkie.util.YorkieException
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap

internal typealias RgaTreeSplitPosRange = Pair<RgaTreeSplitPos, RgaTreeSplitPos>

/**
 * [RestoreSpan] identifies a run of characters from a single original
 * insertion: the absolute-offset interval [[start], [end]) of the insertion
 * created at [createdAt]. [value] is a deep copy of the removed content,
 * carried so that purged nodes can be recreated (GC-safe).
 */
internal data class RestoreSpan<T : RgaTreeSplitValue<T>>(
    val createdAt: TimeTicket,
    val start: Int,
    val end: Int,
    val value: T,
)

/**
 * [RgaTreeSplit] is a block-based list with improved index-based lookup in RGA.
 * The difference from [RgaTreeList] is that it has data on a block basis to
 * reduce the size of CRDT metadata. When an edit occurs on a block,
 * the block is split.
 */
internal class RgaTreeSplit<T : RgaTreeSplitValue<T>> :
    Iterable<RgaTreeSplitNode<T>>,
    GCParent<RgaTreeSplitNode<T>> {
    @Suppress("UNCHECKED_CAST")
    val head = RgaTreeSplitNode(InitialNodeID, InitialNodeValue) as RgaTreeSplitNode<T>

    val treeByIndex = SplayTreeSet<RgaTreeSplitNode<T>> {
        if (it.isRemoved) 0 else it.contentLength
    }.apply { insert(head) }

    val treeByID = TreeMap<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>().apply {
        put(head.id, head)
    }

    val length
        get() = treeByIndex.length

    /**
     * Buffers GC pairs for nodes created already-tombstoned by splitting a
     * removed node. Such pieces inherit `removedAt` without ever passing
     * through [RgaTreeSplitNode.recordRemoval], so they would otherwise never
     * be registered for GC. Callers that split nodes ([edit], [CrdtText.style],
     * [CrdtText.removeStyle], [restore], [retombstone]) drain this buffer via
     * [drainPendingGcPairs] into their returned GC pairs.
     */
    private var pendingGcPairs = mutableListOf<GCPair<RgaTreeSplitNode<T>>>()

    /**
     * Returns the GC pairs buffered for born-tombstoned split pieces and
     * clears the buffer.
     */
    fun drainPendingGcPairs(): List<GCPair<RgaTreeSplitNode<T>>> {
        val pairs = pendingGcPairs
        pendingGcPairs = mutableListOf()
        return pairs
    }

    /**
     * Buffers the copied attribute-tombstone pairs a split registers for the
     * NEW piece ([RgaTreeSplitValue.gcPairs]) — a separate,
     * loosely-typed buffer from [pendingGcPairs] because those pairs are
     * `GCPair<RhtNode>`, not `GCPair<RgaTreeSplitNode<T>>`. Callers that split
     * nodes drain it via [drainPendingAttributeGcPairs] and fold it into
     * their own result alongside their node-level pairs (e.g.
     * [CrdtText.edit], [CrdtText.restore], [CrdtText.retombstone]).
     */
    private var pendingAttributeGcPairs = mutableListOf<GCPair<*>>()

    /**
     * Returns the GC pairs buffered for a split's copied attribute
     * tombstones and clears the buffer.
     */
    fun drainPendingAttributeGcPairs(): List<GCPair<*>> {
        val pairs = pendingAttributeGcPairs
        pendingAttributeGcPairs = mutableListOf()
        return pairs
    }

    /**
     * Drains BOTH [pendingGcPairs] and [pendingAttributeGcPairs] together.
     * A single boundary split can buffer into both buffers at once (a
     * born-dead node pair AND a copied attribute tombstone), so a caller
     * that recovers one after a throwing second [findNodeWithSplit] (F11)
     * must recover both or the attribute copy leaks. Shared by the success
     * paths ([CrdtText.style]/[CrdtText.removeStyle]) and every catch
     * handler that recovers a pre-throw buffer, so the two buffers cannot
     * drift apart again.
     */
    fun drainAllPendingGcPairs(): List<GCPair<*>> {
        return drainPendingGcPairs() + drainPendingAttributeGcPairs()
    }

    /**
     * The nodes whose ledger holds a cancel, the only ones [pruneLedgers] can
     * drop anything from. `null` until first needed -- a tree decoded from a
     * snapshot or deep-copied fills it with one scan ([scanCancelled]).
     */
    private var cancelled: MutableSet<RgaTreeSplitNode<T>>? = null

    private fun scanCancelled(): MutableSet<RgaTreeSplitNode<T>> {
        val set: MutableSet<RgaTreeSplitNode<T>> = Collections.newSetFromMap(IdentityHashMap())
        var node: RgaTreeSplitNode<T>? = head
        while (node != null) {
            if (node.hasCancels()) set.add(node)
            node = node.next
        }
        cancelled = set
        return set
    }

    /** Records that [node]'s ledger has a cancel. */
    private fun trackCancelled(node: RgaTreeSplitNode<T>) {
        (cancelled ?: scanCancelled()).add(node)
    }

    /** Whether any node's ledger holds a cancel. */
    fun hasCancelledRemovals(): Boolean = (cancelled ?: scanCancelled()).isNotEmpty()

    /**
     * Drops, from every tracked node's ledger, the removals whose cancels
     * [vector] covers (see [RgaTreeSplitNode.pruneStable]). Returns how many
     * nodes still hold a cancel.
     */
    fun pruneLedgers(vector: VersionVector): Int {
        val set = cancelled ?: scanCancelled()
        val iterator = set.iterator()
        while (iterator.hasNext()) {
            val node = iterator.next()
            node.pruneStable(vector)
            if (!node.hasCancels()) iterator.remove()
        }
        return set.size
    }

    /**
     * Does following stpes.
     * 1. Split nodes with the given [range].
     * 2. Delete between the given [range].
     * 3. Insert a new node.
     * 4. Add removed nodes.
     */
    fun edit(
        range: RgaTreeSplitPosRange,
        executedAt: TimeTicket,
        value: T?,
        versionVector: VersionVector?,
    ): RgaTreeSplitEditResult<T> {
        // 1. Split nodes.
        var diff = DataSize(
            data = 0,
            meta = 0,
        )
        val (toLeft, toRight, diffTo) = findNodeWithSplit(range.second, executedAt)
        val (fromLeft, fromRight, diffFrom) = findNodeWithSplit(range.first, executedAt)

        diff = addDataSizes(diff, diffTo, diffFrom)

        // 2. Delete between from and to.
        val nodesToDelete = findBetween(fromRight, toRight)
        val (changes, removedNodes, alreadyRemovedIDs) = deleteNodes(
            nodesToDelete,
            executedAt,
            versionVector,
        )
        val caretID = toRight?.id ?: toLeft.id
        var caretPos = RgaTreeSplitPos(caretID, 0)

        // 3. Insert a new node.
        value?.let {
            val index = posToIndex(fromLeft.createPosRange().second, true)
            val inserted = insertAfter(
                fromLeft,
                RgaTreeSplitNode(
                    RgaTreeSplitNodeID(executedAt, 0),
                    it,
                ),
            )
            diff = addDataSizes(diff, inserted.dataSize)

            if (changes.isNotEmpty() && changes.last().from == index) {
                changes[changes.lastIndex] = changes.last().copy(content = it.toString())
            } else {
                changes.add(
                    ContentChange(
                        executedAt.actorID,
                        index,
                        index,
                        value.toString(),
                    ),
                )
            }
            caretPos = RgaTreeSplitPos(inserted.id, inserted.contentLength)
        }

        // 4. Add removed nodes. Nodes that were already tombstoned (concurrent
        // LWW overwrite of an existing tombstone) keep their existing GC pair;
        // re-registering would toggle CrdtRoot.registerGCPair's pair off and
        // leak the node.
        val gcPairs = removedNodes.mapNotNull { (id, node) ->
            if (id in alreadyRemovedIDs) null else GCPair(this, node)
        }.toMutableList()
        gcPairs.addAll(drainPendingGcPairs())
        val removedValues = removedNodes.map { (_, node) -> node.value }
        // Capture split-invariant character identities so an identity-
        // preserving undo can restore this exact content later. value.deepCopy()
        // deep-copies the value so a later split of the tombstone cannot
        // mutate the captured content.
        //
        // Deliberately UNFILTERED by alreadyRemovedIDs (unlike gcPairs
        // above): a node only lands in alreadyRemovedIDs via canRemove()'s
        // LWW-won-concurrent-overwrite case, meaning THIS op legitimately
        // becomes the node's causal owner (editedAt is after the previous
        // removedAt) — so attributing it to this op's undo is correct. Only
        // the GC-pair bookkeeping is skipped there, to avoid double-toggling
        // an already-registered pair. Filtering removedSpans the same way
        // would silently drop legitimate undo content (matches JS SDK
        // rga_tree_split.ts's edit(), which does not filter here either).
        val removedSpans = removedNodes.map { (_, node) ->
            RestoreSpan(
                createdAt = node.createdAt,
                start = node.id.offset,
                end = node.id.offset + node.contentLength,
                value = node.value.deepCopy(),
            )
        }

        return RgaTreeSplitEditResult(caretPos, changes, gcPairs, diff, removedValues, removedSpans)
    }

    /**
     * Re-establishes the characters described by [spans] under their
     * ORIGINAL identities. For each span, per overlapping region:
     * - live piece exists -> skip (idempotent; another undo already restored it)
     * - tombstoned piece exists -> clear removedAt (un-tombstone)
     * - no piece exists (GC'd) -> recreate a node with the original ID
     *
     * The caller must, in order: (1) register every pair in
     * [RgaTreeSplitRestoreResult.pendingGcPairs] — these are fragments
     * [splitNode] buffered while isolating a target range out of a larger
     * tombstoned piece; (2) unregister GC pairs for
     * [RgaTreeSplitRestoreResult.untombstoned]. Registering first is required
     * for entries whose node was itself one of those split-born fragments (a
     * target isolated from the interior of a tombstone) — such a node was
     * never registered under its own id, so step (1) creates the entry that
     * step (2) then correctly walks from gc back to live; entries that remain
     * tombstoned (siblings of the restored target) simply stay registered.
     * Finally, the caller must accumulate
     * [RgaTreeSplitRestoreResult.liveDiff], which accounts the size of any
     * nodes recreated from scratch (the GC'd-away case) — [splitNode]'s
     * buffering does not cover this.
     *
     * [RgaTreeSplitRestoreResult.changes] describes the revived content as
     * insertions in ascending index order, so sequential application (e.g. by
     * editor bindings) keeps indices valid.
     */
    fun restore(
        spans: List<RestoreSpan<T>>,
        executedAt: TimeTicket,
        fallbackAnchor: RgaTreeSplitPos? = null,
    ): RgaTreeSplitRestoreResult<T> {
        val untombstoned = mutableListOf<RgaTreeSplitNode<T>>()
        val recreated = mutableListOf<RgaTreeSplitNode<T>>()
        var liveDiff = DataSize(data = 0, meta = 0)

        // The last node placed at the current cursor (un-tombstoned or
        // recreated), in document order across spans AND gaps within a
        // single restore call. When a recreated fragment has no surviving
        // same-insertion anchor, chaining after this keeps a multi-fragment
        // run in left-to-right order instead of each fragment prepending at
        // the same fixed fallback anchor — which would rebuild the run
        // reversed (port 270ffc66, JS SDK v0.7.14).
        var chainAnchor: RgaTreeSplitNode<T>? = null

        for (span in spans) {
            val pieces = findPiecesOverlapping(span.createdAt, span.start, span.end)

            var cursor = span.start
            var pieceIndex = 0
            while (cursor < span.end) {
                val piece = pieces.getOrNull(pieceIndex)
                val pieceStart = piece?.id?.offset ?: Int.MAX_VALUE
                val pieceEnd = if (piece != null) {
                    pieceStart + piece.contentLength
                } else {
                    Int.MAX_VALUE
                }

                if (piece != null && pieceStart <= cursor) {
                    // Covered by an existing piece.
                    val overlapEnd = minOf(pieceEnd, span.end)
                    if (piece.isRemoved) {
                        val (target, _) = isolateRange(piece, cursor, overlapEnd)
                        // An undo reverses its own actor's change, so the
                        // removal it cancels is the one uncancelled removal
                        // by that actor: one actor cannot remove a piece
                        // already removed in its view. No other removal
                        // holds it if there is none.
                        var live = false
                        val deletion = target.uncancelledRemovalBy(executedAt.actorID)
                        if (deletion != null) {
                            live = target.cancelRemoval(deletion, executedAt)
                            trackCancelled(target)
                        }
                        if (live) {
                            // Repair splay weights on the path to root (length 0 -> len).
                            treeByIndex.splay(target)
                            untombstoned.add(target)
                        }
                        chainAnchor = target
                    } else {
                        chainAnchor = piece
                    }
                    cursor = overlapEnd
                    if (overlapEnd >= pieceEnd) {
                        pieceIndex++
                    }
                } else {
                    // Gap: recreate [cursor, gapEnd) with its original ID.
                    val gapEnd = minOf(pieceStart, span.end)

                    @Suppress("UNCHECKED_CAST")
                    val value = span.value.subSequence(
                        cursor - span.start,
                        gapEnd - span.start,
                    ) as T
                    val newNode =
                        RgaTreeSplitNode(RgaTreeSplitNodeID(span.createdAt, cursor), value)
                    // The purged removal was known to every replica before
                    // the purge, so every change that can arrive later knows
                    // it. A stand-in removal by the same actor at lamport 0,
                    // which every such change's vector covers, keeps that
                    // answer for isRemovedInView, with this cancel. The pair
                    // is dropped once the cancel is known everywhere
                    // (pruneStable); until then snapshots carry it, as Go
                    // records the same.
                    val stable = TimeTicket(0L, 0u, executedAt.actorID)
                    newNode.recordRemoval(stable)
                    newNode.cancelRemoval(stable, executedAt)
                    trackCancelled(newNode)
                    liveDiff = addDataSizes(liveDiff, newNode.dataSize)
                    val (prev, anchorDiff) =
                        findRestoreAnchor(
                            span.createdAt,
                            cursor,
                            gapEnd,
                            executedAt,
                            fallbackAnchor,
                            chainAnchor,
                        )
                    liveDiff = addDataSizes(liveDiff, anchorDiff)
                    insertAfter(prev, newNode)
                    // Re-link the insertion chain around the recreated
                    // fragment (BLOCKER-2 / F3 / I1) in exactly the JS #1328
                    // (v0.7.18) shape: insertionPrev is the same-insertion
                    // piece covering cursor-1 (contiguous by construction),
                    // insertionNext the piece covering gapEnd. `delete()`'s
                    // purge-relink already pointed the surviving successor's
                    // insertionPrev straight across this gap; left
                    // un-repaired, that stale pointer makes
                    // findFloorNodePreferToLeft (used by every subsequent
                    // findNodeWithSplit) resolve a later boundary edit to the
                    // WRONG node with an out-of-range splitNode offset that
                    // JsonText.edit's IllegalArgumentException catch swallows
                    // silently. Never inherit `successor.insertionPrev`
                    // instead: when the purged run extends left of this span
                    // it is a NON-contiguous far-left piece and reproduces the
                    // same out-of-range split at the fragment's own start.
                    // Either link may point at a node that is physically LATER
                    // than its owner (rung (c) places this fragment behind the
                    // rightmost survivor); that is fine for every consumer —
                    // findFloorNodePreferToLeft/posToIndex key on "the
                    // same-insertion piece ending at my start", not on document
                    // order — and deepCopy resolves links in a second pass.
                    //
                    // JS v0.7.26 widens both lookups to the nearest SURVIVING
                    // piece of this insertion, not one whose range literally
                    // covers the boundary offset: `treeByID.floorEntry`
                    // (insPrev) / `ceilingEntry` (insNext), checked only for
                    // `hasSameCreatedAt`. A `findPieceCovering`-style
                    // range check would miss a predecessor/successor whose
                    // immediate neighbour was itself purged away.
                    if (cursor > 0) {
                        val prevKey = RgaTreeSplitNodeID(span.createdAt, cursor - 1)
                        val insPrevEntry = treeByID.floorEntry(prevKey)
                        if (insPrevEntry != null && insPrevEntry.key.hasSameCreatedAt(prevKey)) {
                            newNode.setInsertionPrev(insPrevEntry.value)
                        }
                    }
                    val nextKey = RgaTreeSplitNodeID(span.createdAt, gapEnd)
                    val insNextEntry = treeByID.ceilingEntry(nextKey)
                    if (insNextEntry != null && insNextEntry.key.hasSameCreatedAt(nextKey)) {
                        insNextEntry.value.setInsertionPrev(newNode)
                    }
                    recreated.add(newNode)
                    chainAnchor = newNode
                    cursor = gapEnd
                }
            }
        }

        val pendingGcPairs = drainPendingGcPairs()

        // Revived nodes are now live; report each as an insertion at its
        // final index. Ascending order keeps the indices valid when applied
        // in sequence (each earlier insertion is already present).
        val changes = (untombstoned + recreated)
            .map { node ->
                val (from, _) = findIndexesFromRange(node.createPosRange())
                ContentChange(executedAt.actorID, from, from, node.value.toString(), node.value)
            }
            .sortedBy { it.from }
            .toMutableList()

        return RgaTreeSplitRestoreResult(untombstoned, recreated, changes, liveDiff, pendingGcPairs)
    }

    /**
     * Re-deletes the characters described by [spans] (redo of an identity-
     * preserving undo). Only pieces live in the redo's view are affected: a
     * piece removed in that view, or purged, is skipped (idempotent), and a
     * piece removed here by a deletion the redo never saw records the redo
     * without being re-tombstoned ([versionVector] carries that view; `null`
     * or empty means local, matching [deleteNodes]).
     *
     * Returns GC pairs for the newly tombstoned nodes and the born-dead
     * remainders the splits left behind, the removed regions as deletions,
     * the metadata-size overhead from splitting the (live) pieces to isolate
     * the target range, and [RgaTreeSplitRetombstoneResult.recorded] — the
     * pieces the removal was actually recorded on, which the reverse has to
     * name. The caller must accumulate [RgaTreeSplitRetombstoneResult.dataSize]
     * before registering [RgaTreeSplitRetombstoneResult.gcPairs], mirroring
     * how a normal edit's boundary splits are accounted before its resulting
     * tombstones are registered. Indices are captured before each removal, so
     * applying the changes in emission order stays consistent.
     */
    fun retombstone(
        spans: List<RestoreSpan<T>>,
        executedAt: TimeTicket,
        versionVector: VersionVector? = null,
    ): RgaTreeSplitRetombstoneResult<T> {
        val gcPairs = mutableListOf<GCPair<RgaTreeSplitNode<T>>>()
        val changes = mutableListOf<ContentChange>()
        var diff = DataSize(data = 0, meta = 0)
        val hasView = versionVector != null && versionVector.size() != 0
        val recorded = mutableListOf<RestoreSpan<T>>()

        fun record(span: RestoreSpan<T>, target: RgaTreeSplitNode<T>) {
            val start = target.id.offset
            val end = start + target.contentLength

            @Suppress("UNCHECKED_CAST")
            val value = span.value.subSequence(start - span.start, end - span.start) as T
            recorded.add(RestoreSpan(span.createdAt, start, end, value))
        }

        for (span in spans) {
            val pieces = findPiecesOverlapping(span.createdAt, span.start, span.end)
            for (piece in pieces) {
                if (piece.isRemoved) {
                    // A piece live in the redo's view but removed here by a
                    // deletion the redo never saw still records the redo's
                    // deletion, so a later undo of that other deletion keeps
                    // it removed.
                    if (hasView && !piece.isRemovedInView(requireNotNull(versionVector))) {
                        val pieceStart = piece.id.offset
                        val pieceEnd = pieceStart + piece.contentLength
                        val (target, _) = isolateRange(
                            piece,
                            maxOf(pieceStart, span.start),
                            minOf(pieceEnd, span.end),
                        )
                        target.recordRemoval(executedAt)
                        record(span, target)
                    }
                    continue
                }
                // A piece live here but removed in the redo's view (by a
                // deletion whose undo the redo never saw) is left alone, as
                // the redoing replica left it: like a deletion, a redo
                // removes only what was live in its view.
                if (hasView && piece.isRemovedInView(requireNotNull(versionVector))) {
                    continue
                }

                val pieceStart = piece.id.offset
                val pieceEnd = pieceStart + piece.contentLength
                val (target, splitDiff) = isolateRange(
                    piece,
                    maxOf(pieceStart, span.start),
                    minOf(pieceEnd, span.end),
                )
                // `piece` was live, so the split overhead belongs to the live
                // bucket, same as a normal edit's boundary splits.
                diff = addDataSizes(diff, splitDiff)
                // Capture the visible range while `target` is still live.
                val (from, to) = findIndexesFromRange(target.createPosRange())
                target.recordRemoval(executedAt)
                record(span, target)
                treeByIndex.splay(target)
                gcPairs.add(GCPair(this, target))
                // Emit unconditionally, symmetric with restore()'s equivalent
                // list: every piece actually tombstoned here must produce a
                // matching opInfo, or the undo/redo chain can silently drop
                // an entry and a real local mutation never reaches sync (F15).
                changes.add(ContentChange(executedAt.actorID, from, to))
            }
        }

        // Isolating a range inside an already-tombstoned piece buffers
        // born-dead remainders, and a split books the attribute tombstones it
        // copies. Hand them to the caller with the rest: unregistered, they
        // would never be purged and their size would stay uncharged.
        gcPairs.addAll(drainPendingGcPairs())

        return RgaTreeSplitRetombstoneResult(gcPairs, changes, diff, recorded)
    }

    /**
     * Re-snapshots [spans] against the content they address as it stands now, returning an
     * equivalent span set (same insertion identities, same overall intervals) whose values
     * carry the CURRENT attributes. A span is captured when the edit it reverses runs, so
     * an attribute applied to the run afterward -- a peer's style, which `canStyle` admits
     * even on a tombstone -- is missing from it; a replica that purged the run would then
     * recreate it from the stale snapshot while a replica that still holds the tombstone
     * revives the live attributes, and the two diverge.
     *
     * A region covered by a surviving piece (live or tombstoned) is taken from that piece,
     * so a span crossing several pieces splits into one span per piece. A region with no
     * surviving piece keeps the original snapshot -- it is all this replica knows about it.
     * Read-only: it only reads pieces and builds payload [RestoreSpan]s via [CharSequence.subSequence]
     * (which deep-copies current attributes for [TextValue], the correctness crux) --
     * no [isolateRange]/[insertAfter] mutation, no attribute-tombstone booking. Ported from
     * JS `06f5587b` #1504 `RGATreeSplit.refreshSpans`.
     */
    fun refreshSpans(spans: List<RestoreSpan<T>>): List<RestoreSpan<T>> {
        val refreshed = mutableListOf<RestoreSpan<T>>()

        @Suppress("UNCHECKED_CAST")
        fun fromSnapshot(
            span: RestoreSpan<T>,
            start: Int,
            end: Int,
        ) {
            refreshed.add(
                RestoreSpan(
                    span.createdAt,
                    start,
                    end,
                    span.value.subSequence(start - span.start, end - span.start) as T,
                ),
            )
        }

        for (span in spans) {
            val pieces = findPiecesOverlapping(span.createdAt, span.start, span.end)
            var cursor = span.start
            for (piece in pieces) {
                val pieceStart = piece.id.offset
                val start = maxOf(pieceStart, cursor)
                val end = minOf(pieceStart + piece.contentLength, span.end)
                if (end <= start) continue
                if (start > cursor) {
                    fromSnapshot(span, cursor, start)
                }
                @Suppress("UNCHECKED_CAST")
                val value = piece.value.subSequence(start - pieceStart, end - pieceStart) as T
                refreshed.add(RestoreSpan(span.createdAt, start, end, value))
                cursor = end
            }
            if (cursor < span.end) {
                fromSnapshot(span, cursor, span.end)
            }
        }

        return refreshed
    }

    /**
     * Collects existing nodes (live or tombstoned) belonging to the insertion
     * [createdAt] that overlap the absolute-offset interval [[start], [end]),
     * in ascending offset order. Works by descending floorEntry probes over
     * [treeByID].
     */
    private fun findPiecesOverlapping(
        createdAt: TimeTicket,
        start: Int,
        end: Int,
    ): List<RgaTreeSplitNode<T>> {
        // An empty interval overlaps nothing. Without this, [start, start)
        // probes `start - 1`, matches the live piece covering it, and
        // retombstone's isolateRange(piece, k, k) tombstones that piece's
        // whole remainder (the second splitNode(node, 0) returns it unsplit).
        if (start >= end) return emptyList()
        val pieces = mutableListOf<RgaTreeSplitNode<T>>()
        var probe = end - 1

        while (probe >= 0) {
            val key = RgaTreeSplitNodeID(createdAt, probe)
            val entry = treeByID.floorEntry(key) ?: break
            if (!entry.key.hasSameCreatedAt(key)) break

            val node = entry.value
            val nodeStart = node.id.offset
            val nodeEnd = nodeStart + node.contentLength
            if (nodeEnd <= start) break
            if (nodeStart < end && nodeEnd > start) {
                pieces.add(node)
            }
            if (nodeStart <= start) break
            probe = nodeStart - 1
        }

        return pieces.reversed()
    }

    /**
     * Returns the node of insertion [createdAt] whose absolute-offset range
     * covers [offset], if present.
     */
    private fun findPieceCovering(createdAt: TimeTicket, offset: Int): RgaTreeSplitNode<T>? {
        val key = RgaTreeSplitNodeID(createdAt, offset)
        val entry = treeByID.floorEntry(key) ?: return null
        if (!entry.key.hasSameCreatedAt(key)) return null

        val node = entry.value
        val nodeStart = node.id.offset
        val nodeEnd = nodeStart + node.contentLength
        return node.takeIf { nodeStart <= offset && offset < nodeEnd }
    }

    /**
     * Returns the physical node to insert a recreated fragment
     * [[gapStart], [gapEnd]) of insertion [createdAt] AFTER.
     *
     * Resolution ladder — exactly the JS SDK `rga_tree_split.ts`'s
     * `findRestoreAnchor` ladder as of v0.7.26 (#1514, yorkie#2310), all
     * rules key on op-carried data + ID lookups only:
     *  (a) a piece covering [gapEnd] exists -> directly before it
     *      (originally-adjacent successor; exact original slot)
     *  (b) nearest surviving piece of the same insertion left of [gapStart]
     *      -> directly after it
     *  (c) nearest surviving piece of the same insertion at or after
     *      [gapEnd] -> directly before it. Anchoring before a FARTHER piece
     *      would place the gap after the pieces in between, and a deletion
     *      that later spans from the gap to one of them would run from its
     *      start to the end of the text.
     *  (d) [chainAnchor]: the previously placed fragment of this same
     *      restore call (document order) -> after it, so a purge that left
     *      NO surviving same-insertion piece (char-by-char typing makes
     *      every character its own insertion) is rebuilt left-to-right
     *      instead of each fragment prepending at the same fallback anchor
     *      and reversing the run. Ported from JS `270ffc66` (v0.7.14,
     *      wafflebase#629).
     *  (e) [fallbackAnchor], remapped through [refinePos] and then resolved
     *      via [findNodeWithSplit] inside a catch-all — exactly JS's
     *      `refinePos` + `findNodeWithSplit` + `catch {}`. The remap is
     *      load-bearing, not cosmetic: a JS peer's reverse op carries
     *      `fromPos = normalizePos(...)`, a HEAD-anchored position whose
     *      offset is the absolute live index, and a node-relative anchor can
     *      overshoot a piece whose right neighbours were purged. Either would
     *      trip [splitNode]'s bounds check (`IllegalArgumentException`) and
     *      wedge the receiving sync loop. Android's own undo path still
     *      re-resolves [fallbackAnchor] from reconciled integer offsets first
     *      (DEC-5), so for local undo this rung sees a fresh anchor.
     *  (f) [head] (deterministic last resort)
     *
     * Returns the anchor node together with the metadata-size overhead (if
     * any) that resolving rung (e) via [findNodeWithSplit] incurred, so the
     * caller can fold it into its own live-size accounting instead of
     * discarding it.
     */
    private fun findRestoreAnchor(
        createdAt: TimeTicket,
        gapStart: Int,
        gapEnd: Int,
        executedAt: TimeTicket,
        fallbackAnchor: RgaTreeSplitPos?,
        chainAnchor: RgaTreeSplitNode<T>?,
    ): Pair<RgaTreeSplitNode<T>, DataSize> {
        val zeroDiff = DataSize(data = 0, meta = 0)

        findPieceCovering(createdAt, gapEnd)?.let { successor ->
            return requireNotNull(successor.prev) to zeroDiff
        }

        if (gapStart > 0) {
            val key = RgaTreeSplitNodeID(createdAt, gapStart - 1)
            val entry = treeByID.floorEntry(key)
            if (entry != null && entry.key.hasSameCreatedAt(key)) {
                return entry.value to zeroDiff
            }
        }

        // (c) JS v0.7.26 (#1514, yorkie#2310): the NEAREST surviving piece of
        // the insertion at or after gapEnd, not the rightmost one — anchoring
        // before a later piece would place the gap after the pieces in
        // between, and a deletion that later spans from the gap to one of
        // them would run from its start to the end of the text. Replaces the
        // older rightmost-survivor rung, which disagreed with this JS/Go
        // v0.7.26 ladder on identical replayed input.
        val afterKey = RgaTreeSplitNodeID(createdAt, gapEnd)
        val after = treeByID.ceilingEntry(afterKey)
        if (after != null && after.key.hasSameCreatedAt(afterKey)) {
            return requireNotNull(after.value.prev) to zeroDiff
        }

        // (d) No surviving piece of this insertion anchors the fragment.
        // When the whole run was purged, every fragment lands here;
        // anchoring after the fragment placed just before it (document
        // order) keeps the run forward.
        chainAnchor?.let { return it to zeroDiff }

        if (fallbackAnchor != null) {
            try {
                val (node, _, diff) = findNodeWithSplit(refinePos(fallbackAnchor), executedAt)
                return node to diff
            } catch (e: RuntimeException) {
                // Anchor fully purged (NoSuchElementException from refinePos /
                // findFloorNodePreferToLeft) or still unresolvable
                // (IllegalArgumentException from splitNode). JS catches
                // everything here too — fall through to (f). Nothing above
                // mutates before it throws, so falling through is safe.
            }
        }

        logDebug(TAG, "restore anchor exhausted; falling back to head")
        return head to zeroDiff
    }

    /**
     * Remaps [pos] onto the current split chain — a port of JS
     * `rga_tree_split.ts`'s `refinePos`. Walks the physical `next` chain
     * (not the insertion chain), counting only live characters, until the
     * offset fits inside a node; snaps to the end of the last node when it
     * runs out. Throws [NoSuchElementException] when no node of the anchor's
     * insertion survives at all.
     *
     * Example: `["12345"](1:2:0)`, pos `(1:2:0, rel=5)`; after splits
     * `["1"](1:2:0) - ["23"](1:2:1) - ["45"](1:2:3)` this yields
     * `(1:2:3, rel=2)`. With a head-anchored pos `(head, rel=n)` (what JS's
     * `normalizePos` records on every reverse op) it walks from head past
     * `n` live characters.
     */
    private fun refinePos(pos: RgaTreeSplitPos): RgaTreeSplitPos {
        var node = findFloorNode(pos.id)
            ?: throw NoSuchElementException("the node of the given id should be found: ${pos.id}")
        var offsetInPart = pos.relativeOffSet
        var partLen = node.contentLength
        while (offsetInPart > partLen) {
            offsetInPart -= partLen
            val next = node.next ?: return RgaTreeSplitPos(node.id, partLen)
            node = next
            partLen = node.length
        }
        return RgaTreeSplitPos(node.id, offsetInPart)
    }

    /**
     * Splits [piece] so that a node exactly covering the absolute-offset
     * interval [[from], [to]) exists, and returns it along with the net
     * metadata-size overhead the split(s) introduced.
     *
     * When [piece] is live, this overhead is a normal live-bucket cost (same
     * as any other boundary split) and the caller should accumulate it into
     * `docSize.live`. When [piece] is tombstoned, [splitNode] itself buffers
     * the overhead of any born-removed fragment via [pendingGcPairs] (see
     * [drainPendingGcPairs]), so the returned diff is zero in that case — the
     * caller must still drain and register those pairs.
     *
     * Requires: pieceStart <= [from] < [to] <= pieceEnd.
     */
    private fun isolateRange(
        piece: RgaTreeSplitNode<T>,
        from: Int,
        to: Int,
    ): Pair<RgaTreeSplitNode<T>, DataSize> {
        var diff = DataSize(data = 0, meta = 0)
        var node = piece
        val nodeStart = node.id.offset
        if (from > nodeStart) {
            val (right, splitDiff) = splitNode(node, from - nodeStart)
            diff = addDataSizes(diff, splitDiff)
            node = requireNotNull(right)
        }
        val newStart = node.id.offset
        if (to < newStart + node.contentLength) {
            val (_, splitDiff) = splitNode(node, to - newStart)
            diff = addDataSizes(diff, splitDiff)
        }
        return node to diff
    }

    /**
     * Splits and returns nodes at the given [pos].
     */
    fun findNodeWithSplit(
        pos: RgaTreeSplitPos,
        executedAt: TimeTicket,
    ): Triple<RgaTreeSplitNode<T>, RgaTreeSplitNode<T>?, DataSize> {
        val absoluteID = pos.absoluteID
        var node = findFloorNodePreferToLeft(absoluteID)
        val relativeOffSet = absoluteID.offset - node.id.offset
        val (_, diff) = splitNode(node, relativeOffSet)

        while (node.hasNext && executedAt < node.next?.createdAt) {
            node = node.next ?: break
        }
        return Triple(node, node.next, diff)
    }

    private fun findFloorNodePreferToLeft(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T> {
        var node = findFloorNode(id)
            ?: throw NoSuchElementException("the node of the given id should be found: $id")
        if (id.offset > 0 && node.id.offset == id.offset) {
            if (!node.hasInsertionPrev) return node
            node = requireNotNull(node.insertionPrev)
        }
        return node
    }

    private fun findFloorNode(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T>? {
        val entry = treeByID.floorEntry(id) ?: return null
        return if (entry.key != id && !entry.key.hasSameCreatedAt(id)) {
            null
        } else {
            entry.value
        }
    }

    private fun splitNode(
        node: RgaTreeSplitNode<T>,
        offset: Int,
    ): Pair<RgaTreeSplitNode<T>?, DataSize> {
        // A negative offset is rejected the same as one past the end: JS's
        // `substring`/`truncate` silently clamp it to 0, duplicating content
        // instead of failing (port yorkie-js-sdk `rga_tree_split.ts`
        // `ed25355e`, #1449, yorkie#2111). Before this guard, Android instead
        // reached `TextValue.subSequence`'s `substring(negative, len)`, which
        // throws `StringIndexOutOfBoundsException` AFTER already allocating
        // the node -- this guard rejects it up front, before any mutation.
        if (offset < 0 || offset > node.contentLength) {
            throw IllegalArgumentException("offset should be less than or equal to length")
        }

        var diff = DataSize(
            data = 0,
            meta = 0,
        )

        if (offset == 0) {
            return Pair(node, diff)
        } else if (offset == node.contentLength) {
            return Pair(node.next, diff)
        }

        val prevSize = node.dataSize

        val splitNode = node.split(offset)
        if (splitNode.hasCancels()) {
            trackCancelled(splitNode)
        }
        treeByIndex.updateWeight(splitNode)
        insertAfter(node, splitNode)
        node.insertionNext?.setInsertionPrev(splitNode)
        splitNode.setInsertionPrev(node)

        diff = addDataSizes(diff, node.dataSize, splitNode.dataSize)
        diff = subDataSize(diff, prevSize)

        // The split deep-copies the value's attributes (splitValue below), so
        // any attribute already tombstoned on `node` is duplicated into
        // splitNode under a NEW, unregistered parent. That copy was never in
        // docSize.live (getDataSize skips removed attributes), so it enters
        // gc only (JS rga_tree_split.ts:1487-1500).
        pendingAttributeGcPairs.addAll(splitNode.value.gcPairs)

        // A piece split off an already-tombstoned node inherits removedAt
        // without going through recordRemoval(), so no GC pair is created
        // for it in the normal deletion path. Buffer one here so it can be purged;
        // otherwise it stays in the list forever. The piece was never live,
        // so the net-new size created by the split goes straight to
        // docSize.gc when the pair is registered; report a zero diff to the
        // caller (which accounts diffs to docSize.live).
        if (splitNode.isRemoved) {
            pendingGcPairs.add(GCPair(this, splitNode, gcOnlySize = diff))
            return Pair(splitNode, DataSize(data = 0, meta = 0))
        }

        return Pair(splitNode, diff)
    }

    /**
     * Insert the [newNode] after the given [prevNode].
     */
    fun insertAfter(
        prevNode: RgaTreeSplitNode<T>,
        newNode: RgaTreeSplitNode<T>,
    ): RgaTreeSplitNode<T> {
        val next = prevNode.next
        newNode.setPrev(prevNode)
        next?.setPrev(newNode)

        treeByID[newNode.id] = newNode
        treeByIndex.insertAfter(prevNode, newNode)
        return newNode
    }

    /**
     * Returns nodes between [fromNode] and [toNode].
     */
    fun findBetween(
        fromNode: RgaTreeSplitNode<T>?,
        toNode: RgaTreeSplitNode<T>?,
    ): List<RgaTreeSplitNode<T>> {
        var current = fromNode
        return buildList {
            while (current != toNode) {
                add(current ?: break)
                current = current?.next ?: break
            }
        }
    }

    private fun deleteNodes(
        candidates: List<RgaTreeSplitNode<T>>,
        editedAt: TimeTicket,
        vector: VersionVector?,
    ): Triple<
        MutableList<ContentChange>,
        Map<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>,
        Set<RgaTreeSplitNodeID>,
        > {
        if (candidates.isEmpty()) {
            return Triple(mutableListOf(), emptyMap(), emptySet())
        }

        // Treat missing or empty VersionVector as local operation.
        val isLocal = vector == null || vector.size() == 0

        // 01. Collect nodes to remove and keep.
        val nodesToRemove = ArrayList<RgaTreeSplitNode<T>>()
        val nodesToKeep = ArrayList<RgaTreeSplitNode<T>?>()
        val (leftEdge, rightEdge) = findEdgesOfCandidates(candidates)
        nodesToKeep.add(leftEdge)
        for (node in candidates) {
            // Compute per-node creationKnown and tombstoneKnown
            val creationKnown: Boolean

            if (isLocal) {
                creationKnown = true
            } else {
                val createdAtVV = vector?.get(node.createdAt.actorID)
                creationKnown = createdAtVV != null && createdAtVV >= node.createdAt.lamport
            }

            var tombstoneKnown = false
            val nodeRemovedAt = node.removedAt
            if (nodeRemovedAt != null) {
                val removedAtVV = vector?.get(nodeRemovedAt.actorID)
                if (isLocal) {
                    tombstoneKnown = true
                } else if (removedAtVV != null && removedAtVV >= nodeRemovedAt.lamport) {
                    tombstoneKnown = true
                }
            }

            // Once a tombstone can be cancelled by an undo, "the deleter knew
            // the node" is not enough -- the node has to have been LIVE in
            // the deleter's view. A node the deleter saw removed and an undo
            // revived here has to survive the deletion on every replica.
            val liveInView = if (isLocal) {
                !node.isRemoved
            } else {
                creationKnown && !node.isRemovedInView(requireNotNull(vector))
            }
            if (liveInView && node.canRemove(editedAt, creationKnown, tombstoneKnown)) {
                nodesToRemove.add(node)
            } else {
                // A node this deletion saw live but someone else removed
                // concurrently records the deletion all the same. Otherwise
                // an undo of the other deletion revives it on replicas that
                // applied the two in one order and not in the other.
                if (!isLocal && liveInView && node.isRemoved) {
                    node.recordRemoval(editedAt)
                }
                nodesToKeep.add(node)
            }
        }
        nodesToKeep.add(rightEdge)

        // 02. Create value changes with previous indexes before deletion.
        val changes = makeChanges(nodesToKeep, editedAt)

        // 03. Mark tombstones for removal. Nodes that were already removed
        // (concurrent LWW overwrite of an existing tombstone) are tracked
        // separately: they already have a registered GC pair, and
        // registering a second one would toggle-unregister the first.
        val removedNodes = mutableMapOf<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>()
        val alreadyRemovedIDs = mutableSetOf<RgaTreeSplitNodeID>()
        for (node in nodesToRemove) {
            if (node.isRemoved) {
                alreadyRemovedIDs.add(node.id)
            }
            removedNodes[node.id] = node
            node.recordRemoval(editedAt)
        }

        // 04. Clear the index tree of the given deletion boundaries.
        deleteIndexNodes(nodesToKeep)
        return Triple(changes, removedNodes, alreadyRemovedIDs)
    }

    /**
     * Finds the edges outside [candidates].
     * If right edge is null, it means [candidates] contains the end of text.
     */
    private fun findEdgesOfCandidates(
        candidates: List<RgaTreeSplitNode<T>>,
    ): Pair<RgaTreeSplitNode<T>, RgaTreeSplitNode<T>?> {
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("findEdgesOfCandidates error: candidates is empty")
        }
        return requireNotNull(candidates.first().prev) to candidates.last().next
    }

    private fun makeChanges(
        boundaries: List<RgaTreeSplitNode<T>?>,
        executedAt: TimeTicket,
    ): MutableList<ContentChange> {
        val changes = mutableListOf<ContentChange>()
        var (fromIndex, toIndex) = 0 to 0
        for (index in 0 until boundaries.lastIndex) {
            val leftBoundary = boundaries[index]
            val rightBoundary = boundaries[index + 1]
            if (leftBoundary?.next == rightBoundary) continue

            fromIndex =
                findIndexesFromRange(requireNotNull(leftBoundary?.next).createPosRange()).first
            toIndex = if (rightBoundary == null) {
                treeByIndex.length
            } else {
                findIndexesFromRange(requireNotNull(rightBoundary.prev).createPosRange()).second
            }
        }
        if (fromIndex < toIndex) {
            changes.add(ContentChange(executedAt.actorID, fromIndex, toIndex))
        }
        changes.reverse()
        return changes
    }

    fun findIndexesFromRange(range: RgaTreeSplitPosRange): Pair<Int, Int> {
        val (fromPos, toPos) = range
        return posToIndex(fromPos, false) to posToIndex(toPos, true)
    }

    internal fun posToIndex(pos: RgaTreeSplitPos, preferToLeft: Boolean): Int {
        val absoluteID = pos.absoluteID
        val node = if (preferToLeft) {
            findFloorNodePreferToLeft(absoluteID)
        } else {
            findFloorNode(absoluteID)
        } ?: throw NoSuchElementException("the node of the given ID should be found: $absoluteID")

        val index = treeByIndex.indexOf(node)
        val offset = if (node.isRemoved) 0 else absoluteID.offset - node.id.offset
        return index + offset
    }

    /**
     * Clears the index nodes of the given deletion [boundaries].
     * The [boundaries] mean the nodes that will not be deleted in the range.
     */
    private fun deleteIndexNodes(boundaries: List<RgaTreeSplitNode<T>?>) {
        for (index in 0..boundaries.size - 2) {
            val leftBoundary = boundaries[index]
            val rightBoundary = boundaries[index + 1]
            // If there is no node to delete between boundaries, do nothing.
            if (leftBoundary?.next != rightBoundary) {
                treeByIndex.cutOffRange(requireNotNull(leftBoundary), rightBoundary)
            }
        }
    }

    /**
     * Finds [RgaTreeSplitPos] of the given [index].
     */
    fun indexToPos(index: Int): RgaTreeSplitPos {
        val (node, offset) = treeByIndex.findForText(index)
        return node?.let {
            RgaTreeSplitPos(it.id, offset)
        } ?: throw NoSuchElementException("no node found with the given index: $index")
    }

    /**
     * Finds the node of the given [id].
     */
    fun findNode(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T> {
        return requireNotNull(findFloorNode(id))
    }

    /**
     * Implements [GCParent.purgeBarrierAt]. [findNodeWithSplit] skips
     * forward while the next node was created after the incoming edit, so a
     * tombstone whose `createdAt` precedes the edit stops that walk.
     * Unlinking it hands the next node the stopping decision, which is only
     * the same decision once that node is causally stable.
     */
    @Suppress("UNCHECKED_CAST")
    override fun purgeBarrierAt(node: GCChild): TimeTicket? {
        return (node as? RgaTreeSplitNode<T>)?.next?.createdAt
    }

    /**
     * Physically deletes the given node from this [RgaTreeSplit].
     */
    override fun delete(node: RgaTreeSplitNode<T>) {
        cancelled?.remove(node)
        treeByIndex.delete(node)
        treeByID.remove(node.id)

        val prev = node.prev
        val next = node.next
        val insertionPrev = node.insertionPrev
        val insertionNext = node.insertionNext

        prev?.setNext(next)
        next?.setPrev(prev)
        node.setPrev(null)
        node.setNext(null)
        insertionPrev?.setInsertionNext(insertionNext)
        insertionNext?.setInsertionPrev(insertionPrev)
        node.setInsertionPrev(null)
        node.setInsertionNext(null)
    }

    override fun iterator(): Iterator<RgaTreeSplitNode<T>> {
        return RgaTreeSplitIterator(head)
    }

    private class RgaTreeSplitIterator<T : RgaTreeSplitValue<T>>(head: RgaTreeSplitNode<T>) :
        Iterator<RgaTreeSplitNode<T>> {
        private var node: RgaTreeSplitNode<T>? = head

        override fun hasNext(): Boolean {
            return node?.hasNext == true
        }

        override fun next(): RgaTreeSplitNode<T> {
            return requireNotNull(node?.next).apply {
                node = node?.next
            }
        }
    }

    fun deepCopy(): RgaTreeSplit<T> {
        val clone = RgaTreeSplit<T>()
        // Pass 1: copy every node in document order.
        var node = head.next
        var prev = clone.head
        while (node != null) {
            prev = clone.insertAfter(prev, node.deepCopy())
            node = node.next
        }
        // Pass 2: resolve insertion-chain links only once every node exists.
        // A link may point at a node that sits LATER in document order:
        // restore() relinks a recreated fragment to its same-insertion
        // neighbours by offset, while rung (c) can place that fragment behind
        // the rightmost survivor. Resolving while walking (the previous
        // one-pass shape) threw on exactly that case and, through
        // Document.ensureClone(), bricked the document.
        node = head.next
        var copy = clone.head.next
        while (node != null && copy != null) {
            val insertionPrev = node.insertionPrev
            if (insertionPrev != null) {
                copy.setInsertionPrev(clone.findNode(insertionPrev.id))
            }
            node = node.next
            copy = copy.next
        }
        return clone
    }

    override fun toString(): String {
        return buildString {
            this@RgaTreeSplit.forEach { node ->
                if (!node.isRemoved) append(node.value)
            }
        }
    }

    data class ContentChange(
        val actorID: String,
        val from: Int,
        val to: Int,
        val content: String? = null,
        // Full node value for revived nodes (JS ValueChange.value): restored
        // pieces carry per-node attributes that the flat `content` string
        // cannot express.
        val value: RgaTreeSplitValue<*>? = null,
    )

    companion object {
        private const val TAG = "RgaTreeSplit"

        private val InitialNodeID = RgaTreeSplitNodeID(InitialTimeTicket, 0)

        object InitialNodeValue : RgaTreeSplitValue<InitialNodeValue> {

            override fun deepCopy(): InitialNodeValue = this

            override fun getDataSize(): DataSize = DataSize(
                data = 0,
                meta = 0,
            )

            override val length: Int = 0

            override fun get(index: Int): Char = throw IndexOutOfBoundsException()

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this

            // The sentinel's contentLength is always 0, so splitNode's
            // offset-bounds check rejects any offset > 0 before this could
            // ever be reached — never truncated in practice.
            override fun truncate(offset: Int) {
                throw UnsupportedOperationException("InitialNodeValue is never truncated")
            }
        }
    }
}

internal interface RgaTreeSplitValue<T : RgaTreeSplitValue<T>> : CharSequence {

    fun deepCopy(): T

    fun getDataSize(): DataSize

    /**
     * Shortens this value to its first [offset] units IN PLACE, keeping
     * object identity — a split keeps the LEFT piece's value
     * object rather than replacing it, so GC pairs already registered
     * against it (keyed by identity) stay valid.
     */
    fun truncate(offset: Int)

    /**
     * Attribute tombstones this value holds, as GC pairs ready to register.
     * A split deep-copies attributes into the new piece ([RgaTreeSplit]'s
     * private `splitNode`), duplicating any already-removed attribute under
     * a new, unregistered parent — without this, that copy could never be
     * collected. Empty by default; only a value that carries attributes
     * (`TextValue`) overrides it.
     */
    val gcPairs: List<GCPair<*>>
        get() = emptyList()
}

/**
 * One deletion in a [RgaTreeSplitNode]'s removal ledger, and the undo that
 * cancelled it.
 */
internal data class LedgerCancel(val deletion: TimeTicket, val cancelledAt: TimeTicket)

/**
 * The reading of a node's removal ledger, kept because a ledger grows a pair
 * per delete-and-undo cycle and rebuilding it on every walk would make
 * churning one quadratic. [entries] preserves insertion order; [byKey] and
 * [live] are derived views over it. Every write of the ledger either drops
 * this cache or rebuilds it.
 */
private class LedgerIndex {
    class Entry(val removal: TimeTicket, var cancelledAt: TimeTicket? = null)

    val entries = mutableListOf<Entry>()
    val byKey = mutableMapOf<TimeTicket, Entry>()
    val live = mutableListOf<TimeTicket>()
    val cancelled = mutableMapOf<TimeTicket, TimeTicket>()
}

internal data class RgaTreeSplitNode<T : RgaTreeSplitValue<T>>(
    val id: RgaTreeSplitNodeID,
    private var _value: T,
    private var _removedAt: TimeTicket? = null,
) : GCChild {
    var prev: RgaTreeSplitNode<T>? = null
        private set
    var next: RgaTreeSplitNode<T>? = null
        private set
    var insertionPrev: RgaTreeSplitNode<T>? = null
        private set
    var insertionNext: RgaTreeSplitNode<T>? = null
        private set

    // The removal ledger beyond `_removedAt`: `dels` are the deletions that
    // removed this node while it was live in the deleter's view; `cancels`
    // are the deletions an undo cancelled. `_removedAt` is the latest
    // uncancelled deletion. `!ledgered` means the ledger is just
    // `_removedAt` -- every node starts and stays this way until a second
    // removal or a cancel needs the full ledger (ported from JS SDK
    // `rga_tree_split.ts`, yorkie-js-sdk #1514, yorkie#2310).
    private var ledgered: Boolean = false
    private var dels: MutableList<TimeTicket>? = null
    private var cancels: MutableList<LedgerCancel>? = null
    private var ledgerIndexCache: LedgerIndex? = null

    val createdAt: TimeTicket
        get() = id.createdAt

    val length: Int
        get() = if (isRemoved) 0 else contentLength

    val contentLength: Int
        get() = _value.length

    val hasNext: Boolean
        get() = next != null

    val hasInsertionPrev: Boolean
        get() = insertionPrev != null

    val isRemoved: Boolean
        get() = _removedAt != null

    override val removedAt: TimeTicket?
        get() = _removedAt

    override val dataSize: DataSize
        get() {
            val dataSize = _value.getDataSize()
            var meta = dataSize.meta + TIME_TICKET_SIZE
            if (_removedAt != null) {
                meta += TIME_TICKET_SIZE
            }

            return DataSize(
                data = dataSize.data,
                meta = meta,
            )
        }

    val value
        get() = _value

    fun setPrev(node: RgaTreeSplitNode<T>?) {
        prev = node
        node?.next = this
    }

    fun setNext(node: RgaTreeSplitNode<T>?) {
        next = node
        node?.prev = this
    }

    fun setInsertionPrev(node: RgaTreeSplitNode<T>?) {
        insertionPrev = node
        node?.insertionNext = this
    }

    fun setInsertionNext(node: RgaTreeSplitNode<T>?) {
        insertionNext = node
        node?.insertionPrev = this
    }

    /**
     * Creates a new split node of the given [offset].
     */
    fun split(offset: Int): RgaTreeSplitNode<T> {
        return RgaTreeSplitNode(id.split(offset), splitValue(offset), _removedAt).also {
            it.copyLedger(this)
        }
    }

    /**
     * Copies the removal ledger of [other] onto this node, used by [split]
     * and [deepCopy]. [other]'s own [dels]/[cancels] lists are copied, not
     * shared -- `data class`'s generated `copy()` only reconstructs the
     * constructor parameters ([id], [_value], [_removedAt]); these body
     * fields need an explicit copy or a split/clone silently starts
     * unledgered.
     */
    private fun copyLedger(other: RgaTreeSplitNode<T>) {
        ledgered = other.ledgered
        dels = other.dels?.toMutableList()
        cancels = other.cancels?.toMutableList()
        ledgerIndexCache = null
    }

    /** Whether the ledger holds at least one cancel -- the only thing [pruneStable] can drop. */
    fun hasCancels(): Boolean = !cancels.isNullOrEmpty()

    /**
     * The deletions in the ledger, without building one: an unledgered node
     * was removed by [_removedAt] alone, if at all.
     */
    private fun removals(): List<TimeTicket> {
        return if (ledgered) dels.orEmpty() else listOfNotNull(_removedAt)
    }

    /** Ensures the ledger exists, seeding it from [_removedAt] on first use. */
    private fun ledger(): MutableList<TimeTicket> {
        if (!ledgered) {
            ledgered = true
            dels = _removedAt?.let { mutableListOf(it) } ?: mutableListOf()
            ledgerIndexCache = null
        }
        return requireNotNull(dels)
    }

    private fun ledgerIndex(): LedgerIndex {
        ledgerIndexCache?.let { return it }
        val index = LedgerIndex()
        cancels?.forEach { index.cancelled[it.deletion] = it.cancelledAt }
        for (d in removals()) {
            if (index.byKey.containsKey(d)) continue
            val entry = LedgerIndex.Entry(d, index.cancelled[d])
            index.entries.add(entry)
            index.byKey[d] = entry
            if (entry.cancelledAt == null) index.live.add(d)
        }
        ledgerIndexCache = index
        return index
    }

    private fun recompute() {
        var latest: TimeTicket? = null
        for (d in ledgerIndex().live) {
            if (latest == null || d > latest) latest = d
        }
        _removedAt = latest
    }

    /**
     * Adds a deletion to the ledger. A node's first removal is just
     * [_removedAt]; the ledger is built only when a second removal or a
     * cancel needs it. An actor removes a piece only while it is live in its
     * view, so it never holds two uncancelled removals; a change whose
     * vector leaves out its own earlier removal is not honest, and recording
     * it would break the pairing [getRemovalEvents] relies on and grow the
     * ledger.
     */
    fun recordRemoval(removedAt: TimeTicket) {
        if (!ledgered && _removedAt == null) {
            _removedAt = removedAt
            ledgerIndexCache = null
            return
        }
        ledger()
        val mine = uncancelledRemovalBy(removedAt.actorID)
        if (mine != null && mine != removedAt) {
            return
        }
        val index = ledgerIndex()
        if (mine == null && !index.byKey.containsKey(removedAt)) {
            requireNotNull(dels).add(removedAt)
            val entry = LedgerIndex.Entry(removedAt)
            index.entries.add(entry)
            index.byKey[removedAt] = entry
            index.live.add(removedAt)
        }
        recompute()
    }

    /**
     * Cancels one deletion (an undo of it). Returns whether the node is live
     * afterwards.
     */
    fun cancelRemoval(deletion: TimeTicket, at: TimeTicket): Boolean {
        ledger()
        val cancelList = cancels ?: mutableListOf<LedgerCancel>().also { cancels = it }
        val index = ledgerIndex()
        if (!index.cancelled.containsKey(deletion)) {
            cancelList.add(LedgerCancel(deletion, at))
            index.cancelled[deletion] = at
            index.byKey[deletion]?.let { entry ->
                entry.cancelledAt = at
                val liveIndex = index.live.indexOf(deletion)
                if (liveIndex != -1) index.live.removeAt(liveIndex)
            }
        }
        recompute()
        return _removedAt == null
    }

    /**
     * Drops each removal whose cancel is known to every replica, with that
     * cancel. A change that arrives later knows both, so the pair never
     * decides [isRemovedInView] again, and dropping it keeps an undo-heavy
     * text from carrying every removal it ever had. What is left once
     * nothing more can be dropped is the default ledger ([_removedAt] alone).
     */
    fun pruneStable(vector: VersionVector) {
        val keptCancels = mutableListOf<LedgerCancel>()
        val dropped = mutableSetOf<TimeTicket>()
        cancels?.forEach { c ->
            if (!vector.afterOrEqual(c.cancelledAt)) {
                keptCancels.add(c)
            } else {
                dropped.add(c.deletion)
            }
        }
        var keptDels = dels
        if (dropped.isNotEmpty() && keptDels != null) {
            keptDels = keptDels.filterNot { it in dropped }.toMutableList()
        }
        dels = keptDels
        cancels = keptCancels
        ledgerIndexCache = null
        val currentRemovedAt = _removedAt
        val collapsesToPlain = keptDels.isNullOrEmpty() ||
            (keptDels.size == 1 && currentRemovedAt != null && keptDels[0] == currentRemovedAt)
        if (keptCancels.isEmpty() && collapsesToPlain) {
            ledgered = false
            dels = null
            cancels = null
        }
    }

    /** The removal by [actor] no undo cancelled, if any. */
    fun uncancelledRemovalBy(actor: String): TimeTicket? {
        return ledgerIndex().live.firstOrNull { it.actorID == actor }
    }

    /**
     * Tells whether GC must keep this tombstone under [vector]: a node
     * removed by two or more deletions no undo cancelled stays (an undo of
     * one of them must find it still held by the others), and a node whose
     * ledger is not yet stable stays too (every deletion and cancel in it
     * must be known to all replicas, so a restore recreating this node after
     * a purge answers [isRemovedInView] as the full ledger would).
     */
    override fun heldFromPurge(vector: VersionVector): Boolean {
        if (!ledgered) return false
        val liveCount = ledgerIndex().live.size
        cancels?.forEach { c ->
            if (!vector.afterOrEqual(c.cancelledAt)) return true
        }
        return liveCount >= 2
    }

    /**
     * Tells whether the node was removed in the view of [vector]: a deletion
     * it knew that no undo it knew cancelled.
     */
    fun isRemovedInView(vector: VersionVector): Boolean {
        for (entry in ledgerIndex().entries) {
            if (!ticketKnown(vector, entry.removal)) continue
            val cancelledAt = entry.cancelledAt
            if (cancelledAt != null && ticketKnown(vector, cancelledAt)) continue
            return true
        }
        return false
    }

    /**
     * Returns the ledger as one list: every removal and every cancel's
     * ticket except [_removedAt], in ticket order. Empty when [_removedAt]
     * says it all. An actor's events alternate in ticket order -- it removes
     * a piece only while the piece is live in its view, and its undo
     * cancels its one uncancelled removal -- so [setRemovalEvents] can tell
     * them apart by place.
     */
    fun getRemovalEvents(): List<TimeTicket> {
        if (!ledgered) return emptyList()
        val events = mutableListOf<TimeTicket>()
        dels?.forEach { d -> if (_removedAt == null || d != _removedAt) events.add(d) }
        cancels?.forEach { c -> events.add(c.cancelledAt) }
        return events.sorted()
    }

    /**
     * Rebuilds the ledger from [_removedAt] and the events [getRemovalEvents]
     * returned: per actor, in ticket order, a removal then the cancel of it.
     * Throws on a repeated ticket, which would pair a cancel with a removal
     * that is not older, and when the result does not reproduce
     * [_removedAt] -- and leaves the node as it was in either case.
     */
    fun setRemovalEvents(events: List<TimeTicket>) {
        if (events.isEmpty()) return
        val removedAtBefore = _removedAt
        val all = events.toMutableList()
        removedAtBefore?.let { all.add(it) }
        all.sort()
        for (i in 1 until all.size) {
            if (all[i] == all[i - 1]) {
                throw YorkieException(
                    YorkieException.Code.ErrInvalidArgument,
                    "removal events repeat a ticket",
                )
            }
        }

        val newDels = mutableListOf<TimeTicket>()
        val newCancels = mutableListOf<LedgerCancel>()
        val open = mutableMapOf<String, TimeTicket>()
        for (t in all) {
            val opened = open[t.actorID]
            if (opened != null) {
                newCancels.add(LedgerCancel(opened, t))
                open.remove(t.actorID)
                continue
            }
            newDels.add(t)
            open[t.actorID] = t
        }

        ledgered = true
        dels = newDels
        cancels = newCancels
        ledgerIndexCache = null
        recompute()

        val reproduces = removedAtBefore == _removedAt
        if (!reproduces) {
            _removedAt = removedAtBefore
            ledgered = false
            dels = null
            cancels = null
            ledgerIndexCache = null
            throw YorkieException(
                YorkieException.Code.ErrInvalidArgument,
                "removal events do not reproduce removed_at",
            )
        }
    }

    // Take the right part first (subSequence deep-copies attributes), then
    // shorten this value IN PLACE rather than replacing it, so GC pairs
    // already registered against it (keyed by identity) keep pointing at a
    // live object (JS rga_tree_split.ts:602-607). The baseline
    // replaced `_value` outright, orphaning the left half's registered
    // pairs.
    @Suppress("UNCHECKED_CAST")
    private fun splitValue(offset: Int): T {
        val right = _value.subSequence(offset, _value.length) as T
        _value.truncate(offset)
        return right
    }

    /**
     * Checks if this [RgaTreeSplitNode] can be deleted or not.
     *
     * @param editedAt The time when the edit operation was executed
     * @param creationKnown Whether the node's creation was visible at the operation's frontier
     * @param tombstoneKnown Whether the prior tombstone was visible at the operation's frontier
     *
     * LWW: Allow overwrite only when tombstoneKnown is false and editedAt is newer.
     */
    fun canRemove(
        editedAt: TimeTicket,
        creationKnown: Boolean,
        tombstoneKnown: Boolean,
    ): Boolean {
        // Skip if the node's creation was not visible to this operation.
        if (!creationKnown) {
            return false
        }

        if (_removedAt == null) {
            return true
        }

        // Allow overwrite only when tombstoneKnown is false and editedAt is newer.
        if (!tombstoneKnown && editedAt > _removedAt) {
            return true
        }

        return false
    }

    /**
     * Checks if node is able to set style.
     *
     * Answers only whether the styling change knew this node existed —
     * deliberately NOT whether the node has since been removed. A style is
     * applied unconditionally on the replica that issues it (the node is
     * live there, or the range would not have reached it) and can never be
     * retracted afterwards, so every other replica has to apply it too; a
     * rule that reads [removedAt] is delivery-order dependent, since
     * [removedAt] is last-writer-wins and mutable while a style is
     * evaluated once, when it arrives. Mirrors JS SDK `canStyle`
     * (`rga_tree_split.ts:499-524`, yorkie-js-sdk e0609c7a #1368).
     * BEHAVIOUR CHANGE: a style now covers text the same replica already
     * deleted; see
     * [CrdtText.style]/[CrdtText.removeStyle].
     */
    fun canStyle(versionVector: VersionVector?): Boolean = ticketKnown(versionVector, createdAt)

    /**
     * Sets the remove time of this node. Drops the ledger: a direct LWW
     * overwrite of [_removedAt] (not a ledgered [recordRemoval]) means this
     * node is no longer tracked by the identity-preserving undo path, so any
     * prior ledger no longer describes it.
     */
    fun setRemovedAt(removedAt: TimeTicket?) {
        _removedAt = removedAt
        ledgered = false
        dels = null
        cancels = null
        ledgerIndexCache = null
    }

    fun createPosRange(): RgaTreeSplitPosRange {
        return RgaTreeSplitPosRange(RgaTreeSplitPos(id, 0), RgaTreeSplitPos(id, length))
    }

    fun deepCopy(): RgaTreeSplitNode<T> {
        return copy(_value = _value.deepCopy()).also { it.copyLedger(this) }
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    // S8: identity equality is deliberate — GC pairs are keyed on node
    // identity (CrdtRoot.gcPairMap is an IdentityHashMap since the 2026-09-10
    // review, so the map no longer depends on this override, but the rest of
    // the split — boundary lists, untombstoned lists — still compares nodes
    // by identity). A future copy()-based refactor must not reintroduce
    // structural equality.
    override fun equals(other: Any?): Boolean {
        return super.equals(other)
    }
}

public data class RgaTreeSplitNodeID internal constructor(
    val createdAt: TimeTicket,
    val offset: Int,
) : Comparable<RgaTreeSplitNodeID>, JsonSerializable<RgaTreeSplitNodeID, RgaTreeSplitNodeIDStruct> {
    /**
     * Returns whether the given ID has the same creation time with this [RgaTreeSplitNodeID].
     */
    fun hasSameCreatedAt(other: RgaTreeSplitNodeID) = createdAt == other.createdAt

    /**
     * Creates a new [RgaTreeSplitNodeID] with the given [offset].
     */
    fun split(offset: Int): RgaTreeSplitNodeID {
        return RgaTreeSplitNodeID(createdAt, this.offset + offset)
    }

    override fun compareTo(other: RgaTreeSplitNodeID): Int {
        return compareValuesBy(this, other, { it.createdAt }, { it.offset })
    }

    override fun toStruct(): RgaTreeSplitNodeIDStruct {
        return RgaTreeSplitNodeIDStruct(createdAt.toStruct(), offset)
    }
}

public data class RgaTreeSplitPos internal constructor(
    val id: RgaTreeSplitNodeID,
    val relativeOffSet: Int,
) : JsonSerializable<RgaTreeSplitPos, RgaTreeSplitPosStruct> {
    val absoluteID: RgaTreeSplitNodeID
        get() = RgaTreeSplitNodeID(id.createdAt, id.offset + relativeOffSet)

    override fun toStruct(): RgaTreeSplitPosStruct {
        return RgaTreeSplitPosStruct(id.toStruct(), relativeOffSet)
    }
}
