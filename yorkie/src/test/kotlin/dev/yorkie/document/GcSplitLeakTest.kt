package dev.yorkie.document

import dev.yorkie.api.toByteString
import dev.yorkie.api.toCrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.json.TreePosStructRange
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `gc_split_leak_test.ts` (JS SDK 4f1f0123, #1292) as JVM unit tests
 * (AC2, AC3, AC4, AC5, AC13-deepCopy).
 *
 * A piece split off an already-tombstoned node inherits `removedAt` without
 * ever passing through `remove()`, so before the fix it never registered a
 * GC pair: it lingered forever, invisible to `getGarbageLen()`, and
 * replicas that split a tombstone purged asymmetrically from replicas that
 * didn't.
 */
class GcSplitLeakTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun JsonTree.editRange(fromIndex: Int, toIndex: Int) = edit(fromIndex, toIndex)

    /**
     * Builds two replicas where d1 deletes "el" (splitting the text node
     * while live) and d2 concurrently deletes the whole `<p>` (tombstoning
     * it unsplit). When d1's delete arrives at d2, it splits d2's
     * tombstoned text node, creating pieces that are born already-removed.
     */
    private suspend fun buildTombstoneSplitReplicas(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                key = "t",
                initialRoot = element("doc") { element("p") { text { "hello" } } },
            )
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").editRange(2, 4) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").editRange(0, 7) }.await()
        crossSync(d1, d2)

        return d1 to d2
    }

    @Test
    fun `purges the same nodes on both replicas when a tombstone is split remotely`() = runTest {
        val (d1, d2) = buildTombstoneSplitReplicas()

        assertEquals("<doc></doc>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            d2.getRoot().getAs<JsonTree>("t").toXml(),
        )

        val vector = maxVectorOf(listOf(actor1, actor2))
        val purged1 = d1.garbageCollect(vector)
        val purged2 = d2.garbageCollect(vector)
        assertEquals(
            purged1,
            purged2,
            "asymmetric purge for identical state: d1=$purged1 d2=$purged2",
        )

        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)

        assertEquals(d2.getDocSize(), d1.getDocSize())
        assertEquals(DataSize(0, 0), d1.getDocSize().gc)
    }

    @Test
    fun `registers GC pairs for tree tombstones after snapshot round-trip`() = runTest {
        val (_, d2) = buildTombstoneSplitReplicas()

        // Snapshot-encode d2's root (tombstones included) and rebuild a root
        // from it, as a client receiving a snapshot would.
        val bytes = d2.getRootObject().toByteString()
        val rebuilt = CrdtRoot(bytes.toCrdtObject())

        assertTrue(d2.garbageLength > 0)
        assertEquals(d2.garbageLength, rebuilt.garbageLength)

        val vector = maxVectorOf(listOf(actor1, actor2))
        val purgedLive = d2.garbageCollect(vector)
        val purgedRebuilt = rebuilt.garbageCollect(vector)
        assertEquals(purgedLive, purgedRebuilt)
        assertEquals(0, rebuilt.garbageLength)

        // The rebuilt root counts tombstones straight into docSize.gc (they
        // were never in its docSize.live), so a full GC must drain gc to
        // zero without pushing live negative.
        assertEquals(DataSize(0, 0), rebuilt.docSize.gc)
        assertEquals(d2.getDocSize().live, rebuilt.docSize.live)
    }

    // Each range-conversion wrapper must independently drain the pending GC
    // pairs. Runs against its own freshly tombstoned tree.
    @Test
    fun `registers GC pairs split during posRangeToIndexRange`() = runTest {
        verifyReadPathGcRegistration { tree, range -> tree.posRangeToIndexRange(range) }
    }

    @Test
    fun `registers GC pairs split during posRangeToPathRange`() = runTest {
        verifyReadPathGcRegistration { tree, range -> tree.posRangeToPathRange(range) }
    }

    private suspend fun verifyReadPathGcRegistration(
        convert: (JsonTree, TreePosStructRange) -> Unit,
    ) {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                key = "t",
                initialRoot = element("doc") { element("p") { text { "hello" } } },
            )
        }.await()
        crossSync(d1, d2)

        // Capture a selection that points into the middle of "hello", as a
        // stored cursor/selection would.
        val selection = d1.getRoot().getAs<JsonTree>("t").indexRangeToPosRange(2 to 4)

        // A peer deletes the whole <p>, tombstoning "hello" on d1.
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").editRange(0, 7) }.await()
        crossSync(d1, d2)

        // Resolving the stored selection now lands inside the tombstoned
        // text and splits it — a read path that emits no operation. The
        // born-removed pieces must still be registered so a later GC can
        // purge them.
        convert(d1.getRoot().getAs<JsonTree>("t"), selection)

        d1.garbageCollect(maxVectorOf(listOf(actor1, actor2)))

        // After GC the clone tree (mutated by the read-path split) and the
        // document root must hold the same physical nodes.
        val cloneTree = requireNotNull(d1.clone).root.rootObject["t"] as CrdtTree
        val rootTree = d1.getRootObject()["t"] as CrdtTree
        assertEquals(rootTree.nodeSize, cloneTree.nodeSize)
        assertEquals(0, requireNotNull(d1.clone).root.garbageLength)
    }

    private fun countTextTombstones(document: Document): Int {
        val text = document.getRootObject()["k"] as CrdtText
        return text.rgaTreeSplit.count { it.isRemoved }
    }

    /**
     * Builds two in-process replicas that share a Text field seeded with
     * "abcdef" and already converged.
     */
    private suspend fun buildTextReplicas(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewText("k").edit(0, 0, "abcdef")
        }.await()
        crossSync(d1, d2)

        return d1 to d2
    }

    @Test
    fun `purges pieces split off a tombstoned text node`() = runTest {
        val (d1, d2) = buildTextReplicas()

        // d1 tombstones the whole node; d2 concurrently deletes a middle
        // slice. When d2's delete arrives at d1, it splits d1's tombstone
        // into three pieces; the piece after the deleted range is born dead.
        d1.updateAsync { root, _ -> root.getAs<JsonText>("k").edit(0, 6, "") }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonText>("k").edit(2, 4, "") }.await()
        crossSync(d1, d2)

        assertEquals("", d1.getRoot().getAs<JsonText>("k").toString())
        assertEquals("", d2.getRoot().getAs<JsonText>("k").toString())

        val vector = maxVectorOf(listOf(actor1, actor2))
        val purged1 = d1.garbageCollect(vector)
        val purged2 = d2.garbageCollect(vector)
        assertEquals(
            purged1,
            purged2,
            "asymmetric purge for identical state: d1=$purged1 d2=$purged2",
        )
        assertEquals(0, countTextTombstones(d1))
        assertEquals(0, countTextTombstones(d2))
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)

        assertEquals(d2.getDocSize(), d1.getDocSize())
        assertEquals(DataSize(0, 0), d1.getDocSize().gc)
    }

    @Test
    fun `keeps GC registration when a newer concurrent delete overwrites a tombstone`() = runTest {
        val (d1, d2) = buildTextReplicas()

        // Bump d1's lamport so its whole-range delete is newer than d2's
        // slice delete. When d1's delete arrives at d2, canRemove() allows
        // the LWW overwrite of d2's own tombstone; re-pushing a GC pair for
        // that node used to toggle-unregister it.
        d1.updateAsync { root, _ -> root.getAs<JsonText>("k").edit(6, 6, "!") }.await()
        d1.updateAsync { root, _ -> root.getAs<JsonText>("k").edit(0, 7, "") }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonText>("k").edit(2, 4, "") }.await()
        crossSync(d1, d2)

        assertEquals("", d1.getRoot().getAs<JsonText>("k").toString())
        assertEquals("", d2.getRoot().getAs<JsonText>("k").toString())

        val vector = maxVectorOf(listOf(actor1, actor2))
        val purged1 = d1.garbageCollect(vector)
        val purged2 = d2.garbageCollect(vector)
        assertEquals(
            purged1,
            purged2,
            "asymmetric purge for identical state: d1=$purged1 d2=$purged2",
        )
        assertEquals(0, countTextTombstones(d1))
        assertEquals(0, countTextTombstones(d2))
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)
    }

    @Test
    fun `deepCopy isolates pending GC pair buffers between source and clone`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                key = "t",
                initialRoot = element("doc") { element("p") { text { "hello" } } },
            )
        }.await()

        // Capture a position-based reference into "hello" while it is still
        // visible, then tombstone the whole <p> around it. The captured
        // reference still resolves into the tombstoned interior by node
        // identity (parentID/leftSiblingID), unlike an index.
        val posRange = document.getRoot().getAs<JsonTree>("t").indexRangeToPosRange(2 to 4)
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").editRange(0, 7) }.await()

        val sourceTree = document.getRootObject()["t"] as CrdtTree
        val copyTree = document.getRootObject().deepCopy()["t"] as CrdtTree

        val pos = posRange.first.toOriginal()

        // Split the COPY's tombstoned text node directly (bypassing the
        // auto-draining JsonTree wrapper) to buffer a pending pair there
        // only.
        copyTree.findNodesAndSplitText(pos)
        assertEquals(1, copyTree.drainPendingGcPairs().size)

        // The source's buffer must be unaffected — proving deepCopy does not
        // share the pendingGcPairs list between the two CrdtTree instances.
        assertEquals(0, sourceTree.drainPendingGcPairs().size)
    }

    // F8: CrdtTree's third born-tombstoned site (traverseAll's
    // fromParent.isRemoved branch) must pass gcOnlySize like its three
    // sibling sites, or docSize.live can go negative / diverge between
    // replicas that saw the concurrent insert-into-removed-parent case.
    @Test
    fun `docSize converges when a peer concurrently inserts into a removed paragraph`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                key = "t",
                initialRoot = element("doc") {
                    element("p") { text { "hello" } }
                    element("p") { text { "world" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d1 removes the first <p>; d2 concurrently inserts into it, unaware
        // it is being removed — the inserted content is born already-removed
        // once d1's remove is known.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").editRange(0, 7) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(3, 3, text { "X" }) }.await()
        crossSync(d1, d2)

        assertEquals(
            d2.getDocSize(),
            d1.getDocSize(),
            "docSize must converge even when the inserted content is born already-removed",
        )
    }

    // F10: a read-path conversion's split GC pair must register on the
    // authoritative root (Document.getDocSize()), not only on
    // Document.getRoot()'s snapshot clone — otherwise the size is invisible
    // until some unrelated future updateAsync happens to reuse and drain
    // that same cached clone.
    @Test
    fun `read-path GC pair is visible on getDocSize immediately, not only after garbageCollect`() =
        runTest {
            val d1 = Document("test-doc")
            val d2 = Document("test-doc")
            d1.setActor(actor1)
            d2.setActor(actor2)

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    key = "t",
                    initialRoot = element("doc") { element("p") { text { "hello" } } },
                )
            }.await()
            crossSync(d1, d2)

            val selection = d1.getRoot().getAs<JsonTree>("t").indexRangeToPosRange(2 to 4)

            // A peer deletes the whole <p>, tombstoning "hello" on d1.
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").editRange(0, 7) }.await()
            crossSync(d1, d2)

            val gcBefore = d1.getDocSize().gc

            // Resolving the stored selection lands inside the tombstoned text
            // and splits it — a read path that emits no operation.
            d1.getRoot().getAs<JsonTree>("t").posRangeToIndexRange(selection)

            assertNotEquals(
                gcBefore,
                d1.getDocSize().gc,
                "the split's GC pair must be visible on the authoritative getDocSize() " +
                    "immediately, not only via a later unrelated updateAsync",
            )
        }
}
