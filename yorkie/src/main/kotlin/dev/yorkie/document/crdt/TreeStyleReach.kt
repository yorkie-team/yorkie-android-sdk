package dev.yorkie.document.crdt

import dev.yorkie.document.time.VersionVector
import dev.yorkie.document.time.ticketKnown
import dev.yorkie.util.TokenType
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Answers, for one change, the two questions [CrdtTree.styleTargets] asks of
 * every node it visits: is this node the element a position declared as its
 * parent (or an ancestor of it), matched through the split lineage.
 *
 * Both questions used to be answered per node by walking the declared
 * parent's whole current ancestry and, for each ancestor, its [splitOriginsOf]
 * chain — pointers a peer controls, so one style message over a wide range
 * bought O(range x depth x chain) work, with a fresh cycle-guard set per
 * step, on every replica that applied it. Nothing in those walks depends on
 * the node being asked about, so they run once per change here (see
 * [declaredLineageOf]): [covers] is then a set lookup, and
 * [coversEitherWay]'s one remaining direction is memoized per node, so each
 * node costs its own chain at most once. Port JS SDK `tree.ts`
 * `DeclaredLineage` (b69169c0, #1404).
 */
internal class DeclaredLineage(
    /**
     * Reports whether a node is one of the declared ancestors, or a piece a
     * split cut off one of them.
     */
    val covers: (node: CrdtTreeNode) -> Boolean,
    /**
     * [covers] plus the opposite direction: it also matches when a declared
     * ancestor is a piece a split cut off the node being asked about.
     */
    val coversEitherWay: (node: CrdtTreeNode) -> Boolean,
)

/**
 * An identity-keyed, not structurally-keyed, mutable set of tree nodes (D6):
 * [CrdtTreeNode] is a data class whose generated `equals`/`hashCode`
 * recurses over `childNodes`/attributes, which would turn every membership
 * check below into a deep comparison on a hot path — the same reason
 * [CrdtTree.resolveMergeTarget] uses one.
 */
private fun identityNodeSetOf(): MutableSet<CrdtTreeNode> =
    Collections.newSetFromMap(IdentityHashMap())

/**
 * Returns the element [pos] named as its parent, or null when this replica
 * cannot [resolve] either id the position carries. Port JS SDK `tree.ts`
 * `declaredParentOf` (b69169c0, #1404).
 */
internal fun declaredParentOf(
    pos: CrdtTreePos,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
): CrdtTreeNode? {
    val parent = resolve(pos.parentID) ?: return null
    resolve(pos.leftSiblingID) ?: return null
    return parent
}

/**
 * Walks back along [CrdtTreeNode.insPrevID] from [node], feeding each
 * element it passes to [visit] and stopping when [visit] returns false.
 * Only [CrdtTreeNode.split] sets `insPrevID` on an element, so the chain is
 * exactly the split lineage; [InsNextWalker] stops a crafted chain that
 * loops back on itself, and a text link cuts it. Port JS SDK `tree.ts`
 * `splitOriginsOf` (b69169c0, #1404).
 */
internal fun splitOriginsOf(
    node: CrdtTreeNode,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
    visit: (CrdtTreeNode) -> Boolean,
) {
    val walker = InsNextWalker()
    var current = node
    walker.visit(current)
    while (visit(current)) {
        val insPrevID = current.insPrevID ?: return
        val prev = resolve(insPrevID) ?: return
        if (prev.isText || !walker.visit(prev)) return
        current = prev
    }
}

/**
 * Resolves, once for a change, everything [DeclaredLineage] answers about
 * the element [declaredParent] a position declared as its parent: the
 * element's current ancestry, and the split lineage of each ancestor. Port
 * JS SDK `tree.ts` `declaredLineageOf` (b69169c0, #1404).
 */
internal fun declaredLineageOf(
    declaredParent: CrdtTreeNode,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
): DeclaredLineage {
    // Every element a declared ancestor was split from. A chain already
    // walked needs no second walk because the chain back from an element is
    // fixed.
    val ancestors = identityNodeSetOf()
    val products = identityNodeSetOf()
    var ancestor: CrdtTreeNode? = declaredParent
    while (ancestor != null) {
        ancestors.add(ancestor)
        splitOriginsOf(ancestor, resolve) { origin ->
            if (origin in products) {
                false
            } else {
                products.add(origin)
                true
            }
        }
        ancestor = ancestor.parent
    }

    // The opposite direction -- a declared ancestor is a piece split off the
    // node being asked about -- cannot be precomputed, since it starts from
    // that node. Memoize it instead: the answer for a node is the answer
    // for every node whose chain runs through it, so one walk settles the
    // whole prefix it passed. Identity-keyed (D6): same reason as above.
    val reversed = IdentityHashMap<CrdtTreeNode, Boolean>()
    fun reaches(node: CrdtTreeNode): Boolean {
        reversed[node]?.let { return it }
        val walked = mutableListOf<CrdtTreeNode>()
        var found = false
        splitOriginsOf(node, resolve) { origin ->
            val memo = reversed[origin]
            when {
                origin in ancestors -> {
                    found = true
                    false
                }
                memo != null -> {
                    found = memo
                    false
                }
                else -> {
                    walked.add(origin)
                    true
                }
            }
        }
        walked.forEach { reversed[it] = found }
        return found
    }

    return DeclaredLineage(
        covers = { node -> node in products },
        coversEitherWay = { node -> node in products || reaches(node) },
    )
}

/**
 * Walks the CURRENT ancestry of [declaredParent], up to the root, and
 * reports whether any of them [match]es. Port JS SDK `tree.ts`
 * `declaredAncestryHas` (b69169c0, #1404).
 */
internal fun declaredAncestryHas(
    declaredParent: CrdtTreeNode,
    match: (CrdtTreeNode) -> Boolean,
): Boolean {
    var current: CrdtTreeNode? = declaredParent
    while (current != null) {
        if (match(current)) return true
        current = current.parent
    }
    return false
}

/**
 * [declaredLineageOf] for the element [pos] named as its parent, or null
 * when this replica cannot resolve the position. Port JS SDK `tree.ts`
 * `declaredLineage` (b69169c0, #1404).
 */
internal fun declaredLineage(
    pos: CrdtTreePos,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
): DeclaredLineage? {
    val declaredParent = declaredParentOf(pos, resolve) ?: return null
    return declaredLineageOf(declaredParent, resolve)
}

/**
 * Returns the elements a split concurrent with the change produced from
 * [node]: its [CrdtTreeNode.insNextID] chain, stopped at the first sibling
 * the change already knew about. A style that covered [node] covered these
 * too, because the change resolved its range while [node] was still one
 * piece. Port JS SDK `tree.ts` `unknownSplitSiblings` (b69169c0, #1404).
 */
internal fun unknownSplitSiblings(
    node: CrdtTreeNode,
    versionVector: VersionVector?,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
): List<CrdtTreeNode> {
    val siblings = mutableListOf<CrdtTreeNode>()
    val walker = InsNextWalker()
    var current = node
    walker.visit(current)
    while (true) {
        val nextID = current.insNextID ?: break
        val next = resolve(nextID) ?: break
        if (next.isText) break
        if (!walker.visit(next)) break
        if (ticketKnown(versionVector, next.id.createdAt)) break
        siblings.add(next)
        current = next
    }
    return siblings
}

/**
 * Walks back along [CrdtTreeNode.insPrevID] from an element whose creation
 * the change did not know, collecting the products a concurrent split made
 * of a node it did know. Returns the family in document order, that known
 * node first, or an empty list when the chain reaches no such node — a node
 * simply new to the change. Port JS SDK `tree.ts` `splitFamilyOf` (b69169c0,
 * #1404).
 */
internal fun splitFamilyOf(
    node: CrdtTreeNode,
    versionVector: VersionVector?,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
): List<CrdtTreeNode> {
    val reversed = mutableListOf(node)
    val walker = InsNextWalker()
    var current = node
    walker.visit(current)
    while (true) {
        val insPrevID = current.insPrevID ?: return emptyList()
        val prev = resolve(insPrevID) ?: return emptyList()
        if (prev.isText || !walker.visit(prev)) return emptyList()
        reversed.add(prev)
        if (prev.canStyle(versionVector)) return reversed.reversed()
        current = prev
    }
}

/**
 * Reports whether the element a position named as its parent, or any
 * ancestor of it, has been removed — the only way a range declared forwards
 * can resolve backwards in [CrdtTree.boundaryRangeCovers]. Port JS SDK
 * `tree.ts` `declaredAncestryRemoved` (b69169c0, #1404).
 */
internal fun declaredAncestryRemoved(
    pos: CrdtTreePos,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
): Boolean {
    val parent = declaredParentOf(pos, resolve) ?: return false
    return declaredAncestryHas(parent) { it.isRemoved }
}

/**
 * Returns the elements the change reached through a single token on the
 * replica that issued it: the ancestors of the range-start position, whose
 * End tokens the range ran past, and the ancestors of the range-end
 * position, whose Start tokens it ran past — each chain stopping below the
 * common ancestor of the two. Each element is returned with the token the
 * range ran past, so the caller can put it through the same per-token skip
 * checks the traversal applies.
 *
 * These come from the positions the change carries, which never move, so
 * every replica computes the same set whatever a concurrent merge did to
 * the index space. Port JS SDK `tree.ts` `boundaryElements` (b69169c0,
 * #1404).
 */
internal fun boundaryElements(
    from: CrdtTreePos,
    to: CrdtTreePos,
    resolve: (CrdtTreeNodeID) -> CrdtTreeNode?,
): List<CrdtTreeToken> {
    val fromParent = declaredParentOf(from, resolve) ?: return emptyList()
    val toParent = declaredParentOf(to, resolve) ?: return emptyList()

    val fromAncestors = identityNodeSetOf()
    var current: CrdtTreeNode? = fromParent
    while (current != null) {
        fromAncestors.add(current)
        current = current.parent
    }

    // The common ancestor is the first ancestor of the range-end position
    // that also sits above the range-start position.
    var common: CrdtTreeNode? = null
    val startTokens = mutableListOf<CrdtTreeToken>()
    current = toParent
    while (current != null) {
        if (current in fromAncestors) {
            common = current
            break
        }
        startTokens.add(CrdtTreeToken(current, TokenType.Start))
        current = current.parent
    }
    common ?: return emptyList()

    val elements = mutableListOf<CrdtTreeToken>()
    current = fromParent
    while (current != null && current !== common) {
        elements.add(CrdtTreeToken(current, TokenType.End))
        current = current.parent
    }
    return elements + startTokens
}
