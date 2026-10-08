package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.util.DataSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Ports JS `text_attr_ledger_test.ts` (v0.7.23), all 12 cases, plus
 * adversarial probes for the attribute-ledger accounting this change adds
 * (a style/removeStyle split's copied attribute tombstones, and a write
 * that loses LWW against an attribute tombstone). A running ledger
 * ([Document.getDocSize], [Document.garbageLength]) must always agree with
 * a from-scratch rebuild off a [deepCopy] of the live tree.
 */
class TextAttrLedgerTest {

    private fun rebuilt(d: Document) = CrdtRoot(d.getRootObject().deepCopy())

    private fun Document.text() = getRootObject()["k"] as CrdtText

    private fun assertLedgerExact(d: Document, msg: String) {
        val r = rebuilt(d)
        assertEquals(r.docSize.live, d.getDocSize().live, "$msg: live")
        assertEquals(r.docSize.gc, d.getDocSize().gc, "$msg: gc")
        assertEquals(r.garbageLength, d.garbageLength, "$msg: count")
    }

    private fun assertNotNegative(size: DataSize, msg: String) {
        assertTrue(size.data >= 0, "$msg: live data went negative")
        assertTrue(size.meta >= 0, "$msg: live meta went negative")
    }

    private suspend fun seededText(): Document {
        val d = Document("test-doc")
        d.updateAsync { r, _ -> r.setNewText("k").edit(0, 0, "abcdefghij") }.await()
        return d
    }

    private suspend fun seededTree(): Document {
        val d = Document("test-doc")
        d.updateAsync { r, _ ->
            r.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "abcd" } }
                    element("p") { text { "efgh" } }
                },
            )
        }.await()
        return d
    }

    // ---- JS text_attr_ledger_test.ts (v0.7.23), all 12 cases ----

    @Test
    fun `does not leak when a text attribute is overwritten`() = runTest {
        val d = seededText()
        for (v in listOf("1", "2", "3")) {
            d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to v)) }.await()
        }
        assertLedgerExact(d, "after three text overwrites")
    }

    @Test
    fun `does not leak when a tree attribute is overwritten`() = runTest {
        val d = seededTree()
        for (v in listOf("1", "2", "3")) {
            d.updateAsync { r, _ ->
                r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("b" to v))
            }.await()
        }
        assertLedgerExact(d, "after three tree overwrites")
    }

    @Test
    fun `moves a tombstoned text attribute out of live`() = runTest {
        val d = seededText()
        val before = d.getDocSize().live
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        d.history.undoAsync().await()
        assertEquals(1, d.garbageLength, "the tombstone is registered")
        assertEquals(before, d.getDocSize().live, "charged to gc and live at once")
        assertLedgerExact(d, "after undoing a text style")
    }

    @Test
    fun `does not strand a purged text attribute in live`() = runTest {
        val d = seededText()
        val before = d.getDocSize().live
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        d.history.undoAsync().await()
        d.garbageCollect(d.getVersionVector())
        assertEquals(0, d.garbageLength)
        assertEquals(before, d.getDocSize().live, "the purged tombstone never left live")
        assertLedgerExact(d, "after collecting a text attribute tombstone")
    }

    /**
     * A style whose range OPENS inside an element reaches it as an
     * End-only visit; the removed `TokenType.End` guard dropped the only
     * booking for that node (HIGH-1).
     */
    @Test
    fun `keeps live exact for a style straddling an element boundary`() = runTest {
        val d = seededTree()
        d.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(2), mapOf("b" to "1"))
        }.await()
        for (i in 0 until 6) {
            d.updateAsync { r, _ -> r.getAs<JsonTree>("t").style(1, 6, mapOf("b" to "v$i")) }
                .await()
            assertLedgerExact(d, "straddling overwrite ${i + 1}")
            assertNotNegative(d.getDocSize().live, "straddling overwrite ${i + 1}")
        }
    }

    @Test
    fun `keeps a split text attribute tombstone collectable`() = runTest {
        val d = seededText()
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        d.history.undoAsync().await()
        assertEquals(1, d.garbageLength, "one tombstone before the split")
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").edit(5, 5, "X") }.await()
        assertLedgerExact(d, "after splitting the node the tombstone rides on")
        val purged = d.garbageCollect(d.getVersionVector())
        assertTrue(purged > 0)
        assertEquals(0, d.garbageLength, "every tombstone was collected")
    }

    @Test
    fun `keeps a registered pair alive across a later split`() = runTest {
        val d = seededText()
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        d.history.undoAsync().await()
        assertEquals(1, d.garbageLength, "registered before any split")
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").edit(3, 3, "Z") }.await()
        assertLedgerExact(d, "after a split of the node the pair names")
        d.garbageCollect(d.getVersionVector())
        assertLedgerExact(d, "after collecting")
        assertEquals(0, d.garbageLength)
        assertEquals(
            DataSize(0, 0),
            rebuilt(d).docSize.gc,
            "the content still holds a tombstone the ledger called collected",
        )
    }

    @Test
    fun `balances a style that spans deleted text, and its undo`() = runTest {
        val d = seededText()
        val attr = mapOf("bbbbbbbbbb" to "vvvvvvvvvv")
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(4, 6, attr) }.await()
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").edit(4, 6, "") }.await()
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 8, attr) }.await()
        d.history.undoAsync().await()
        assertLedgerExact(d, "after undoing a style that spanned deleted text")
        assertNotNegative(d.getDocSize().live, "style spanning deleted text")
        d.garbageCollect(d.getVersionVector())
        assertEquals(0, d.garbageLength)
        assertEquals(DataSize(0, 0), d.getDocSize().gc, "gc residue")
    }

    @Test
    fun `does not strand a revived attribute in gc`() = runTest {
        val d = seededText()
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        d.history.undoAsync().await()
        assertEquals(1, d.garbageLength)
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "2")) }.await()
        assertEquals(0, d.garbageLength, "the tombstone was revived")
        assertLedgerExact(d, "after reviving an attribute tombstone")
    }

    @Test
    fun `does not strand a revived tree attribute in gc`() = runTest {
        val d = seededTree()
        d.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("b" to "1"))
        }.await()
        d.history.undoAsync().await()
        d.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("b" to "2"))
        }.await()
        assertLedgerExact(d, "after reviving a tree attribute tombstone")
    }

    private suspend fun fromSnapshot(a: Document): Document {
        val bytes = dev.yorkie.api.snapshotToBytes(a.getRootObject(), emptyMap())
        val b = Document("test-doc")
        b.applyChangePack(
            dev.yorkie.document.change.ChangePack(
                "test-doc",
                dev.yorkie.document.change.CheckPoint(1, 0u),
                emptyList(),
                bytes,
                false,
                a.getVersionVector(),
            ),
        )
        return b
    }

    @Test
    fun `keeps the tail when a split follows a snapshot`() = runTest {
        val a = seededText()
        val b = fromSnapshot(a)
        b.updateAsync { r, _ -> r.getAs<JsonText>("k").edit(5, 5, "X") }.await()
        assertEquals(
            """{"k":[{"val":"abcde"},{"val":"X"},{"val":"fghij"}]}""",
            b.toJson(),
            "the tail after the split point was destroyed",
        )
    }

    @Test
    fun `charges live for a style that follows a snapshot`() = runTest {
        val a = seededText()
        val d = fromSnapshot(a)
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        assertLedgerExact(d, "style after a snapshot")
    }

    // ---- adversarial probes for the attribute-ledger accounting above ----

    /**
     * A STYLE whose boundary split lands inside a node carrying a removed
     * attribute. The split's copied attribute tombstone must be drained
     * into the result, or it is unreachable by any future collection
     * (HIGH-2).
     */
    @Test
    fun `style boundary split registers the copied attribute tombstone`() = runTest {
        val d = seededText()
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        d.history.undoAsync().await()
        assertEquals(1, d.garbageLength)
        d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 4, mapOf("i" to "1")) }.await()
        assertLedgerExact(d, "after a style split the node carrying a removed attribute")
    }

    /** The same through a remote StyleOperation on a second replica. */
    @Test
    fun `remote style boundary split registers the copied attribute tombstone`() = runTest {
        val d1 = Document("test-doc").apply { setActor("000000000000000000000001") }
        val d2 = Document("test-doc").apply { setActor("000000000000000000000002") }
        d1.updateAsync { r, _ -> r.setNewText("k").edit(0, 0, "abcdefghij") }.await()
        d1.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        d1.history.undoAsync().await()
        crossSync(d1, d2)
        d1.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 4, mapOf("i" to "1")) }.await()
        crossSync(d1, d2)
        assertLedgerExact(d2, "remote replica after a style split")
    }

    /**
     * A two-replica pairing for the LWW overwrite flip: concurrent
     * overwrites of the same attribute on two replicas, both orderings of
     * which actor's ticket sorts higher, converge with no garbage on
     * either side. Mirrors `StyleTombstoneConvergenceTest`'s `either order`
     * loop: which actor's write ends up live must not decide whether the
     * replicas agree, or whether `Rht.set`'s superseded/installed booking
     * leaves the overwritten value's bytes stranded in live.
     */
    @Test
    fun `concurrent attribute overwrites converge with no garbage, either order`() = runTest {
        for (aOverwritesLower in listOf(true, false)) {
            val d1 = Document("test-doc").apply { setActor("000000000000000000000001") }
            val d2 = Document("test-doc").apply { setActor("000000000000000000000002") }
            d1.updateAsync { r, _ -> r.setNewText("k").edit(0, 0, "abcdefghij") }.await()
            d1.updateAsync { r, _ ->
                r.getAs<JsonText>(
                    "k",
                ).style(0, 10, mapOf("b" to "1"))
            }.await()
            crossSync(d1, d2)
            val lower = if (aOverwritesLower) d1 else d2
            val upper = if (aOverwritesLower) d2 else d1
            lower.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 5, mapOf("b" to "22")) }
                .await()
            upper.updateAsync { r, _ -> r.getAs<JsonText>("k").style(3, 10, mapOf("b" to "333")) }
                .await()
            crossSync(d1, d2)
            val tag = "aOverwritesLower=$aOverwritesLower"
            assertEquals(d1.toJson(), d2.toJson(), tag)
            assertEquals(0, d1.garbageLength, "d1 garbage ($tag)")
            assertEquals(0, d2.garbageLength, "d2 garbage ($tag)")
            assertEquals(d1.getDocSize(), d2.getDocSize(), tag)
            assertLedgerExact(d1, "d1 ($tag)")
            assertLedgerExact(d2, "d2 ($tag)")
        }
    }

    /**
     * A remote style that LOSES LWW against an attribute tombstone must
     * leave the tombstone registered, not spuriously revive it (the
     * baseline shape Rht.set's rewrite retires).
     */
    @Test
    fun `style losing LWW against a tombstone keeps the tombstone registered`() = runTest {
        val d1 = Document("test-doc").apply { setActor("000000000000000000000001") }
        val d2 = Document("test-doc").apply { setActor("000000000000000000000002") }
        d1.updateAsync { r, _ -> r.setNewText("k").edit(0, 0, "abcdefghij") }.await()
        d1.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "1")) }.await()
        crossSync(d1, d2)
        // bump d1's lamport so its removal outranks d2's concurrent write
        repeat(3) { d1.updateAsync { r, _ -> r.setNewObject("o$it") }.await() }
        d1.history.undoAsync().await() // undo of the last setNewObject
        d1.history.undoAsync().await()
        d1.history.undoAsync().await()
        d1.history.undoAsync().await() // removeStyle b
        d2.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("b" to "2")) }.await()
        crossSync(d1, d2)
        assertEquals(d1.toJson(), d2.toJson())
        assertEquals("""[{"val":"abcdefghij"}]""", d1.text().toJson(), "d1's removal must win LWW")
        assertLedgerExact(d1, "d1 after a losing write against its tombstone")
        assertLedgerExact(d2, "d2")
    }
}
