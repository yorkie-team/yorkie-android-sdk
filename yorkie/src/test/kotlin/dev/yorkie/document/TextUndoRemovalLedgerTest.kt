package dev.yorkie.document

import android.util.Base64
import dev.yorkie.api.snapshotToBytes
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNode
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.minVersionVector
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `text_undo_removal_ledger_test.ts` (JS SDK #1514, yorkie#2310, itself
 * a port of Go's `text_undo_removal_ledger_test.go`) as JVM unit tests. Every
 * change is relayed through the protobuf converters ([send]), as the JS
 * suite's `send` does, so the wire encode/decode of `removal_events` is part
 * of what each case exercises.
 */
class TextUndoRemovalLedgerTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"
    private val actor3 = "000000000000000000000003"

    /**
     * Delivers [from]'s pending local changes to each of [to] over the wire
     * (protobuf round trip), WITHOUT acking [from] -- mirrors the JS suite's
     * `send`. `android.util.Base64` is bridged to the real codec only for the
     * duration of the conversion, as [dev.yorkie.helper.crossSyncOverWire]
     * does.
     */
    private suspend fun send(from: Document, vararg to: Document) {
        val pack = from.createChangePack()
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        val wireChanges = try {
            pack.toPBChangePack().toChangePack().changes
        } finally {
            unmockkStatic(Base64::class)
        }
        to.forEach { doc ->
            doc.applyChangePack(
                ChangePack(
                    from.getKey(),
                    CheckPoint.InitialCheckPoint,
                    wireChanges,
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }
    }

    /** Drops [doc]'s already-sent local changes from its own queue. */
    private suspend fun ack(doc: Document) {
        val pack = doc.createChangePack()
        doc.applyChangePack(
            ChangePack(
                doc.getKey(),
                CheckPoint(0, pack.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    private suspend fun exchange(a: Document, b: Document) {
        send(a, b)
        send(b, a)
        ack(a)
        ack(b)
    }

    /** Two replicas (actor1, actor2) holding [content] in the Text under "t". */
    private suspend fun replicas(content: String): Pair<Document, Document> {
        val a = Document("ledger")
        val b = Document("ledger")
        a.setActor(actor1)
        b.setActor(actor2)
        a.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, content) }.await()
        exchange(a, b)
        return a to b
    }

    /** Three LIVE replicas (actor1, actor2, actor3) holding [content] in the Text under "t". */
    private suspend fun replicas3(content: String): Triple<Document, Document, Document> {
        val a = Document("ledger")
        val b = Document("ledger")
        val c = Document("ledger")
        a.setActor(actor1)
        b.setActor(actor2)
        c.setActor(actor3)
        a.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, content) }.await()
        send(a, b, c)
        ack(a)
        return Triple(a, b, c)
    }

    /** A third replica (actor3) loaded from [src]'s snapshot, as a late attach would be. */
    private suspend fun fromSnapshot(src: Document): Document {
        val bytes = snapshotToBytes(src.getRootObject(), emptyMap())
        val doc = Document("ledger")
        doc.setActor(actor3)
        doc.applyChangePack(
            ChangePack(
                "ledger",
                CheckPoint(1, 0u),
                emptyList(),
                bytes,
                false,
                src.getVersionVector(),
            ),
        )
        return doc
    }

    private suspend fun edit(
        doc: Document,
        from: Int,
        to: Int,
        content: String = "",
    ) {
        doc.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(from, to, content) }.await()
    }

    private suspend fun text(doc: Document) = doc.getRoot().getAs<JsonText>("t").toString()

    private fun nodes(doc: Document): List<RgaTreeSplitNode<TextValue>> =
        (doc.getRootObject()["t"] as CrdtText).rgaTreeSplit.toList()

    private fun tombstones(doc: Document) = nodes(doc).count { it.isRemoved }

    private fun events(doc: Document) = nodes(doc).sumOf { it.getRemovalEvents().size }

    private fun minVV(vararg docs: Document) = minVersionVector(docs.map { it.getVersionVector() })

    /**
     * Deletes the character at 1 and undoes it [n] times on [doc] alone, so
     * no peer sees the pairs and GC can prune none of them. Returns the most
     * removal events any node of the Text then carries.
     */
    private suspend fun churn(doc: Document, n: Int): Int {
        repeat(n) {
            edit(doc, 1, 2)
            doc.history.undoAsync().await()
        }
        return nodes(doc).maxOf { it.getRemovalEvents().size }
    }

    // ---- 1. keeps a concurrent deletion the undo never saw ----

    private suspend fun keepsConcurrentDeletionUndoerFirst(undoerFirst: Boolean) {
        val (a, b) = replicas("abcdefgh")
        // b deletes `d` and undoes it; a concurrently deletes `defgh`.
        edit(b, 3, 4)
        b.history.undoAsync().await()
        edit(a, 3, 8)

        if (undoerFirst) {
            send(b, a)
            send(a, b)
        } else {
            send(a, b)
            send(b, a)
        }

        // `d` was removed by a's deletion too: b's undo cancels only its own.
        assertEquals("abc", text(a))
        assertEquals("abc", text(b))
    }

    @Test
    fun `keeps a concurrent deletion the undo never saw - undoer first`() = runTest {
        keepsConcurrentDeletionUndoerFirst(true)
    }

    @Test
    fun `keeps a concurrent deletion the undo never saw - deleter first`() = runTest {
        keepsConcurrentDeletionUndoerFirst(false)
    }

    @Test
    fun `does not remove text a concurrent undo revives`() = runTest {
        val (d1, d2) = replicas("efgh")
        edit(d2, 1, 2)
        exchange(d1, d2)
        assertEquals("egh", text(d1))

        // d1 deletes `g` while d2 undoes its deletion of `f`.
        edit(d1, 1, 2)
        d2.history.undoAsync().await()
        exchange(d1, d2)

        // d1 saw `f` removed, so its deletion leaves the revived `f` alone.
        assertEquals("efh", text(d1))
        assertEquals("efh", text(d2))
    }

    @Test
    fun `cancels the redo not the original deletion on the next undo`() = runTest {
        val (a, b) = replicas("abcdefgh")
        // b: delete `d`, undo, redo, undo. Each undo has to cancel the
        // removal just before it: the second one cancels the redo.
        edit(b, 3, 4)
        b.history.undoAsync().await()
        b.history.redoAsync().await()
        b.history.undoAsync().await()
        assertEquals("abcdefgh", text(b))
        // a concurrently removes `defgh`, which keeps `d` removed everywhere.
        edit(a, 3, 8)
        exchange(a, b)

        assertEquals("abc", text(a))
        assertEquals("abc", text(b))
    }

    @Test
    fun `leaves alone a removed piece the deleter saw removed`() = runTest {
        val (a, b) = replicas("abcdefgh")
        edit(b, 3, 4)
        exchange(a, b)
        // a removes `cef` around the removed `d`; b concurrently undoes.
        edit(a, 2, 5)
        b.history.undoAsync().await()
        exchange(a, b)

        assertEquals("abdgh", text(a))
        assertEquals("abdgh", text(b))
    }

    @Test
    fun `keeps the ledger through a snapshot`() = runTest {
        val (a, b) = replicas("abcdefgh")
        edit(b, 3, 4)
        edit(a, 3, 8)
        send(a, b)

        // c loads b's snapshot, where `d` is removed by both deletions; it
        // must not forget a's part when b's undo arrives.
        val c = fromSnapshot(b)
        b.history.undoAsync().await()
        send(b, a, c)

        assertEquals("abc", text(a))
        assertEquals("abc", text(b))
        assertEquals("abc", text(c))
    }

    @Test
    fun `keeps a piece another deletion still holds out of GC`() = runTest {
        val (a, b) = replicas("abcdefgh")
        edit(b, 3, 4)
        edit(a, 3, 8)
        exchange(a, b)

        // Both deletions are known everywhere. b collects `efgh` but keeps
        // `d`: an undo of either deletion must find it held by the other.
        assertTrue(b.garbageCollect(b.getVersionVector()) > 0)
        assertEquals(1, tombstones(b))

        b.history.undoAsync().await()
        exchange(a, b)
        assertEquals("abc", text(a))
        assertEquals("abc", text(b))

        // Only a's deletion is left on `d`, so it can be collected now.
        assertTrue(b.garbageCollect(b.getVersionVector()) > 0)
        assertEquals(0, tombstones(b))
    }

    @Test
    fun `does not recreate a purged piece a redo never removed`() = runTest {
        val (a, b) = replicas("abcdefgh")
        // b deletes `d` and undoes it while a deletes `d` too.
        edit(b, 3, 4)
        b.history.undoAsync().await()
        edit(a, 3, 4)
        exchange(a, b)
        assertEquals("abcefgh", text(b))

        // b's redo finds `d` already removed by a, so it records nothing
        // there, and the undo of the redo must not name `d` either: a has
        // collected `d` and would recreate it.
        b.history.redoAsync().await()
        exchange(a, b)
        assertTrue(a.garbageCollect(a.getVersionVector()) > 0)
        b.history.undoAsync().await()
        exchange(a, b)

        assertEquals("abcefgh", text(a))
        assertEquals("abcefgh", text(b))
    }

    @Test
    fun `revives a deletion nobody else made also after a snapshot`() = runTest {
        val (a, b) = replicas("abcdefgh")
        edit(b, 3, 4)
        exchange(a, b)
        val c = fromSnapshot(a)
        b.history.undoAsync().await()
        send(b, a, c)

        assertEquals("abcdefgh", text(a))
        assertEquals("abcdefgh", text(b))
        assertEquals("abcdefgh", text(c))
    }

    @Test
    fun `redoes only what the redoer saw live`() = runTest {
        val (a, b) = replicas("abcdef")
        // a and b delete `cd` concurrently; a learns of b's deletion.
        edit(a, 2, 4)
        edit(b, 2, 4)
        send(b, a)
        ack(b)
        // a undoes and redoes: `cd` stays removed by b's deletion all along,
        // so a's redo removes nothing. b then undoes its deletion.
        a.history.undoAsync().await()
        a.history.redoAsync().await()
        b.history.undoAsync().await()
        exchange(a, b)

        // b revived `cd` before a's redo arrived; the redo, which saw `cd`
        // removed, leaves it alone there as it did on a.
        assertEquals("abcdef", text(a))
        assertEquals("abcdef", text(b))
    }

    @Test
    fun `propagates an undo that leaves the text as it was`() = runTest {
        val (a, b) = replicas("abcdefgh")
        // Both delete `a` concurrently; b learns of a's deletion first.
        edit(a, 0, 1)
        edit(b, 0, 1)
        send(a, b)
        ack(a)

        // b's undo cancels its own deletion while a's still holds `a`, so
        // b's text does not change. Its change has to reach a all the same
        // (ported from JS SDK #1514/yorkie#2310).
        a.history.undoAsync().await()
        b.history.undoAsync().await()
        assertEquals("bcdefgh", text(b))
        send(b, a)
        send(a, b)

        assertEquals("abcdefgh", text(a))
        assertEquals("abcdefgh", text(b))
    }

    @Test
    fun `redo leaves alone a piece removed in its view`() = runTest {
        val (a, b) = replicas("abcdefgh")
        edit(a, 0, 1)
        edit(b, 0, 3)
        send(b, a)
        ack(b)
        // b undoes before a hears of it, so in a's view `a` stays removed by
        // b's deletion: a's undo and redo leave it removed, and the redo
        // must not remove it on b, where b's undo already revived it.
        b.history.undoAsync().await()
        a.history.undoAsync().await()
        a.history.redoAsync().await()
        send(a, b)
        send(b, a)

        assertEquals(text(b), text(a))
        assertEquals("abcdefgh", text(a))
    }

    @Test
    fun `recreates a purged piece before the piece that follows it`() = runTest {
        val (a, b) = replicas("abcdefghij")
        edit(a, 1, 3)
        edit(a, 0, 1)
        // b splits `defghij` by removing `e`, so two pieces of the insertion
        // survive after the gap a's undo will recreate.
        edit(b, 4, 5)
        send(a, b)
        ack(a)
        assertTrue(b.garbageCollect(b.getVersionVector()) > 0)

        // a revives `a` and removes `d`. On b, `a` must come back before `d`:
        // placed after a later piece, a's removal would run from `a` to the
        // end of the text.
        a.history.undoAsync().await()
        edit(a, 1, 2)
        exchange(a, b)

        assertEquals("afghij", text(a))
        assertEquals("afghij", text(b))
    }

    @Test
    fun `keeps a piece until its cancelled removals are known everywhere`() = runTest {
        val (a, b) = replicas("abc")
        val c = fromSnapshot(a)
        // a and b remove `b` concurrently; c hears only of a's removal and
        // removes the text around it.
        edit(a, 1, 2)
        edit(b, 1, 2)
        send(a, c)
        edit(c, 0, 2)

        // a undoes its removal. b learns all of it, so only b's own removal
        // holds `b` there; c still knows nothing of a's undo.
        send(a, b)
        ack(a)
        a.history.undoAsync().await()
        send(a, b)
        send(b, a, c)
        ack(b)
        b.garbageCollect(minVV(a, b, c))

        // b's undo revives `b`; c's removal, which saw `b` removed by a,
        // must leave it alone on b as on a, whether or not b purged it.
        b.history.undoAsync().await()
        send(b, a, c)
        send(c, a, b)
        send(a, c)

        assertEquals("b", text(a))
        assertEquals("b", text(b))
        assertEquals("b", text(c))
    }

    @Test
    fun `links a recreated piece to the nearest surviving neighbours`() = runTest {
        val (a, b) = replicas("abcdefghij")
        // a removes `g`, then `h`; b collects both.
        edit(a, 6, 7)
        edit(a, 6, 7)
        send(a, b)
        ack(a)
        assertTrue(b.garbageCollect(b.getVersionVector()) > 0)

        // b removes `f`, the piece before the gap, and a learns of it.
        edit(b, 5, 6)
        send(b, a)
        ack(b)

        // a's undo recreates `h` on b, which then collects `f` too: `f`
        // still pointed past the recreated `h`, so purging it must not hand
        // `ij` an insertion predecessor that skips `h`.
        a.history.undoAsync().await()
        send(a, b)
        ack(a)
        assertTrue(b.garbageCollect(minVV(a, b)) > 0)

        // An edit ending right after `h` resolves through `ij`'s predecessor.
        edit(a, 5, 6)
        send(a, b)

        assertEquals("abcdeij", text(a))
        assertEquals("abcdeij", text(b))
    }

    @Test
    fun `keeps a recreated piece out of a deletion that saw it removed`() = runTest {
        val (a, b) = replicas("abc")
        val c = fromSnapshot(a)
        edit(b, 1, 2)
        send(b, a, c)
        ack(b)
        assertTrue(a.garbageCollect(minVV(a, b, c)) > 0)

        // b undoes; c, which saw `b` removed but not the undo, removes the
        // text around it. a recreates `b` from the undo, so the recreated
        // node has to answer c's view as b's own ledger does.
        b.history.undoAsync().await()
        edit(c, 0, 2)
        send(b, a, c)
        send(c, a, b)

        assertEquals("b", text(a))
        assertEquals("b", text(b))
        assertEquals("b", text(c))
    }

    @Test
    fun `drops cancelled removals every replica knows`() = runTest {
        val (a, b) = replicas("abcdefgh")
        repeat(5) {
            edit(a, 0, 8)
            a.history.undoAsync().await()
            exchange(a, b)
        }
        // Every removal is cancelled, but only a vector both replicas have
        // seen lets the pairs go; until then they decide later deletions.
        assertTrue(events(a) > 0)
        a.garbageCollect(minVV(a, b))
        assertEquals(0, events(a))
        assertEquals("abcdefgh", text(a))
    }

    @Test
    fun `carries a cancelled removal through a snapshot`() = runTest {
        val (a, b) = replicas("abcdefgh")
        // a deletes `d` and undoes it; the pair stays in the ledger while b
        // has not seen the cancel, so the snapshot has to carry both halves.
        edit(a, 3, 4)
        a.history.undoAsync().await()
        assertTrue(events(a) > 0)

        val c = fromSnapshot(a)
        assertTrue(events(c) > 0)
        assertEquals("abcdefgh", text(c))

        // The replica loaded from that snapshot answers a later concurrent
        // deletion as the one that wrote it does: b never saw the undo, so
        // its deletion of `d` holds the piece removed on both.
        edit(b, 3, 4)
        send(a, b)
        send(b, a, c)
        assertEquals("abcefgh", text(a))
        assertEquals(text(a), text(c))
    }

    @Test
    fun `reverses an undo that found nothing left to undo`() = runTest {
        val (a, b) = replicas("ab")
        // b inserts `X`, then deletes `a` and undoes that deletion, leaving
        // one entry on each stack.
        edit(b, 2, 2, "X")
        edit(b, 0, 1)
        b.history.undoAsync().await()
        exchange(a, b)
        assertEquals("abX", text(b))

        // a removes `X`; b's undo of its own insertion then finds nothing
        // live to re-remove, so its reverse names no span.
        edit(a, 2, 3)
        exchange(a, b)
        b.history.undoAsync().await()
        exchange(a, b)
        assertEquals("ab", text(b))

        // That reverse still has to be pushed: dropping it would leave the
        // redo stack one entry short, and this redo would re-run the
        // deletion of `a` instead of doing nothing.
        b.history.redoAsync().await()
        exchange(a, b)
        assertEquals("ab", text(b))
        assertEquals("ab", text(a))
    }

    @Test
    fun `reads back a snapshot whose ledger it grew itself`() = runTest {
        val (a, _) = replicas("abc")

        // A server holding this document writes the ledger into every
        // snapshot it builds, and a client has to load it whole. Churn a
        // reduced count (JVM-test-time bound; the JS/Go suites churn past
        // 4096, the bound Go puts only on pushed payloads) that still grows
        // the ledger past that bound.
        val grown = churn(a, (1 shl 11) + 8)
        assertTrue(grown > (1 shl 12))
        val b = fromSnapshot(a)
        assertEquals("abc", text(b))
        assertEquals(events(a), events(b))
    }

    // ---- a later insert's split must carry an already-ledgered (two-deletion) ----
    // ---- node's full ledger onto both halves ----

    /**
     * a and b concurrently delete "cd" (the same range); c concurrently inserts "X" between
     * them. On a and b, the OTHER replica's delete merges into their own still-single,
     * unledgered "cd" tombstone FIRST, building a two-deletion ledger on ONE node -- and only
     * THEN does c's insert arrive and force that node to split. c itself already split at
     * the exact insertion boundary locally before either delete arrives, so its own path
     * never re-splits an already-ledgered node. Undoing the deletion whose ticket root
     * `recompute()` leaves as `removedAt` (replica [undoerFirst] ? a : b) must leave the
     * piece removed everywhere: the other replica's still-live deletion holds it. Without
     * `copyLedger` in `RgaTreeSplitNode.split()`, the split halves on a and b would start
     * unledgered, `removedAt` alone pinned from the constructor, and undoing it would
     * spuriously revive them there (while c, which never re-split, would stay correct) --
     * exactly the reproduced `a=b≠c` divergence found during review.
     */
    private suspend fun ledgeredSplitSurvivesUndo(undoerFirst: Boolean) {
        val (a, b, c) = replicas3("abcdef")

        edit(a, 2, 4) // a deletes "cd" locally
        edit(b, 2, 4) // b deletes "cd" locally, concurrently
        edit(c, 3, 3, "X") // c inserts "X" locally, concurrently (between 'c' and 'd')

        // a and b first learn of EACH OTHER's delete (merging into their own still-single
        // "cd" tombstone -- ledgered, two dels, not yet split), and only then learn of c's
        // insert (forcing the split that must carry that ledger forward).
        send(b, a)
        send(a, b)
        send(a, c)
        send(b, c)
        send(c, a)
        send(c, b)

        assertEquals("abXef", text(a))
        assertEquals("abXef", text(b))
        assertEquals("abXef", text(c))

        val undoer = if (undoerFirst) a else b
        undoer.history.undoAsync().await()
        exchange(a, b)
        send(a, c)
        send(b, c)

        // The other replica's deletion is still live: "cd" stays removed on every replica.
        assertEquals("abXef", text(a))
        assertEquals("abXef", text(b))
        assertEquals("abXef", text(c))
    }

    @Test
    fun `undoing a concurrent deletion of an already-ledgered split piece stays removed - a`() =
        runTest {
            ledgeredSplitSurvivesUndo(undoerFirst = true)
        }

    @Test
    fun `undoing a concurrent deletion of an already-ledgered split piece stays removed - b`() =
        runTest {
            ledgeredSplitSurvivesUndo(undoerFirst = false)
        }
}
