package dev.yorkie.document.crdt

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
 * Clears the merge lineage ([CrdtTreeNode.mergedFrom]/[CrdtTreeNode.mergedAt]/
 * [CrdtTreeNode.mergedInto]) on this node and every one of its descendants
 * (tombstones included).
 *
 * Unlike [dropSplitLinks] this is NOT safe to call on an arbitrary tree: a
 * tree that really was merged carries the lineage as state, and dropping it
 * would rewrite the document. It is for a *tree edit's* content, which the
 * editing client always creates fresh for that one operation, so none of its
 * nodes can be a merge product. A peer is free to stamp them anyway, and
 * [CrdtTree.declaredBoundaries] then follows a stamped [CrdtTreeNode.mergedFrom]
 * off the freshly inserted node into an element the position never named.
 * Clearing them costs nothing on conforming traffic -- no producer sets them
 * on edit content, here or in yorkie -- so no replica sees a different tree
 * for a change a client could legitimately have written. Port JS SDK
 * `tree.ts` `CRDTTreeNode.dropMergeStamps` (b69169c0, #1404; spec 035 unit 1E).
 */
internal fun CrdtTreeNode.dropMergeStamps() {
    traverseAllPreorder(this) { node, _ ->
        node.mergedFrom = null
        node.mergedAt = null
        node.mergedInto = null
    }
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
