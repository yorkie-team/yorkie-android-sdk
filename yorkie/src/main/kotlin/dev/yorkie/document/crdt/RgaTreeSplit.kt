package dev.yorkie.document.crdt

import dev.yorkie.document.JsonSerializable
import dev.yorkie.document.RgaTreeSplitNodeIDStruct
import dev.yorkie.document.RgaTreeSplitPosStruct
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.SplayTreeSet
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize
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
     * through [RgaTreeSplitNode.remove], so they would otherwise never be
     * registered for GC. Callers that split nodes ([edit], [CrdtText.style],
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
        // reversed (port 270ffc66).
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
                        target.setRemovedAt(null)
                        // Repair splay weights on the path to root (length 0 -> len).
                        treeByIndex.splay(target)
                        untombstoned.add(target)
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
                    liveDiff = addDataSizes(liveDiff, newNode.dataSize)
                    val prev =
                        findRestoreAnchor(
                            span.createdAt,
                            cursor,
                            gapEnd,
                            executedAt,
                            fallbackAnchor,
                            chainAnchor,
                        )
                    insertAfter(prev, newNode)
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
     * preserving undo). Only live pieces are affected; already removed or
     * purged regions are skipped (idempotent).
     *
     * Returns GC pairs for the newly tombstoned nodes, the removed regions as
     * deletions, and the metadata-size overhead from splitting the (live)
     * pieces to isolate the target range. The caller must accumulate
     * [RgaTreeSplitRetombstoneResult.dataSize] before registering
     * [RgaTreeSplitRetombstoneResult.gcPairs], mirroring how a normal edit's
     * boundary splits are accounted before its resulting tombstones are
     * registered. Indices are captured before each removal, so applying the
     * changes in emission order stays consistent.
     */
    fun retombstone(
        spans: List<RestoreSpan<T>>,
        executedAt: TimeTicket,
    ): RgaTreeSplitRetombstoneResult<T> {
        val gcPairs = mutableListOf<GCPair<RgaTreeSplitNode<T>>>()
        val changes = mutableListOf<ContentChange>()
        var diff = DataSize(data = 0, meta = 0)

        for (span in spans) {
            val pieces = findPiecesOverlapping(span.createdAt, span.start, span.end)
            for (piece in pieces) {
                if (piece.isRemoved) continue

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
                target.remove(executedAt)
                treeByIndex.splay(target)
                gcPairs.add(GCPair(this, target))
                if (from < to) {
                    changes.add(ContentChange(executedAt.actorID, from, to))
                }
            }
        }

        // Defensive: retombstone only ever isolates live pieces, so
        // splitNode never buffers anything here — drain anyway to stay
        // consistent with every other caller of isolateRange/splitNode.
        gcPairs.addAll(drainPendingGcPairs())

        return RgaTreeSplitRetombstoneResult(gcPairs, changes, diff)
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
     * Resolution ladder (all rules key on op-carried data + ID lookups only):
     *  (a) a piece covering [gapEnd] exists -> directly before it
     *      (originally-adjacent successor; exact original slot)
     *  (b) nearest surviving piece of the same insertion left of [gapStart]
     *      -> directly after it
     *  (c) rightmost surviving piece of the same insertion (must be right of
     *      the gap) -> directly before it
     *  (d) chain anchor: the previously placed fragment of this same restore
     *      (document order) -> after it, so a purged multi-fragment run is
     *      rebuilt left-to-right rather than reversed
     *  (e) [fallbackAnchor], resolved via [findNodeWithSplit] (DEC-5: Android
     *      has no refinePos/normalizePos; the caller already reconciles
     *      [fallbackAnchor] from undo integer offsets, mirroring JS's
     *      fromPos-doubles-as-fallback-anchor interplay)
     *  (f) [head] (deterministic last resort)
     */
    private fun findRestoreAnchor(
        createdAt: TimeTicket,
        gapStart: Int,
        gapEnd: Int,
        executedAt: TimeTicket,
        fallbackAnchor: RgaTreeSplitPos?,
        chainAnchor: RgaTreeSplitNode<T>?,
    ): RgaTreeSplitNode<T> {
        findPieceCovering(createdAt, gapEnd)?.let { successor ->
            return requireNotNull(successor.prev)
        }

        if (gapStart > 0) {
            val key = RgaTreeSplitNodeID(createdAt, gapStart - 1)
            val entry = treeByID.floorEntry(key)
            if (entry != null && entry.key.hasSameCreatedAt(key)) {
                return entry.value
            }
        }

        val rightmostKey = RgaTreeSplitNodeID(createdAt, Int.MAX_VALUE)
        val rightmost = treeByID.floorEntry(rightmostKey)
        if (rightmost != null &&
            rightmost.key.hasSameCreatedAt(rightmostKey) &&
            rightmost.value.id.offset >= gapEnd
        ) {
            return requireNotNull(rightmost.value.prev)
        }

        // (d) No surviving piece of this insertion anchors the fragment.
        // When the whole run was purged, every fragment lands here;
        // anchoring after the fragment placed just before it (document
        // order) keeps the run forward.
        chainAnchor?.let { return it }

        if (fallbackAnchor != null) {
            try {
                return findNodeWithSplit(fallbackAnchor, executedAt).first
            } catch (e: RuntimeException) {
                // Anchor fully purged — fall through to (f).
            }
        }

        logDebug(TAG, "restore anchor exhausted; falling back to head")
        return head
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
        if (offset > node.contentLength) {
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
        treeByIndex.updateWeight(splitNode)
        insertAfter(node, splitNode)
        node.insertionNext?.setInsertionPrev(splitNode)
        splitNode.setInsertionPrev(node)

        diff = addDataSizes(diff, node.dataSize, splitNode.dataSize)
        diff = subDataSize(diff, prevSize)

        // A piece split off an already-tombstoned node inherits removedAt
        // without going through remove(), so no GC pair is created for it in
        // the normal deletion path. Buffer one here so it can be purged;
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

            if (node.canRemove(editedAt, creationKnown, tombstoneKnown)) {
                nodesToRemove.add(node)
            } else {
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
            node.remove(removedAt = editedAt)
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
     * Physically deletes the given node from this [RgaTreeSplit].
     */
    override fun delete(node: RgaTreeSplitNode<T>) {
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
        var node = head.next
        var prev = clone.head
        var current: RgaTreeSplitNode<T>
        while (node != null) {
            current = clone.insertAfter(prev, node.deepCopy())
            if (node.hasInsertionPrev) {
                val insertionPrevNode = clone.findNode(requireNotNull(node.insertionPrev).id)
                current.setInsertionPrev(insertionPrevNode)
            }
            prev = current
            node = node.next
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
        }
    }
}

internal interface RgaTreeSplitValue<T : RgaTreeSplitValue<T>> : CharSequence {

    fun deepCopy(): T

    fun getDataSize(): DataSize
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
        return RgaTreeSplitNode(id.split(offset), splitValue(offset), _removedAt)
    }

    @Suppress("UNCHECKED_CAST")
    private fun splitValue(offset: Int): T {
        val valueBefore = _value
        _value = valueBefore.subSequence(0, offset) as T
        return valueBefore.subSequence(offset, valueBefore.length) as T
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
     */
    fun canStyle(executedAt: TimeTicket, clientLamportAtChange: Long): Boolean {
        val nodeExisted = createdAt.lamport <= clientLamportAtChange

        return nodeExisted && (removedAt == null || executedAt > removedAt)
    }

    /**
     * Sets the remove time of this node.
     */
    fun setRemovedAt(removedAt: TimeTicket?) {
        _removedAt = removedAt
    }

    /**
     * Removes the node with the given removedAt timestamp.
     *
     * Precondition: `canRemove` was checked before calling this method.
     * This sets or overwrites removedAt if the new timestamp is newer.
     */
    fun remove(removedAt: TimeTicket) {
        if (_removedAt == null || removedAt > _removedAt) {
            _removedAt = removedAt
        }
    }

    fun createPosRange(): RgaTreeSplitPosRange {
        return RgaTreeSplitPosRange(RgaTreeSplitPos(id, 0), RgaTreeSplitPos(id, length))
    }

    fun deepCopy(): RgaTreeSplitNode<T> {
        return copy(_value = _value.deepCopy())
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

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
