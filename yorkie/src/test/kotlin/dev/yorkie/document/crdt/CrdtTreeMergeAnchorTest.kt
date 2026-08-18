package dev.yorkie.document.crdt

import dev.yorkie.api.toByteString
import dev.yorkie.api.toCrdtTree
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import dev.yorkie.issueTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `c5d5c851` (tombstones move with merge) and `b2e66114` (flatten
 * chained merge) as JVM unit tests (AC1-AC4).
 */
class CrdtTreeMergeAnchorTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"
    private val actor3 = "000000000000000000000003"

    private fun issuePos(offset: Int = 0) = CrdtTreeNodeID(issueTime(), offset)

    private fun CrdtTreeNode.toList() = listOf(this)

    private fun CrdtTree.edit(range: Pair<Int, Int>, nodes: List<CrdtTreeNode>?) {
        val fromPos = findPos(range.first)
        val toPos = findPos(range.second)
        edit(fromPos to toPos, nodes, 0, issueTime(), ::issueTime)
    }

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    /**
     * Three-way broadcast sync for [d1]/[d2]/[d3]: unlike chaining pairwise
     * [crossSync] calls (which acks — and so permanently drains — each
     * side's pending local changes on every call, so a change relayed to an
     * intermediate is never available for that intermediate to forward to a
     * third party), this captures every doc's pending changes BEFORE
     * applying or acking any of them, then broadcasts each to the other two.
     * Mirrors what a real shared server would do for a `sync()` from three
     * attached clients.
     */
    private suspend fun threeWaySync(
        d1: Document,
        d2: Document,
        d3: Document,
    ) {
        val pack1 = d1.createChangePack()
        val pack2 = d2.createChangePack()
        val pack3 = d3.createChangePack()

        suspend fun applyFrom(
            target: Document,
            source: Document,
            pack: ChangePack,
        ) {
            target.applyChangePack(
                ChangePack(
                    source.getKey(),
                    CheckPoint.InitialCheckPoint,
                    pack.changes,
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }

        applyFrom(d1, d2, pack2)
        applyFrom(d1, d3, pack3)
        applyFrom(d2, d1, pack1)
        applyFrom(d2, d3, pack3)
        applyFrom(d3, d1, pack1)
        applyFrom(d3, d2, pack2)

        d1.applyChangePack(
            ChangePack(
                d1.getKey(),
                CheckPoint(0, pack1.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        d2.applyChangePack(
            ChangePack(
                d2.getKey(),
                CheckPoint(0, pack2.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        d3.applyChangePack(
            ChangePack(
                d3.getKey(),
                CheckPoint(0, pack3.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    /**
     * The runtime tree's node list, in postorder, as `[toXML, visibleSize,
     * isRemoved]` tuples — the per-node fingerprint that must match a
     * snapshot round-trip rebuild (AC3).
     */
    private fun CrdtTree.nodeFingerprints(): List<Triple<String, Int, Boolean>> = buildList {
        indexTree.traverseAll { node, _ ->
            add(Triple(node.toXml(), node.visibleSize, node.isRemoved))
        }
    }

    // AC1: port c5d5c851 tree_test.ts 'merge moves an element tombstone with
    // correct length accounting'.
    @Test
    fun `merge moves an element tombstone with correct length accounting`() {
        // 01. Create <root><p>ab</p><p><b></b>cd</p></root>.
        //       0   1 2 3    4   5   6    7 8 9    10
        // <root> <p> a b </p> <p> <b> </b> c d </p>  </root>
        val tree = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())
        tree.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        tree.edit(4 to 4, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(5 to 5, CrdtTreeElement(issuePos(), "b").toList())
        tree.edit(7 to 7, CrdtTreeText(issuePos(), "cd").toList())
        assertEquals("<root><p>ab</p><p><b></b>cd</p></root>", tree.toXml())

        // 02. Delete b, the second paragraph's open tag, the <b></b> element
        // and c, merging the paragraph. The <b></b> element becomes a
        // tombstone moved into the first paragraph. Its padding must not
        // inflate the visible size of the (surviving) first paragraph.
        tree.edit(2 to 8, null)
        assertEquals("<root><p>ad</p></root>", tree.toXml())

        assertEquals(4, tree.root.visibleSize)
        assertEquals(2, tree.root.children[0].visibleSize)
    }

    // AC2 (case 1): port tree_merge_anchor_test.ts 'converges when inserting
    // into a concurrently removed range'.
    @Test
    fun `converges when inserting into a concurrently removed range`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 6, element("p")) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 6) }.await()
        crossSync(d1, d2)

        assertEquals("<r><p></p>d</r>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC2 (case 2): port tree_merge_anchor_test.ts 'converges with two
    // inserts at the same anchor in a removed range' (RGA createdAt
    // tie-break).
    @Test
    fun `converges with two inserts at the same anchor in a removed range`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        val d3 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d3.setActor(actor3)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        threeWaySync(d1, d2, d3)

        // c1 and c2 insert distinct elements at the same index 6; c3 removes
        // the range [0, 6) that the inserts anchor into.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 6, element("i")) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 6, element("b")) }.await()
        d3.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 6) }.await()

        threeWaySync(d1, d2, d3)

        assertEquals(d1.toJson(), d2.toJson())
        assertEquals(d2.toJson(), d3.toJson())
    }

    // AC3: port tree_merge_anchor_test.ts 'converges when insert is anchored
    // in a chained merge' (P->Q->R) + snapshot round-trip.
    @Test
    fun `converges when insert is anchored in a chained merge`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        val d3 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d3.setActor(actor3)

        // Initial: <r><p>ab</p><p>cd</p><p>ef</p></r>.
        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                    element("p") { text { "ef" } }
                },
            )
        }.await()
        threeWaySync(d1, d2, d3)
        assertEquals(
            "<r><p>ab</p><p>cd</p><p>ef</p></r>",
            d1.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals(
            "<r><p>ab</p><p>cd</p><p>ef</p></r>",
            d3.getRoot().getAs<JsonTree>("t").toXml(),
        )

        // d1 merges p2 into p1: edit(2, 6) removes b, </p>, <p>, c.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 6) }.await()
        // d2 merges p3 into p2: edit(6, 10) removes d, </p>, <p>, e.
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 10) }.await()
        // d3 inserts <x> at the left-most position of p3: edit(9, 9).
        d3.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(9, 9, element("x")) }.await()

        threeWaySync(d1, d2, d3)

        // The insert must survive on every replica: <x> is anchored before
        // e, so it lands between the surviving a and f in the merge target.
        assertEquals("<r><p>a<x></x>f</p></r>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(d1.toJson(), d2.toJson())
        assertEquals(d1.toJson(), d3.toJson())

        // Snapshot round-trip: a fresh client loading the post-chained-merge
        // snapshot must reconstruct the identical tree (no resurrected
        // tombstone, no lost insert).
        val runtimeTree = d1.crdtTree()
        val snapshotTree = runtimeTree.toByteString().toCrdtTree()
        assertEquals(runtimeTree.nodeFingerprints(), snapshotTree.nodeFingerprints())
    }

    // AC4: resolveMergeTarget's cycle guard must terminate rather than loop
    // forever when a concurrent mutual merge (constructed here directly,
    // since organically reproducing the exact concurrent interleaving is not
    // deterministic) leaves two tombstones forwarding into each other. This
    // edit is a fully collapsed, content-less no-op EXCEPT for the
    // resolveMergeTarget(fromParent) call every edit() performs up front —
    // without the `seen`-set guard this test would hang instead of failing.
    @Test
    fun `resolveMergeTarget cycle guard terminates for a concurrent mutual merge`() {
        val tree = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())
        tree.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(2 to 2, CrdtTreeElement(issuePos(), "p").toList())
        val p = tree.root.children[0]
        val q = tree.root.children[1]

        // Force a pathological mutual-forwarding cycle between two
        // tombstones — the shape resolveMergeTarget's `seen` set exists to
        // survive, regardless of how it is reached in practice.
        p.remove(issueTime())
        q.remove(issueTime())
        p.mergedInto = q.id
        q.mergedInto = p.id

        // A raw CrdtTreePos addressing p directly (as a concurrent remote op
        // built before the tombstoning would) resolves fromParent = p
        // without redirecting away from it: p's forwarding target (q) is
        // also removed, so the insertion-boundary redirect does not fire.
        val posAtP = CrdtTreePos(p.id, p.id)
        tree.edit(posAtP to posAtP, null, 0, issueTime(), ::issueTime)

        // The probe edit is a true no-op: the cycle is undisturbed and the
        // tree is unchanged (both tombstoned by this test's own setup above,
        // hence invisible in the XML view), proving the call returned rather
        // than hanging.
        assertEquals(q.id, p.mergedInto)
        assertEquals(p.id, q.mergedInto)
        assertEquals("<root></root>", tree.toXml())
    }

    // AC4: an intermediate that only relays another source's children (Q,
    // built empty, receiving R's children via a first merge) keeps
    // mergedInto unset — both at runtime and after a snapshot rebuild —
    // because mergedInto is derived solely from a moved child, and no child
    // in this scenario was ever originally Q's own.
    @Test
    fun `relay-only intermediate keeps mergedInto unset on both runtime and rebuild paths`() {
        // <root><p>a</p><p></p><p>b</p></root>: P, Q (empty), R.
        val tree = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())
        tree.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(1 to 1, CrdtTreeText(issuePos(), "a").toList())
        tree.edit(3 to 3, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(5 to 5, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(6 to 6, CrdtTreeText(issuePos(), "b").toList())
        assertEquals("<root><p>a</p><p></p><p>b</p></root>", tree.toXml())
        val p = tree.root.children[0]
        val q = tree.root.children[1]
        val r = tree.root.children[2]

        // First merge: R into Q (dest = Q, live). R's child "b" moves into Q.
        tree.edit(4 to 6, null)
        assertEquals("<root><p>a</p><p>b</p></root>", tree.toXml())
        assertEquals(q.id, r.mergedInto)

        // Second merge: Q into P (dest = P, live). Q contributes no child of
        // its own — "b"'s mergedFrom is already R (stamped once) — so only
        // R's forwarding pointer is path-compressed onto P; Q's own
        // mergedInto is never set.
        tree.edit(2 to 4, null)
        assertEquals("<root><p>ab</p></root>", tree.toXml())

        assertEquals(p.id, r.mergedInto)
        assertNull(q.mergedInto)

        // Snapshot rebuild derives the same forwarding from mergedFrom alone.
        val rebuilt = tree.toByteString().toCrdtTree()
        val rebuiltR = rebuilt.findFloorNode(r.id)
        val rebuiltQ = rebuilt.findFloorNode(q.id)
        assertEquals(p.id, rebuiltR?.mergedInto)
        assertNull(rebuiltQ?.mergedInto)
    }
}
