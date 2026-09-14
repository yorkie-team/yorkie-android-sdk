package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * PR #360 review 5165084872, findings F2 and F4 (spec 014).
 *
 * F2: an identity restore must not revive a node whose ancestor is still
 * tombstoned. `edit()` already applies the "born-dead" rule to inserts under
 * a removed parent; `restore()`/`recreateFromSpan` must apply the same rule,
 * or replicas whose GC timing differs diverge and `docSize.live` drifts for
 * the life of the document.
 *
 * F4: a recreated element deep-copies the span's attribute snapshot, tombstoned
 * `RhtNode`s included; those copies must be registered as GC pairs or they leak
 * forever.
 */
class TreeRestoreGcGuardTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    private suspend fun Document.xml(key: String = "t"): String =
        getRoot().getAs<JsonTree>(key).toXml()

    private fun identitySequence(tree: CrdtTree): List<CrdtTreeNodeID> = buildList {
        tree.indexTree.traverse { node, _ -> add(node.id) }
    }

    /** Two replicas both holding `<doc><p>hello</p></doc>`. */
    private suspend fun buildPair(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d1.updateAsync { root, _ ->
            root.setNewTree("t", element("doc") { element("p") { text { "hello" } } })
        }.await()
        crossSync(d1, d2)
        return d1 to d2
    }

    // F2-A — the reviewer's probe. d1 deletes "hello", d2 then deletes the
    // now-empty <p>; only d2 purges. d1's undo tries to revive "hello" under a
    // <p> that is still a tombstone on d1 and already purged on d2. Without
    // the removed-ancestor guard d1 shows <p>hello</p> while d2 shows <p></p>.
    @Test
    fun `undo does not revive a node under a still-tombstoned parent and replicas converge`() =
        runTest {
            val (d1, d2) = buildPair()

            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 6) }.await()
            crossSync(d1, d2)
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 2) }.await()
            crossSync(d1, d2)
            assertEquals("<doc></doc>", d1.xml())
            assertEquals(d1.xml(), d2.xml())

            // Only d2 reaches the GC threshold: a normal sync-timing window.
            d2.garbageCollect(maxVectorOf(listOf(actor1, actor2)))

            // d1's undo must be a no-op on visible state AND on accounting:
            // "hello"'s parent is a tombstone, so the node stays tombstoned
            // with its GC pair intact.
            val sizeBefore = d1.getDocSize()
            val garbageBefore = d1.garbageLength
            d1.history.undoAsync().await()
            assertEquals("<doc></doc>", d1.xml(), "a child under a tombstoned parent stays hidden")
            assertEquals(
                sizeBefore,
                d1.getDocSize(),
                "no size may move into live for a hidden node",
            )
            assertEquals(garbageBefore, d1.garbageLength, "the hidden node keeps its GC pair")
            crossSync(d1, d2)

            d2.history.undoAsync().await()
            crossSync(d1, d2)

            assertEquals("<doc><p></p></doc>", d1.xml())
            assertEquals(d1.xml(), d2.xml())
            assertEquals(identitySequence(d1.crdtTree()), identitySequence(d2.crdtTree()))

            // After both purge everything reclaimable, accounting is identical.
            val vector = maxVectorOf(listOf(actor1, actor2))
            d1.garbageCollect(vector)
            d2.garbageCollect(vector)
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)
            assertEquals(DataSize(0, 0), d1.getDocSize().gc)
            assertEquals(DataSize(0, 0), d2.getDocSize().gc)
            assertEquals(
                d1.getDocSize(),
                d2.getDocSize(),
                "converged replicas must agree on docSize",
            )
            assertEquals(d1.crdtTree().nodeSize, d2.crdtTree().nodeSize)
        }

    // F2-B — the recreate path. "hello" is purged on both replicas BEFORE <p>
    // is tombstoned, so d1's undo has to recreate "hello" from its span under
    // a parent that is still a tombstone. The recreated node must be born
    // dead (removed, gcOnlySize pair) exactly like edit()'s insert under a
    // removed parent.
    @Test
    fun `recreating a purged node under a tombstoned parent makes it a born-dead tombstone`() =
        runTest {
            val (d1, d2) = buildPair()
            val vector = maxVectorOf(listOf(actor1, actor2))

            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 6) }.await()
            crossSync(d1, d2)
            assertTrue(d1.garbageCollect(vector) > 0, "\"hello\" must be purged on d1")
            assertTrue(d2.garbageCollect(vector) > 0, "\"hello\" must be purged on d2")

            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 2) }.await()
            crossSync(d1, d2)
            assertEquals("<doc></doc>", d1.xml())

            // d1 undo: recreate "hello" under the tombstoned <p>.
            val sizeBefore = d1.getDocSize().live
            d1.history.undoAsync().await()
            assertEquals(
                "<doc></doc>",
                d1.xml(),
                "a recreated child under a tombstone stays hidden",
            )
            assertEquals(sizeBefore, d1.getDocSize().live, "a born-dead node never reaches live")
            crossSync(d1, d2)
            assertEquals(d1.xml(), d2.xml())

            // d2 undo revives <p>; the born-dead "hello" stays a tombstone.
            d2.history.undoAsync().await()
            crossSync(d1, d2)
            assertEquals("<doc><p></p></doc>", d1.xml())
            assertEquals(d1.xml(), d2.xml())
            assertEquals(identitySequence(d1.crdtTree()), identitySequence(d2.crdtTree()))

            // Exactly one pair is left on each side: the born-dead "hello".
            assertEquals(1, d1.garbageLength, "the recreated node must carry a GC pair")
            assertEquals(1, d2.garbageLength, "the recreated node must carry a GC pair")

            val purged1 = d1.garbageCollect(vector)
            val purged2 = d2.garbageCollect(vector)
            assertEquals(1, purged1)
            assertEquals(1, purged2)
            assertEquals(DataSize(0, 0), d1.getDocSize().gc)
            assertEquals(DataSize(0, 0), d2.getDocSize().gc)
            assertEquals(
                d1.getDocSize(),
                d2.getDocSize(),
                "converged replicas must agree on docSize",
            )
            assertEquals(d1.crdtTree().nodeSize, d2.crdtTree().nodeSize)
        }

    // F4 — removeStyle leaves a tombstoned attribute INSIDE <p>'s Rht (an
    // overwrite would not: Rht.set hands back a detached tombstone copy that
    // never enters the map — F12). Delete + purge removes <p> and that
    // tombstone; the undo recreates <p> from a span whose attrs snapshot was
    // taken before the purge and still carries it. The copy must be
    // registered as a GC pair so a later sweep can reclaim it.
    @Test
    fun `undo registers the attribute tombstones copied into a recreated element`() = runTest {
        val document = Document("test-doc")
        document.setActor(actor1)
        val vector = maxVectorOf(listOf(actor1))
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("doc") { element("p") { text { "x" } } })
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 3, mapOf("bold" to "a"))
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyle(0, 3, listOf("bold"))
        }.await()
        assertEquals("<doc><p>x</p></doc>", document.xml())
        assertEquals(1, document.garbageLength, "the removed attribute is a tombstone in the Rht")

        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 3) }.await()
        assertEquals("<doc></doc>", document.xml())
        assertTrue(document.garbageCollect(vector) >= 3, "<p>, \"x\" and the attribute are purged")
        assertEquals(0, document.garbageLength)

        document.history.undoAsync().await()
        assertEquals("<doc><p>x</p></doc>", document.xml())

        val recreated = document.crdtTree().indexTree.root.allChildren
            .single { !it.isRemoved && it.type == "p" }
        assertEquals(
            1,
            recreated.gcPairs.size,
            "the attrs snapshot carries the removed \"bold\" tombstone into the recreated node",
        )
        assertEquals(
            1,
            document.garbageLength,
            "the copied attribute tombstone must be registered, not leaked",
        )

        assertEquals(
            1,
            document.garbageCollect(vector),
            "the sweep reclaims the copied tombstone",
        )
        assertEquals(0, document.garbageLength)
        assertEquals(0, recreated.gcPairs.size, "purge removed the tombstone from the Rht")
        assertEquals(DataSize(0, 0), document.getDocSize().gc)
        assertEquals("<doc><p>x</p></doc>", document.xml())
    }
}
