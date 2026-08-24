package dev.yorkie.document

import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_style_moved_anchor_test.ts` (JS SDK 1c033ff5, mirrors
 * yorkie#1928) as JVM in-process cross-sync tests (AC7): a style/removeStyle
 * range whose declared end anchor sits inside a parent a concurrent merge
 * tombstoned must not leak onto content that merge moved into the merge
 * target on the applying replica.
 */
class TreeStyleMovedAnchorTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"
    private val actor3 = "000000000000000000000003"

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    private fun boldOf(node: CrdtTreeNode) = node.attributes["bold"]

    /** Finds the surviving (live) node of [type], ignoring tombstones. */
    private fun CrdtTree.findByType(type: String): CrdtTreeNode? {
        var found: CrdtTreeNode? = null
        indexTree.traverseAll { node, _ -> if (node.type == type && !node.isRemoved) found = node }
        return found
    }

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

    // AC7 (case 1, headline): style does not leak onto a sibling inserted
    // at the merge anchor while it never learned of the concurrent merge.
    @Test
    fun `style does not apply to a node inserted at the merge anchor`() = runTest {
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

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        assertNull(boldOf(requireNotNull(x)), "the interloper must not carry the concurrent style")
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 2): removeStyle leaves nothing on the interloper either —
    // same anchor shape, opposite attribute direction.
    @Test
    fun `removeStyle leaves nothing on a node inserted at the merge anchor`() = runTest {
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
        // Pre-existing bold on the whole document, synced first, so
        // removeStyle has something real to remove.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 9, mapOf("bold" to "x"))
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyle(0, 6, listOf("bold"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        assertNull(boldOf(requireNotNull(x)), "the interloper must carry no bold either way")
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 3): the styling client's OWN insert genuinely INSIDE the
    // declared range (not at the boundary) is still styled — the guard
    // must not over-fire on ordinary same-client content.
    @Test
    fun `own insert inside the styled range is still styled`() = runTest {
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

        // Insert a new element BEFORE the first paragraph — comfortably
        // inside [0,6) and untouched by d2's concurrent delete/merge of the
        // two paragraphs, so styling it is an ordinary case the guard must
        // not disturb.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 0, element("x")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        assertEquals("x", boldOf(requireNotNull(x)))
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 4): a subtree (element with a child) inserted at the merge
    // anchor is skipped as one unit — descendants included.
    @Test
    fun `interloper's descendants are skipped along with it`() = runTest {
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

        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(8, 8, element("x") { element("y") { } })
        }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        val y = tree1.findByType("y")
        assertNull(boldOf(requireNotNull(x)))
        assertNull(boldOf(requireNotNull(y)))
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 5): content already synced (known to both replicas) before
    // the concurrent merge is unaffected by the interloper filter — it is
    // simply ordinary, non-interloper content and stays styled.
    @Test
    fun `sibling synced before the merge is still styled`() = runTest {
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
        // Insert "x" at the merge anchor and sync it BEFORE the style/merge
        // race, so both replicas already know about it.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 6, fail-open): a node that reached the merge target via an
    // EARLIER, already-synced merge (so it carries a mergedFrom stamp) is
    // inside the styled range — it IS styled, since the guard fails open
    // on any stamp (stamp equality cannot prove range membership after a
    // chained merge).
    @Test
    fun `child from an earlier synced merge is still styled`() = runTest {
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
                    element("p") { text { "ef" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // Merge the SECOND paragraph into the first, and sync it, so "cd"
        // already carries mergedFrom by the time the next race starts —
        // fail-open must not treat already-known, already-stamped content
        // as an interloper of a LATER, unrelated merge.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(3, 5) }.await()
        crossSync(d1, d2)
        assertEquals("<r><p>abcd</p><p>ef</p></r>", d1.getRoot().getAs<JsonTree>("t").toXml())

        // Style the whole merged paragraph while d2 concurrently merges the
        // (now second) paragraph into it too.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "y"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(5, 7) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val cd = tree1.findByType("p")
        assertEquals("y", boldOf(requireNotNull(cd)))
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 7): three-client convergence — d3 concurrently inserts at
    // the same merge anchor while d1 styles and d2 merges; all three agree.
    @Test
    fun `three-client convergence on the merge anchor style race`() = runTest {
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

        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()
        d3.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()

        threeWaySync(d1, d2, d3)

        assertEquals(d1.toJson(), d2.toJson())
        assertEquals(d2.toJson(), d3.toJson())
    }
}
