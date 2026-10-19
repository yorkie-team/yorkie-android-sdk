package dev.yorkie.document.crdt

import dev.yorkie.util.traverseAll
import dev.yorkie.util.traverseAllPreorder
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Bounds a walk of an `insNextID`/`insPrevID` chain.
 *
 * `insNextID` is a structural pointer only [CrdtTreeNode.split] is supposed
 * to set, but it also arrives verbatim from client-supplied bytes, so a
 * document rebuilt from stored or remote changes can hold a chain that loops
 * back on itself. An unbounded walk of such a chain would spin the applying
 * task forever. Every chain walk in [CrdtTree] runs through one of these.
 *
 * Identity set, never structural `equals`: [CrdtTreeNode] is a data class
 * whose generated `equals` recurses over `childNodes`/attributes, which
 * would turn this cycle guard itself into a deep comparison on a hot path —
 * the same reason [CrdtTree.resolveMergeTarget] uses an identity set. Port
 * JS SDK `tree.ts` `InsNextWalker` (c8928853, #1375).
 */
internal class InsNextWalker {
    private val seen = Collections.newSetFromMap(IdentityHashMap<CrdtTreeNode, Boolean>())

    /**
     * Records [node] and reports whether this walk had not already passed
     * through it. A false result means the chain is cyclic; stop following
     * it.
     */
    fun visit(node: CrdtTreeNode): Boolean = seen.add(node)
}

/**
 * Clears the split-sibling links ([CrdtTreeNode.insPrevID]/
 * [CrdtTreeNode.insNextID]) on this node and every one of its descendants
 * (tombstones included).
 *
 * `insPrevID`/`insNextID` name positions in a split chain and only
 * [CrdtTreeNode.split] may create them. A node arriving as operation
 * content, a decoded simple element, or an undo/redo-restored value is
 * freshly created by the editing client, so it can never legitimately be a
 * split product — but the wire format carries the fields regardless, and
 * the chain walks that read them treat them as trusted structural pointers.
 * Drop them on the way in rather than let a peer hand the tree a chain of
 * its choosing. Port JS SDK `tree.ts` `CRDTTreeNode.dropSplitLinks`
 * (c8928853, #1375).
 */
internal fun CrdtTreeNode.dropSplitLinks() {
    traverseAllPreorder(this) { node, _ ->
        node.insPrevID = null
        node.insNextID = null
    }
}

/**
 * Clears the engine-only links on this node and every one of its descendants
 * (tombstones included): the split-sibling chain
 * ([CrdtTreeNode.insPrevID]/[CrdtTreeNode.insNextID]) and the merge lineage
 * ([CrdtTreeNode.mergedFrom]/[CrdtTreeNode.mergedAt]/[CrdtTreeNode.mergedInto]).
 *
 * The operation-content counterpart of [dropSplitLinks]: it REPLACES the
 * earlier `dropSplitLinks()` + `dropMergeStamps()` pair at the edit-content
 * decode call site. `mergedFrom`/`mergedAt` name the parent a
 * node was moved out of and only a merge may stamp them ([CrdtTree.edit]
 * restamps them on the content it inserts, from the merge parent it resolves
 * locally), so content arriving on a tree edit -- always freshly created by
 * the editing client, never a copy of live state -- can never legitimately
 * carry them, while [CrdtTree.declaredBoundaries] and the §6.2 delete
 * cascade read them as trusted structural pointers. `mergedInto` goes with
 * them: the decoder derives it from `mergedFrom`, so leaving it would keep a
 * source pointing at a destination no field records any more.
 *
 * Unlike this function, [dropSplitLinks] alone IS safe to call on an
 * arbitrary tree, which is why the Set/Add/ArraySet element path
 * ([dropSplitLinksInElement]) keeps calling it instead of this one: a
 * reverse-of-Remove copy legitimately carries real merge lineage that must
 * survive, and only operation content can never legitimately hold either
 * kind of link. Port JS SDK `tree.ts` `CRDTTreeNode.dropEngineOnlyLinks`
 * (d3ffe946, #1406; yorkie#2033).
 */
internal fun CrdtTreeNode.dropEngineOnlyLinks() {
    traverseAllPreorder(this) { node, _ ->
        node.insPrevID = null
        node.insNextID = null
        node.mergedFrom = null
        node.mergedAt = null
        node.mergedInto = null
    }
}

/**
 * Clears the tombstone ([CrdtTreeNode.removedAt]) on this node and every one
 * of its descendants, restoring the sizes a decoded tombstone suppressed.
 *
 * Like the engine-only links, `removedAt` is carried by the wire format on
 * every tree node yet operation content can never legitimately hold it: a
 * tree edit's content is freshly created by the editing client, so a node
 * arriving tombstoned is a crafted one. Left in place, [CrdtTree.edit] would
 * count it into the live data size and register no GC pair for it under a
 * live parent, while [CrdtTreeNode.isRemoved] hides it from every later edit.
 *
 * Cleared via [CrdtTreeNode.unremove] so each node's padded size is given
 * back to its ancestors. [traverseAll] is post-order, so a child is revived
 * before its parent, and the parent's own size already includes the child
 * by the time the parent hands it further up.
 *
 * A node's attribute table carries tombstones of its own, and those are
 * deliberately NOT cleared here. Unlike `removedAt` on the node, a removed
 * RHT entry on content can be genuine: the undo copy-reinsert path re-sends
 * a deep copy of nodes a real `removeStyle` tombstoned, and it has to, or
 * the reinserted node would stop rejecting the stale styles the original
 * rejects. Stripping them on decode would also make this replica disagree
 * with every other producer of the same bytes -- an older yorkie-android-sdk,
 * the JS SDK, the Go SDK and the snapshot the server rebuilds from this very
 * operation -- which is divergence, not hardening. A crafted one is instead
 * made harmless the way every other decode path already makes it harmless:
 * [CrdtTree.edit] books it into gc through [CrdtTreeNode.gcPairs], the same
 * routing the snapshot and the Set/Add element payload take through
 * [CrdtTree.gcPairs]. Port JS SDK `tree.ts` `CRDTTreeNode.clearTombstones`
 * (d3ffe946, #1406; yorkie#2033).
 */
internal fun CrdtTreeNode.clearTombstones() {
    traverseAll(this) { node, _ -> node.unremove() }
}

/**
 * Strips the split-sibling links from every tree reachable from [elem].
 *
 * A Set/Add/ArraySet payload carries a whole element rather than a position
 * in the document, and the wire format carries `insPrevID`/`insNextID` on
 * every tree node it holds. Such a payload is client-supplied and its trees
 * are always freshly created by the editing client, so none of their nodes
 * can be a split product — but the tree follows the links as trusted
 * structural pointers all the same. Drop them, for the same reason the edit
 * content decode path drops them.
 *
 * Both ways into the document have to agree: the converter calls this on
 * the element bytes it decodes, and `executeUndoRedo` calls it on the copy
 * a reverse operation captured, which never passes the converter on the
 * replica that runs the undo. Removed members are walked too — they are
 * still reachable through [CrdtContainer.getDescendants]. Port JS SDK
 * `split_links.ts` `dropSplitLinksInElement` (c8928853, #1375).
 */
internal fun dropSplitLinksInElement(elem: CrdtElement): CrdtElement {
    if (elem is CrdtTree) {
        elem.root.dropSplitLinks()
        return elem
    }

    if (elem is CrdtContainer) {
        elem.getDescendants { child, _ ->
            if (child is CrdtTree) {
                child.root.dropSplitLinks()
            }
            false
        }
    }

    return elem
}
