package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.exchange
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_split_order_test.ts` (JS SDK c8928853, #1375) as JVM
 * in-process convergence tests (spec 033 AC1).
 *
 * Two replicas splitting one element at the same boundary each apply their
 * own split first, and [CrdtTreeNode.split] places its product directly
 * after the node it splits -- so without an ordering rule the two products
 * land in arrival order, which differs per replica. XML and `toSortedJSON`
 * happen to match (all but the last product are empty), but a later
 * position-based operation sees the difference: a range delete over the
 * root then leaves an empty node on one replica for good, and the empty
 * node between two concurrent splits of one span carries different
 * attributes on each replica. `orderSameBoundarySplit` resolves this by
 * ordering same-boundary split products by ticket, newest first, as RGA
 * orders concurrent inserts after the same node.
 *
 * Not ported: the 4 JS `it.skipIf(known)` cases ("the older actor splits
 * the paragraph" / "the older actor presses Enter as two edits", each run
 * for both older replicas) diverge upstream too, per that test file's own
 * `known` strings -- named here rather than silently dropped.
 */
class TreeSplitOrderTest {

    private val actorIDs = listOf(
        "000000000000000000000001",
        "000000000000000000000002",
        "000000000000000000000003",
    )

    private fun JsonObject.tree() = getAs<JsonTree>("t")

    /** [n] replicas seeded with `<doc><p><span>abcde</span></p></doc>`, already synced from replica 0. */
    private suspend fun replicas(n: Int): List<Document> {
        val docs = (0 until n).map { i -> Document("test-doc").apply { setActor(actorIDs[i]) } }
        docs[0].updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("span") {
                            text { "abcde" }
                        }
                    }
                },
            )
        }.await()
        exchange(
            docs,
            docs.indices.map { i -> if (i == 0) emptyList() else listOf(0) },
            overWire = true,
        )
        return docs
    }

    /**
     * Renders the tree under [key], tombstones included -- XML cannot tell
     * two empty `<p>`s apart; this can, so it is what the convergence
     * checks below compare.
     */
    private fun treeShape(doc: Document, key: String = "t"): String {
        fun walk(node: CrdtTreeNode): String {
            val removed = if (node.isRemoved) "x" else ""
            return if (node.isText) {
                "${node.id}$removed\"${node.value}\""
            } else {
                val children = node.allChildren.joinToString(",", transform = ::walk)
                "${node.type}#${node.id}$removed[$children]"
            }
        }
        return walk((doc.getRootObject()[key] as CrdtTree).root)
    }

    /**
     * Applies [split] concurrently on a fresh 2-doc pair, exchanges once,
     * asserts XML and node-identity convergence, then range-deletes the
     * whole root from replica 0, exchanges again, and asserts both replicas
     * are left with an empty `<doc></doc>` -- the position-based operation
     * that the arrival-order bug leaves diverged.
     */
    private suspend fun assertTwoReplicaConvergesThenRangeDeletes(
        split: suspend (JsonTree) -> Unit,
    ) {
        val docs = replicas(2)
        docs.forEach { doc -> doc.updateAsync { root, _ -> split(root.tree()) }.await() }
        exchange(docs, listOf(listOf(1), listOf(0)), overWire = true)

        assertEquals(docs[0].getRoot().tree().toXml(), docs[1].getRoot().tree().toXml())
        assertEquals(treeShape(docs[0]), treeShape(docs[1]))

        docs[0].updateAsync { root, _ ->
            val tree = root.tree()
            val rootChildCount = (tree.rootTreeNode as ElementNode).children.size
            tree.editByPath(listOf(0), listOf(rootChildCount))
        }.await()
        exchange(docs, listOf(listOf(1), listOf(0)), overWire = true)

        assertEquals("<doc></doc>", docs[0].getRoot().tree().toXml())
        assertEquals("<doc></doc>", docs[1].getRoot().tree().toXml())
    }

    /**
     * Applies [split] concurrently on all three replicas of a fresh 3-doc
     * set, then exchanges with each replica seeing the other two in a
     * different arrival order, and asserts all three converge on the same
     * node-identity shape.
     */
    private suspend fun assertThreeReplicaConverges(split: suspend (JsonTree) -> Unit) {
        val docs = replicas(3)
        docs.forEach { doc -> doc.updateAsync { root, _ -> split(root.tree()) }.await() }
        exchange(docs, listOf(listOf(2, 1), listOf(0, 2), listOf(1, 0)), overWire = true)

        val shape = treeShape(docs[0])
        assertEquals(shape, treeShape(docs[1]))
        assertEquals(shape, treeShape(docs[2]))
        assertTrue(docs[0].getRoot().tree().toXml().contains("abc"))
    }

    private val paragraphSplit: suspend (JsonTree) -> Unit = { t -> t.splitByPath(listOf(0, 1)) }
    private val spanSplit: suspend (JsonTree) -> Unit =
        { t -> t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1) }
    private val spanAndParagraphSplitInOneEdit: suspend (JsonTree) -> Unit =
        { t -> t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 2) }
    private val spanSplitThenParagraphSplitInSecondEdit: suspend (JsonTree) -> Unit = { t ->
        t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
        t.splitByPath(listOf(0, 1))
    }
    private val sameAtEndOfText: suspend (JsonTree) -> Unit = { t ->
        t.editByPath(listOf(0, 0, 5), listOf(0, 0, 5), splitLevel = 1)
        t.splitByPath(listOf(0, 1))
    }

    @Test
    fun `paragraph split - two replicas, and a range delete afterwards`() = runTest {
        assertTwoReplicaConvergesThenRangeDeletes(paragraphSplit)
    }

    @Test
    fun `paragraph split - three replicas, each in a different arrival order`() = runTest {
        assertThreeReplicaConverges(paragraphSplit)
    }

    @Test
    fun `span split - two replicas, and a range delete afterwards`() = runTest {
        assertTwoReplicaConvergesThenRangeDeletes(spanSplit)
    }

    @Test
    fun `span split - three replicas, each in a different arrival order`() = runTest {
        assertThreeReplicaConverges(spanSplit)
    }

    @Test
    fun `span and paragraph split in one edit - two replicas, and a range delete afterwards`() =
        runTest {
            assertTwoReplicaConvergesThenRangeDeletes(spanAndParagraphSplitInOneEdit)
        }

    @Test
    fun `span and paragraph split in one edit - three replicas, different arrival orders`() =
        runTest {
            assertThreeReplicaConverges(spanAndParagraphSplitInOneEdit)
        }

    @Test
    fun `span split then paragraph split - two replicas, and a range delete afterwards`() =
        runTest {
            assertTwoReplicaConvergesThenRangeDeletes(spanSplitThenParagraphSplitInSecondEdit)
        }

    @Test
    fun `span split then paragraph split - three replicas, different arrival orders`() = runTest {
        assertThreeReplicaConverges(spanSplitThenParagraphSplitInSecondEdit)
    }

    @Test
    fun `the same at the end of the text - two replicas, and a range delete afterwards`() =
        runTest {
            assertTwoReplicaConvergesThenRangeDeletes(sameAtEndOfText)
        }

    @Test
    fun `the same at the end of the text - three replicas, each in a different arrival order`() =
        runTest {
            assertThreeReplicaConverges(sameAtEndOfText)
        }

    @Test
    fun `the empty node between two halves carries the same attributes everywhere`() = runTest {
        val docs = replicas(2)
        docs[0].updateAsync { root, _ ->
            val t = root.tree()
            t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
            t.styleByPath(listOf(0, 0), mapOf("bold" to "true"))
        }.await()
        docs[1].updateAsync { root, _ ->
            val t = root.tree()
            t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
            t.styleByPath(listOf(0, 1), mapOf("italic" to "true"))
        }.await()
        exchange(docs, listOf(listOf(1), listOf(0)), overWire = true)

        assertEquals(docs[0].getRoot().tree().toXml(), docs[1].getRoot().tree().toXml())
    }

    /**
     * A same-boundary split that meets an older, already-known split
     * product of one of the two actors: [older] splits the span, both
     * replicas see it, then BOTH replicas split the very same (now
     * boundary-aligned) position again concurrently.
     */
    private suspend fun assertOlderActorSplitsSpanAgainConverges(older: Int) {
        val docs = replicas(2)
        val newer = 1 - older
        docs[older].updateAsync { root, _ -> spanSplit(root.tree()) }.await()
        exchange(docs, listOf(listOf(1), listOf(0)), overWire = true)

        docs[older].updateAsync { root, _ -> spanSplit(root.tree()) }.await()
        docs[newer].updateAsync { root, _ -> spanSplit(root.tree()) }.await()
        exchange(docs, listOf(listOf(1), listOf(0)), overWire = true)

        assertEquals(docs[0].getRoot().tree().toXml(), docs[1].getRoot().tree().toXml())
        assertEquals(treeShape(docs[0]), treeShape(docs[1]))
    }

    @Test
    fun `the older actor splits the span again, older split by replica 0`() = runTest {
        assertOlderActorSplitsSpanAgainConverges(older = 0)
    }

    @Test
    fun `the older actor splits the span again, older split by replica 1`() = runTest {
        assertOlderActorSplitsSpanAgainConverges(older = 1)
    }
}
