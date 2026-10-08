package dev.yorkie.document.crdt

import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class CrdtTextTest {
    private lateinit var target: CrdtText

    @Before
    fun setUp() {
        target = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
    }

    @Test
    fun `should handle edit operations with attributes`() {
        target.edit(
            target.indexRangeToPosRange(0, 0),
            "ABCD",
            TimeTicket.InitialTimeTicket,
            mapOf("b" to "1"),
        )
        assertEquals(
            """[{"attrs":{"b":"1"},"val":"ABCD"}]""",
            target.toJson(),
        )

        target.edit(target.indexRangeToPosRange(3, 3), "\n", TimeTicket.InitialTimeTicket)
        assertEquals(
            """[{"attrs":{"b":"1"},"val":"ABC"},{"val":"\n"},""" +
                """{"attrs":{"b":"1"},"val":"D"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle edit operations without attributes`() {
        target.edit(target.indexRangeToPosRange(0, 0), "A", TimeTicket.InitialTimeTicket)
        assertEquals("""[{"val":"A"}]""", target.toJson())

        target.edit(target.indexRangeToPosRange(0, 0), "B", TimeTicket.InitialTimeTicket)
        assertEquals(
            """[{"val":"A"},{"val":"B"}]""",
            target.toJson(),
        )
    }

    // Port of yorkie-js-sdk 190204f8 (#1365): Rht.set's live-overwrite
    // branch used to hand back a tombstoned COPY of the overwritten value
    // so its bytes left docSize.live via a GC pass. JS's fix supersedes
    // that: RHT overrides immutably, so the old live value is simply
    // dropped with no tombstone and nothing to collect — its bytes leave
    // live directly via accAttrWrite's `superseded` subtraction. This test
    // used to assert the old copy-and-collect shape; it now asserts the
    // retirement: no GCPair, no garbage, ever, from a same-key overwrite.
    @Test
    fun `style overwrite of a live attribute leaves no garbage behind`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val editResult = text.edit(text.indexRangeToPosRange(0, 0), "01", tick(1))
        root.acc(editResult.dataSize)

        val firstStyle = text.style(text.indexRangeToPosRange(0, 2), mapOf("b" to "1"), tick(2))
        root.acc(firstStyle.docSize.live)
        root.accGC(firstStyle.docSize.gc)
        firstStyle.gcPairs.forEach(root::registerGCPair)
        assertEquals(
            0,
            root.garbageLength,
            "the first style has no predecessor to tombstone yet",
        )
        val liveAfterFirstStyle = root.docSize.live

        // Overwrite the same key with an equal-length value: the old value's
        // bytes leave live via accAttrWrite's `superseded` subtraction and
        // the new value's bytes re-enter live via `installed` — net zero
        // change, and NO GCPair is minted for the overwritten value.
        val secondStyle = text.style(text.indexRangeToPosRange(0, 2), mapOf("b" to "2"), tick(3))
        root.acc(secondStyle.docSize.live)
        root.accGC(secondStyle.docSize.gc)
        secondStyle.gcPairs.forEach(root::registerGCPair)

        assertEquals(
            emptyList(),
            secondStyle.gcPairs,
            "an overwritten LIVE attribute mints no tombstone and registers no gc pair",
        )
        assertEquals(
            liveAfterFirstStyle,
            root.docSize.live,
            "an equal-length overwrite nets to zero change in live",
        )
        assertEquals(DataSize(data = 0, meta = 0), root.docSize.gc)
        assertEquals(
            0,
            root.garbageLength,
            "overwriting a live attribute leaves nothing to collect",
        )
        assertEquals(0, root.garbageCollect(maxVectorOf(listOf(actor))))
    }

    // The two-node sibling of the test above — a style spanning two
    // separate rga nodes overwrites the SAME key on both. The OLD
    // tombstoned copies for both nodes were structurally equal, which an
    // equals-keyed gcPairMap collapsed into one entry; the retirement
    // sidesteps that entire class of bug by minting no tombstone copy at
    // all.
    @Test
    fun `style overwrite across two nodes leaves no garbage on either node`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        // Two separate rga nodes: "01" then "23" appended.
        root.acc(text.edit(text.indexRangeToPosRange(0, 0), "01", tick(1)).dataSize)
        root.acc(text.edit(text.indexRangeToPosRange(2, 2), "23", tick(2)).dataSize)
        assertEquals(2, text.rgaTreeSplit.count { !it.isRemoved })

        val firstStyle = text.style(text.indexRangeToPosRange(0, 4), mapOf("b" to "1"), tick(3))
        root.acc(firstStyle.docSize.live)
        root.accGC(firstStyle.docSize.gc)
        firstStyle.gcPairs.forEach(root::registerGCPair)
        val liveAfterFirstStyle = root.docSize.live

        val secondStyle = text.style(text.indexRangeToPosRange(0, 4), mapOf("b" to "2"), tick(4))
        root.acc(secondStyle.docSize.live)
        root.accGC(secondStyle.docSize.gc)
        secondStyle.gcPairs.forEach(root::registerGCPair)

        assertEquals(
            0,
            secondStyle.gcPairs.size,
            "an overwritten LIVE attribute mints no tombstone on either node",
        )
        assertEquals(0, root.garbageLength, "nothing to collect from an equal-length overwrite")
        assertEquals(
            DataSize(data = 0, meta = 0),
            root.docSize.gc,
            "no byte ever moved into gc for a live overwrite",
        )
        assertEquals(
            liveAfterFirstStyle,
            root.docSize.live,
            "an equal-length overwrite on either node nets to zero change in live",
        )
        assertEquals(0, root.garbageCollect(maxVectorOf(listOf(actor))))
    }

    // An already-removed owning node's single outer GCPair (gcOnlySize =
    // node.dataSize) already covers every attribute's bytes (TextValue's
    // getDataSize sums them in) — re-adding each attribute's own pair on top
    // double-counts them into docSize.gc.
    @Test
    fun `gcPairs does not double-count an attribute tombstone whose owning node is removed`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val split = RgaTreeSplit<TextValue>()
        val value = TextValue("cd").apply { setAttribute("bold", "true", tick(1)) }
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(tick(0), 0), value)
        split.insertAfter(split.head, node)
        node.remove(tick(2))

        val text = CrdtText(split, tick(3))

        val pairs = text.gcPairs
        assertEquals(
            1,
            pairs.size,
            "the owning node's single pair already covers its attribute bytes",
        )
        assertEquals(node.dataSize, pairs.single().gcOnlySize)
    }

    // removeStyle (not a same-length style overwrite — Rht.set's tombstoned
    // copy is handed to the caller for GC registration but is never itself
    // stored back into the node's own attribute map) leaves a genuine
    // tombstoned RhtNode inside the still-live "cd" node's
    // own attributes. Deleting and GC'ing "cd" purges it; undo recreates it
    // via subSequence, which deliberately preserves the copied attribute
    // tombstone verbatim. CrdtText.restore() must register that copied
    // tombstone or its bytes are never reachable by any future GC pass.
    @Test
    fun `restore registers a recreated node's copied attribute tombstone for later GC`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val editResult = text.edit(text.indexRangeToPosRange(0, 0), "abcdef", tick(1))
        root.acc(editResult.dataSize)

        val styleResult =
            text.style(text.indexRangeToPosRange(2, 4), mapOf("bold" to "true"), tick(2))
        root.acc(styleResult.docSize.live)
        root.accGC(styleResult.docSize.gc)
        styleResult.gcPairs.forEach(root::registerGCPair)

        val removeStyleResult =
            text.removeStyle(text.indexRangeToPosRange(2, 4), listOf("bold"), tick(3))
        removeStyleResult.gcPairs.forEach(root::registerGCPair)

        val deleteResult = text.edit(text.indexRangeToPosRange(2, 4), "", tick(4))
        root.acc(deleteResult.dataSize)
        deleteResult.gcPairs.forEach(root::registerGCPair)

        // Purge "cd" (and its now-orphaned bold tombstone) so restore() must
        // recreate it from scratch rather than un-tombstoning it in place.
        root.garbageCollect(maxVectorOf(listOf(actor)))
        assertEquals(0, root.garbageLength)

        val restoreResult = text.restore(deleteResult.removedSpans, tick(5))
        assertEquals(1, restoreResult.recreated.size)
        assertEquals(
            1,
            restoreResult.pendingGcPairs.size,
            "the recreated node's copied bold tombstone must be registered, not silently dropped",
        )
        restoreResult.pendingGcPairs.forEach(root::registerGCPair)

        assertEquals(1, root.garbageLength)
        assertEquals(1, root.garbageCollect(maxVectorOf(listOf(actor))))
        assertEquals(0, root.garbageLength)
    }

    // removeStyle's own two boundary splits (findNodeWithSplit for
    // range.second then range.first) deep-copy whatever attribute tombstones
    // the node being split already carries (RgaTreeSplit.splitNode), exactly
    // the mechanism `style()` already drains via
    // drainPendingAttributeGcPairs(). removeStyle must drain the same buffer
    // or the copies are never registered: their bytes never entered
    // docSize.live (TextValue.getDataSize skips removed attributes either
    // way) but also never enter docSize.gc without a registerGCPair call, so
    // they simply vanish from the ledger and are unreachable by any future
    // garbageCollect() sweep.
    @Test
    fun `removeStyle registers the copied attribute tombstones its own boundary splits produce`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        obj.set("t", text, tick(0))
        root.registerElement(text, obj)

        val editResult = text.edit(text.indexRangeToPosRange(0, 0), "abcdef", tick(1))
        root.acc(editResult.dataSize)

        val styleResult =
            text.style(text.indexRangeToPosRange(0, 6), mapOf("bold" to "true"), tick(2))
        root.acc(styleResult.docSize.live)
        styleResult.gcPairs.forEach(root::registerGCPair)

        // Removes "bold" from the WHOLE node, so the single "abcdef" node
        // becomes one removed-attribute tombstone spanning its entire
        // content (no split needed yet: the range matches the node exactly).
        val firstRemove = text.removeStyle(text.indexRangeToPosRange(0, 6), listOf("bold"), tick(3))
        root.acc(firstRemove.docSize.live)
        firstRemove.gcPairs.forEach(root::registerGCPair)

        // A SECOND removeStyle, over a DIFFERENT key and a sub-range, forces
        // its own two boundary splits inside "abcdef" (at offsets 2 and 4).
        // Each split deep-copies the already-tombstoned "bold" attribute onto
        // the newly split-off piece — two copies in total ("cd" and "ef").
        val secondRemove =
            text.removeStyle(text.indexRangeToPosRange(2, 4), listOf("italic"), tick(4))
        root.acc(secondRemove.docSize.live)
        secondRemove.gcPairs.forEach(root::registerGCPair)

        // A from-scratch rebuild sees the exact same final CRDT structure
        // (the mutation only affects BOOKKEEPING, not the structure itself),
        // so it always counts the copied tombstones correctly via
        // TextValue.gcPairs — an oracle immune to the registration bug.
        val rebuilt = CrdtRoot(obj.deepCopy())
        assertEquals(
            rebuilt.docSize.gc,
            root.docSize.gc,
            "the two copied attribute tombstones from removeStyle's own " +
                "boundary splits must be registered into gc",
        )
        assertEquals(rebuilt.docSize.live, root.docSize.live)
    }

    // RgaTreeSplit.restore's un-tombstone branch (isolateRange on an
    // ALREADY-removed piece) can also split that piece to isolate only PART
    // of it for revival; the untouched remainder inherits a DEEP COPY of
    // whatever attribute tombstones the original piece carried, buffered the
    // same way a live split's copy is (RgaTreeSplit.splitNode). CrdtText.restore
    // must drain that buffer too, or the copy is never registered.
    @Test
    fun `restore registers the copied attribute tombstone its own boundary split produces`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        obj.set("t", text, tick(0))
        root.registerElement(text, obj)

        val editResult = text.edit(text.indexRangeToPosRange(0, 0), "abcdef", tick(1))
        root.acc(editResult.dataSize)

        val styleResult =
            text.style(text.indexRangeToPosRange(0, 6), mapOf("bold" to "true"), tick(2))
        root.acc(styleResult.docSize.live)
        styleResult.gcPairs.forEach(root::registerGCPair)

        // Splits "abcdef" into "ab" | "cd" | "ef"; "cd" keeps a genuine
        // (not yet copied) "bold" tombstone.
        val removeStyleResult =
            text.removeStyle(text.indexRangeToPosRange(2, 4), listOf("bold"), tick(3))
        root.acc(removeStyleResult.docSize.live)
        removeStyleResult.gcPairs.forEach(root::registerGCPair)

        // Deletes [1, 5) ("b","cd","e") WITHOUT purging: "cd" becomes a
        // removed node that still carries its own (not copied) bold
        // tombstone.
        val deleteResult = text.edit(text.indexRangeToPosRange(1, 5), "", tick(4))
        root.acc(deleteResult.dataSize)
        deleteResult.gcPairs.forEach(root::registerGCPair)

        // One RestoreSpan per removed NODE ("b", "cd", "e"): pick "cd"'s.
        val fullSpan = deleteResult.removedSpans.single { it.start == 2 }
        // Narrows the span to [3, 4) — the right half of "cd" only — so
        // restore()'s un-tombstone branch must split "cd" to isolate it,
        // deep-copying "cd"'s existing bold tombstone onto the untouched
        // left remainder that is inserted by the split.
        val narrowSpan = RestoreSpan(
            fullSpan.createdAt,
            3,
            4,
            fullSpan.value.subSequence(3 - fullSpan.start, 4 - fullSpan.start) as TextValue,
        )

        val restoreResult = text.restore(listOf(narrowSpan), tick(5))
        assertEquals(1, restoreResult.untombstoned.size)
        assertEquals(0, restoreResult.recreated.size)

        restoreResult.pendingGcPairs.forEach(root::registerGCPair)
        restoreResult.untombstoned.forEach { node ->
            root.unregisterGCPair(GCPair(text.rgaTreeSplit, node))
        }
        root.acc(restoreResult.dataSize)

        val rebuilt = CrdtRoot(obj.deepCopy())
        assertEquals(
            rebuilt.docSize.gc,
            root.docSize.gc,
            "restore's own boundary split's copied bold tombstone must be registered into gc",
        )
        assertEquals(rebuilt.docSize.live, root.docSize.live)
    }

    // RgaTreeSplit.retombstone's isolateRange call on a LIVE piece can
    // also split that piece to isolate only the part being re-deleted; the
    // sibling piece the split leaves behind (still live) inherits a DEEP
    // COPY of whatever attribute tombstones the original piece already
    // carried. CrdtText.retombstone must drain that buffer too, or the copy
    // is never registered.
    @Test
    fun `retombstone registers the copied attribute tombstone its own boundary split produces`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        obj.set("t", text, tick(0))
        root.registerElement(text, obj)

        val editResult = text.edit(text.indexRangeToPosRange(0, 0), "abcdef", tick(1))
        root.acc(editResult.dataSize)

        val styleResult =
            text.style(text.indexRangeToPosRange(0, 6), mapOf("bold" to "true"), tick(2))
        root.acc(styleResult.docSize.live)
        styleResult.gcPairs.forEach(root::registerGCPair)

        // Splits "abcdef" into "ab" | "cd" | "ef"; "cd" stays LIVE but keeps
        // a genuine (not yet copied) "bold" tombstone.
        val removeStyleResult =
            text.removeStyle(text.indexRangeToPosRange(2, 4), listOf("bold"), tick(3))
        root.acc(removeStyleResult.docSize.live)
        removeStyleResult.gcPairs.forEach(root::registerGCPair)

        // A redo span targeting only [2, 3) — the left half of the still-LIVE
        // "cd" — so retombstone()'s isolateRange call must split "cd" to
        // isolate it, deep-copying "cd"'s existing bold tombstone onto the
        // sibling half the split leaves behind, still live. retombstone()
        // never reads span.value (only used by restore() to recreate purged
        // content), so an arbitrary placeholder is fine here.
        val span = RestoreSpan(tick(1), 2, 3, TextValue("c"))

        val retombstoneResult = text.retombstone(listOf(span), tick(5))
        root.acc(retombstoneResult.dataSize)
        retombstoneResult.gcPairs.forEach(root::registerGCPair)

        val rebuilt = CrdtRoot(obj.deepCopy())
        assertEquals(
            rebuilt.docSize.gc,
            root.docSize.gc,
            "retombstone's own boundary split's copied bold tombstone must be registered into gc",
        )
        assertEquals(rebuilt.docSize.live, root.docSize.live)
    }

    // The first of style's two sequential findNodeWithSplit calls can
    // already have buffered a born-dead split piece (splitting an
    // already-tombstoned node) before the second throws; StyleOperation must
    // drain and register it before propagating, not leave it stuck in
    // RgaTreeSplit's own buffer.
    @Test
    fun `StyleOperation drains a pending GC pair even when the second split throws`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val t1 = tick(1)
        text.edit(text.indexRangeToPosRange(0, 0), "0123456789", t1)
        val deleteResult = text.edit(text.indexRangeToPosRange(4, 6), "", tick(2))
        deleteResult.gcPairs.forEach(root::registerGCPair)
        val garbageBefore = root.garbageLength

        // toPos lands INSIDE the tombstoned "45" node (id offset 5): splitting
        // it buffers a born-dead piece. fromPos is a nonexistent position, so
        // the second findNodeWithSplit call throws before StyleOperation ever
        // gets a TextStyleResult back.
        val insideTombstone = RgaTreeSplitPos(RgaTreeSplitNodeID(t1, 5), 0)
        val nonexistent = RgaTreeSplitPos(RgaTreeSplitNodeID(TimeTicket.MaxTimeTicket, 0), 0)

        val op = StyleOperation(
            fromPos = nonexistent,
            toPos = insideTombstone,
            attributes = mapOf("b" to "1"),
            parentCreatedAt = text.createdAt,
            executedAt = tick(3),
        )

        assertFailsWith<NoSuchElementException> {
            op.execute(root, OpSource.Local, null)
        }

        assertEquals(
            garbageBefore + 1,
            root.garbageLength,
            "the born-dead piece must be registered even though the operation threw",
        )
        assertTrue(
            text.rgaTreeSplit.drainPendingGcPairs().isEmpty(),
            "the buffer must already be drained, not left for a future caller to double-register",
        )
    }

    // S7: EditOperation.execute's edit path needs the same catch-drain
    // regression coverage as StyleOperation above — RgaTreeSplit.edit's
    // first sequential findNodeWithSplit call (range.second / toPos) can
    // already have buffered a born-dead split piece before the second call
    // (range.first / fromPos) throws.
    @Test
    fun `EditOperation drains a pending GC pair even when the second split throws`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val t1 = tick(1)
        text.edit(text.indexRangeToPosRange(0, 0), "0123456789", t1)
        val deleteResult = text.edit(text.indexRangeToPosRange(4, 6), "", tick(2))
        deleteResult.gcPairs.forEach(root::registerGCPair)
        val garbageBefore = root.garbageLength

        // toPos lands INSIDE the tombstoned "45" node (id offset 5): splitting
        // it in the FIRST findNodeWithSplit call (range.second) buffers a
        // born-dead piece. fromPos is a nonexistent position, so the SECOND
        // findNodeWithSplit call (range.first) throws before
        // EditOperation.execute ever gets a TextEditResult back.
        val insideTombstone = RgaTreeSplitPos(RgaTreeSplitNodeID(t1, 5), 0)
        val nonexistent = RgaTreeSplitPos(RgaTreeSplitNodeID(TimeTicket.MaxTimeTicket, 0), 0)

        val op = EditOperation(
            fromPos = nonexistent,
            toPos = insideTombstone,
            content = "",
            parentCreatedAt = text.createdAt,
            executedAt = tick(3),
            attributes = emptyMap(),
        )

        assertFailsWith<NoSuchElementException> {
            op.execute(root, OpSource.Local, null)
        }

        assertEquals(
            garbageBefore + 1,
            root.garbageLength,
            "the born-dead piece must be registered even though the operation threw",
        )
        assertTrue(
            text.rgaTreeSplit.drainPendingGcPairs().isEmpty(),
            "the buffer must already be drained, not left for a future caller to double-register",
        )
    }

    // #1363 (F11, second pass): the two catch-drain tests above split a
    // tombstone WITHOUT any attribute tombstone on it, so they never
    // exercise RgaTreeSplit's SEPARATE pendingAttributeGcPairs buffer
    // (splitNode copies a node's attribute tombstones onto every new piece
    // it produces, live or dead — see splitNode). Here "45" already carries
    // a COPIED "b" removed-attribute tombstone (from edit(4,6)'s own
    // boundary splits) before StyleOperation's second findNodeWithSplit
    // throws, so the first (successful) call both buffers a born-dead node
    // piece AND copies "45"'s tombstone onto it — a catch handler that
    // drains only the node-level buffer leaks the attribute copy.
    @Test
    fun `StyleOperation drains a pending attribute GC pair even when the second split throws`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val t1 = tick(1)
        text.edit(text.indexRangeToPosRange(0, 0), "0123456789", t1)
        // Styles, then un-styles, the WHOLE (still unsplit) node: no split
        // happens (both ranges land exactly on the node's boundaries), so
        // the node is simply left carrying a REMOVED "b" tombstone.
        val styleResult =
            text.style(text.indexRangeToPosRange(0, 10), mapOf("b" to "1"), tick(2))
        styleResult.gcPairs.forEach(root::registerGCPair)
        val removeStyleResult =
            text.removeStyle(text.indexRangeToPosRange(0, 10), listOf("b"), tick(3))
        removeStyleResult.gcPairs.forEach(root::registerGCPair)

        // Deletes "45": edit's own two boundary splits each copy the node's
        // "b" tombstone onto their new piece (splitNode), so the resulting
        // removed node "45" carries a COPY of "b", not the original.
        val deleteResult = text.edit(text.indexRangeToPosRange(4, 6), "", tick(4))
        deleteResult.gcPairs.forEach(root::registerGCPair)
        val garbageBefore = root.garbageLength

        // toPos lands INSIDE the tombstoned "45" node (id offset 5): splitting
        // it in the FIRST findNodeWithSplit call (range.second) buffers a
        // born-dead node piece AND copies "45"'s "b" tombstone onto it.
        // fromPos is nonexistent, so the SECOND findNodeWithSplit call
        // (range.first) throws before CrdtText.style ever returns.
        val insideTombstone = RgaTreeSplitPos(RgaTreeSplitNodeID(t1, 5), 0)
        val nonexistent = RgaTreeSplitPos(RgaTreeSplitNodeID(TimeTicket.MaxTimeTicket, 0), 0)

        val op = StyleOperation(
            fromPos = nonexistent,
            toPos = insideTombstone,
            attributes = mapOf("i" to "1"),
            parentCreatedAt = text.createdAt,
            executedAt = tick(5),
        )

        assertFailsWith<NoSuchElementException> {
            op.execute(root, OpSource.Local, null)
        }

        assertEquals(
            garbageBefore + 2,
            root.garbageLength,
            "both the born-dead node piece AND its copied attribute tombstone must be registered",
        )
        assertTrue(
            text.rgaTreeSplit.drainPendingGcPairs().isEmpty(),
            "the node-level buffer must already be drained",
        )
        assertTrue(
            text.rgaTreeSplit.drainPendingAttributeGcPairs().isEmpty(),
            "the attribute-level buffer must already be drained, not left to leak (F11, #1363)",
        )
    }

    // Same gap as above, for EditOperation.execute's edit path.
    @Test
    fun `EditOperation drains a pending attribute GC pair even when the second split throws`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val t1 = tick(1)
        text.edit(text.indexRangeToPosRange(0, 0), "0123456789", t1)
        val styleResult =
            text.style(text.indexRangeToPosRange(0, 10), mapOf("b" to "1"), tick(2))
        styleResult.gcPairs.forEach(root::registerGCPair)
        val removeStyleResult =
            text.removeStyle(text.indexRangeToPosRange(0, 10), listOf("b"), tick(3))
        removeStyleResult.gcPairs.forEach(root::registerGCPair)

        val deleteResult = text.edit(text.indexRangeToPosRange(4, 6), "", tick(4))
        deleteResult.gcPairs.forEach(root::registerGCPair)
        val garbageBefore = root.garbageLength

        val insideTombstone = RgaTreeSplitPos(RgaTreeSplitNodeID(t1, 5), 0)
        val nonexistent = RgaTreeSplitPos(RgaTreeSplitNodeID(TimeTicket.MaxTimeTicket, 0), 0)

        val op = EditOperation(
            fromPos = nonexistent,
            toPos = insideTombstone,
            content = "",
            parentCreatedAt = text.createdAt,
            executedAt = tick(5),
            attributes = emptyMap(),
        )

        assertFailsWith<NoSuchElementException> {
            op.execute(root, OpSource.Local, null)
        }

        assertEquals(
            garbageBefore + 2,
            root.garbageLength,
            "both the born-dead node piece AND its copied attribute tombstone must be registered",
        )
        assertTrue(
            text.rgaTreeSplit.drainPendingGcPairs().isEmpty(),
            "the node-level buffer must already be drained",
        )
        assertTrue(
            text.rgaTreeSplit.drainPendingAttributeGcPairs().isEmpty(),
            "the attribute-level buffer must already be drained, not left to leak (F11, #1363)",
        )
    }
}
