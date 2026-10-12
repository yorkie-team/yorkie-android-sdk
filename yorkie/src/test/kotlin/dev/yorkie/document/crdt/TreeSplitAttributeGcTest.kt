package dev.yorkie.document.crdt

import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `gc_attr_split_test.ts` (yorkie-js-sdk v0.7.23, `99dbec9d`, #1363,
 * server twin yorkie#2005) as JVM unit tests.
 *
 * `CrdtTreeNode.split` deep-copies the node's RHT via `clone()` — tombstones
 * included, which it must: two replicas that apply the split and a
 * concurrent style in a different order still need to resolve against the
 * same attribute state on both halves. The copy was never registered for GC,
 * so it rode along unreachable until a snapshot reload re-scanned it
 * (pinned, until this fix, by
 * `TreeUpstreamDefectPinTest."known upstream defect - copied attribute
 * tombstones are never swept"`, which covers the UNRELATED recreate-path
 * leak and stays pinned).
 */
class TreeSplitAttributeGcTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private suspend fun Document.xml(key: String = "t"): String =
        getRoot().getAs<JsonTree>(key).toXml()

    /**
     * `<doc><p><span>abcdefghij</span></p></doc>` with `span` styled
     * `color: red` then immediately un-styled — leaving one tombstoned
     * `RhtNode` on `span`'s Rht.
     */
    private suspend fun styledAndRemoved(actor: String = actor1): Document {
        val document = Document("test-doc")
        document.setActor(actor)
        document.updateAsync { root, _ ->
            root.setNewTree(
                key = "t",
                initialRoot = element("doc") {
                    element("p") {
                        element("span") { text { "abcdefghij" } }
                    }
                },
            )
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t")
                .styleByPath(listOf(0, 0), listOf(0, 1), mapOf("color" to "red"))
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t")
                .removeStyleByPath(listOf(0, 0), listOf(0, 1), listOf("color"))
        }.await()
        return document
    }

    /**
     * A document's garbage is a function of its content: a root rebuilt from
     * that content (as a client joining the document would hold) must report
     * the same GC count and the same `docSize.gc`.
     */
    private fun assertRebuildsSame(document: Document, msg: String) {
        val rebuilt = CrdtRoot(document.getRootObject().deepCopy())
        assertEquals(document.getDocSize().gc, rebuilt.docSize.gc, "$msg: gc")
        assertEquals(document.garbageLength, rebuilt.garbageLength, "$msg: count")
    }

    @Test
    fun `counts and collects the tombstone a tree split copied`() = runTest {
        val document = styledAndRemoved()
        assertEquals(1, document.garbageLength)
        assertRebuildsSame(document, "before the split")

        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
        }.await()

        assertEquals("<doc><p><span>a</span><span>bcdefghij</span></p></doc>", document.xml())
        assertEquals(2, document.garbageLength)
        assertRebuildsSame(document, "after the split")

        assertEquals(2, document.garbageCollect(maxVectorOf(listOf(actor1))))
        assertEquals(0, document.garbageLength)
        assertEquals(DataSize(data = 0, meta = 0), document.getDocSize().gc)
        assertRebuildsSame(document, "after collecting")
    }

    @Test
    fun `counts a tombstone the second split copied from the first copy`() = runTest {
        val document = styledAndRemoved()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 1, 4), listOf(0, 1, 4), splitLevel = 1)
        }.await()

        assertEquals(3, document.garbageLength)
        assertRebuildsSame(document, "after splitting a split")

        assertEquals(3, document.garbageCollect(maxVectorOf(listOf(actor1))))
        assertEquals(DataSize(data = 0, meta = 0), document.getDocSize().gc)
    }

    @Test
    fun `drains when a later style revives the key on both halves`() = runTest {
        val document = styledAndRemoved()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
        }.await()
        assertEquals(2, document.garbageLength)

        // Re-setting the key supersedes both tombstones. Each un-registers
        // against its own parent (identity-keyed gcPairMap, CrdtRoot.kt):
        // keying on the child alone (the pre-fix JS shape) would have had the
        // second registration re-add the entry the first removed.
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t")
                .styleByPath(listOf(0, 0), listOf(0, 2), mapOf("color" to "blue"))
        }.await()
        assertEquals(0, document.garbageLength)
    }

    // Cross-replica pairing: two replicas converge on the split-copied
    // tombstone count and docSize, and purge symmetrically.
    @Test
    fun `purges the same tree tombstones on both replicas`() = runTest {
        val d1 = styledAndRemoved(actor1)
        val d2 = Document("test-doc")
        d2.setActor(actor2)
        crossSync(d1, d2)

        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
        }.await()
        crossSync(d1, d2)

        assertEquals(d1.xml(), d2.xml())
        // Both replicas leaked the copy before the fix, so agreeing with each
        // other is not enough — name the count they must agree on.
        assertEquals(2, d1.garbageLength)
        assertEquals(2, d2.garbageLength)
        assertEquals(d1.getDocSize(), d2.getDocSize())

        val vector = maxVectorOf(listOf(actor1, actor2))
        val purged1 = d1.garbageCollect(vector)
        val purged2 = d2.garbageCollect(vector)
        assertEquals(purged1, purged2)
        assertEquals(DataSize(data = 0, meta = 0), d1.getDocSize().gc)
        assertEquals(DataSize(data = 0, meta = 0), d2.getDocSize().gc)
    }
}
