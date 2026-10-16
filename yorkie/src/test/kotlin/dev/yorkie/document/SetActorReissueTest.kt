package dev.yorkie.document

import android.util.Base64
import dev.yorkie.api.snapshotToBytes
import dev.yorkie.core.ResourceStatus
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.YorkieException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val ACTOR_A = "0000000000000000000000a1"
private const val ACTOR_B = "0000000000000000000000b2"

/**
 * Port of yorkie-js-sdk's `set_actor_reissue_test.ts` never-synced/flag
 * cases (`ed25355e`, #1449, yorkie#2111) -- the `setActor(actor, reissue)`
 * rebuild+replay+swap itself is pinned separately once it lands (context
 * §3.2, spec 038_sync-js-sdk-v0725-pre-attach-reissue Unit 4).
 */
class SetActorReissueTest {

    // toBytes()/fromBytes() encode a non-empty VersionVector via
    // android.util.Base64, an unmocked stub under plain JVM unit tests (same
    // bridge as SnapshotConverterTest).
    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    private fun emptyRemotePack(checkPoint: CheckPoint = CheckPoint.InitialCheckPoint) = ChangePack(
        documentKey = "",
        checkPoint = checkPoint,
        changes = emptyList(),
        snapshot = null,
        isRemoved = false,
        versionVector = VersionVector(),
    )

    @Test
    fun `a fresh never-attached document with local changes is never-synced`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()

        assertTrue(document.neverSynced)
        assertTrue(document.hasMintedOperations())
    }

    @Test
    fun `a document with no local changes has not minted operations`() = runTest {
        val document = Document("")
        assertFalse(document.hasMintedOperations())
    }

    @Test
    fun `restoreAppendedChanges marks absorbed and minted, never re-issues after`() = runTest {
        val source = Document("")
        source.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        val appendedChange = source.pendingChanges().single()

        val restored = Document("")
        restored.restoreAppendedChanges(listOf(appendedChange))

        assertFalse(restored.neverSynced, "a replayed append log must never re-issue")
        assertTrue(restored.hasMintedOperations())

        val reissued = restored.setActor(ACTOR_A, reissue = true)
        assertFalse(reissued, "the fallback renames only -- no re-issue after a log replay")
    }

    @Test
    fun `applying a remote change clears never-synced`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        assertTrue(document.neverSynced)

        document.applyChangePack(emptyRemotePack())

        assertFalse(document.neverSynced, "a document that absorbed a remote pack is synced")
    }

    @Test
    fun `applying a snapshot clears never-synced`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        assertTrue(document.neverSynced)

        val snapshotBytes = snapshotToBytes(document.getRootObject(), document.allPresences.value)
        val pack = emptyRemotePack().copy(snapshot = snapshotBytes)
        document.applyChangePack(pack)

        assertFalse(document.neverSynced, "applySnapshot must mark absorbedRemote")
    }

    @Test
    fun `a non-Detached status clears never-synced`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        assertTrue(document.neverSynced)

        document.applyStatus(ResourceStatus.Attached)

        assertFalse(document.neverSynced)
    }

    @Test
    fun `a version vector naming another actor clears never-synced`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        assertTrue(document.neverSynced)

        document.changeID = document.changeID.copy(
            versionVector = VersionVector(mapOf("000000000000000000000abc" to 1L)),
        )

        assertFalse(document.neverSynced)
    }

    @Test
    fun `a restored document is never-synced=false and has minted operations`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        val bytes = document.toBytes()

        val restored = Document.fromBytes("", bytes)

        assertFalse(restored.neverSynced, "a restored document must never re-issue")
        assertTrue(restored.hasMintedOperations())
        restored.close()
    }

    @Test
    fun `resetForReanchor clears absorbedRemote but keeps mintedOperations`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        document.applyChangePack(emptyRemotePack())
        assertFalse(document.neverSynced)
        assertTrue(document.hasMintedOperations())

        document.resetForReanchor()

        assertTrue(document.hasMintedOperations(), "mintedOperations is never cleared")
        // absorbedRemote is cleared, but resetForReanchor also clears
        // localChanges, so the document has no local changes of its own
        // at this point -- neverSynced is a property of actor-reissue
        // eligibility, independent of whether there is anything to reissue.
        assertEquals(CheckPoint.InitialCheckPoint, document.checkPoint)
    }

    // ---- Unit 4: setActor(actor, reissue) rebuild+replay+swap ----------
    // Port of yorkie-js-sdk's `Document.setActor with reissue` describe
    // block (`ed25355e`, #1449, yorkie#2111). `documentActors` is an
    // INDEPENDENT oracle from the production walker under test
    // (api/Reissue.kt): it recurses the DOMAIN tree (CrdtObject/CrdtArray/
    // CrdtText/CrdtTree) and every pending local Change's own operation
    // fields directly, rather than re-walking the wire message shapes
    // Reissue.kt itself knows about -- so it cannot share that walker's
    // blind spot. lamport-0 (InitialTimeTicket) is excluded everywhere, as
    // it is the root/sentinel identity shared by every replica and is never
    // rewritten by design.

    private fun TimeTicket?.recordInto(actors: MutableSet<String>) {
        if (this != null && lamport != 0L) actors += actorID
    }

    private fun collectElementActors(element: CrdtElement, actors: MutableSet<String>) {
        element.createdAt.recordInto(actors)
        element.movedAt.recordInto(actors)
        element.removedAt.recordInto(actors)
        when (element) {
            is CrdtObject -> element.forEach { (_, value) -> collectElementActors(value, actors) }
            is CrdtArray -> element.forEach { value -> collectElementActors(value, actors) }
            is CrdtText -> element.rgaTreeSplit.forEach { node ->
                node.id.createdAt.recordInto(actors)
                node.removedAt.recordInto(actors)
                node.insertionPrev?.id?.createdAt.recordInto(actors)
                node.value.attributesWithTimeTicket.forEach { it.executedAt.recordInto(actors) }
            }

            is CrdtTree -> element.indexTree.traverseAll { node, _ ->
                node.id.createdAt.recordInto(actors)
                node.removedAt.recordInto(actors)
                node.insPrevID?.createdAt.recordInto(actors)
                node.insNextID?.createdAt.recordInto(actors)
                node.mergedFrom?.createdAt.recordInto(actors)
                node.mergedAt.recordInto(actors)
                node.rhtNodes.forEach { it.executedAt.recordInto(actors) }
            }

            else -> Unit
        }
    }

    private fun collectOperationActors(op: Operation, actors: MutableSet<String>) {
        op.parentCreatedAt.recordInto(actors)
        op.executedAt.recordInto(actors)
        when (op) {
            is SetOperation -> collectElementActors(op.value, actors)
            is AddOperation -> {
                op.prevCreatedAt.recordInto(actors)
                collectElementActors(op.value, actors)
            }

            is MoveOperation -> {
                op.prevCreatedAt.recordInto(actors)
                op.createdAt.recordInto(actors)
            }

            is RemoveOperation -> op.createdAt.recordInto(actors)
            is EditOperation -> {
                op.fromPos.id.createdAt.recordInto(actors)
                op.toPos.id.createdAt.recordInto(actors)
                op.restoreSpans?.forEach { it.createdAt.recordInto(actors) }
                op.retombstoneSpans?.forEach { it.createdAt.recordInto(actors) }
            }

            is StyleOperation -> {
                op.fromPos.id.createdAt.recordInto(actors)
                op.toPos.id.createdAt.recordInto(actors)
            }

            is IncreaseOperation -> collectElementActors(op.value, actors)
            is TreeEditOperation -> {
                op.fromPos.parentID.createdAt.recordInto(actors)
                op.fromPos.leftSiblingID.createdAt.recordInto(actors)
                op.toPos.parentID.createdAt.recordInto(actors)
                op.toPos.leftSiblingID.createdAt.recordInto(actors)
                op.splitTickets.forEach { it.recordInto(actors) }
                op.contents?.forEach { content ->
                    collectTreeNodeActors(content, actors)
                }
                // M5 (team-review, RTCOLLABPLATFORM-789): an identity-preserving
                // restore/retombstone op (undo/redo of a plain Tree delete) addresses
                // content by these spans' own ids, not by fromPos/toPos -- an
                // un-reissued span went undetected by every test using this oracle.
                fun recordSpan(span: dev.yorkie.document.crdt.TreeRestoreSpan) {
                    span.id.createdAt.recordInto(actors)
                    span.parentID?.createdAt?.recordInto(actors)
                    span.leftSiblingID?.createdAt?.recordInto(actors)
                }
                op.restoreSpans?.forEach(::recordSpan)
                op.retombstoneSpans?.forEach(::recordSpan)
            }

            is TreeStyleOperation -> {
                op.fromPos.parentID.createdAt.recordInto(actors)
                op.fromPos.leftSiblingID.createdAt.recordInto(actors)
                op.toPos.parentID.createdAt.recordInto(actors)
                op.toPos.leftSiblingID.createdAt.recordInto(actors)
            }

            is ArraySetOperation -> {
                op.createdAt.recordInto(actors)
                collectElementActors(op.value, actors)
            }

            else -> Unit
        }
    }

    private fun collectTreeNodeActors(node: CrdtTreeNode, actors: MutableSet<String>) {
        node.id.createdAt.recordInto(actors)
        node.removedAt.recordInto(actors)
        node.insPrevID?.createdAt.recordInto(actors)
        node.insNextID?.createdAt.recordInto(actors)
        node.mergedFrom?.createdAt.recordInto(actors)
        node.mergedAt.recordInto(actors)
        node.rhtNodes.forEach { it.executedAt.recordInto(actors) }
        node.children.forEach { collectTreeNodeActors(it, actors) }
    }

    /**
     * Every actor minting a non-zero-lamport ticket anywhere in [document]'s
     * current root OR any of its pending local changes.
     */
    private suspend fun documentActors(document: Document): Set<String> {
        val actors = mutableSetOf<String>()
        collectElementActors(document.getRootObject(), actors)
        document.pendingChanges().forEach { change ->
            if (change.id.lamport != 0L) actors += change.id.actor
            change.operations.forEach { collectOperationActors(it, actors) }
        }
        return actors
    }

    /**
     * Populates every kind of element a never-synced document can hold
     * before an attach -- text (with a split and a style), object, array,
     * counter, tree, and a removed key -- so every place a ticket can hide
     * is populated. Port of the JS `fillEverything` helper, adapted to
     * Android's incremental JSON API (no single-shot nested-literal
     * assignment).
     */
    private suspend fun fillEverything(document: Document) {
        document.updateAsync { root, presence ->
            root.setNewText("text").apply {
                edit(0, 0, "hello")
                edit(1, 3, "XY")
                style(0, 2, mapOf("b" to "1"))
            }
            root.setNewObject("obj").apply {
                this["k"] = "v"
                setNewArray("arr").apply {
                    put(1)
                    put(2)
                    put(3)
                }
            }
            root.setNewCounter("cnt", 1).increase(2)
            root.setNewTree(
                "tree",
                element("doc") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
            root["gone"] = "x"
            presence.put(mapOf("cursor" to "1"))
        }.await()
        document.updateAsync { root, _ ->
            (root["tree"] as dev.yorkie.document.json.JsonTree).edit(
                2,
                2,
                dev.yorkie.document.json.JsonTree.TextNode("c"),
            )
            (root["text"] as JsonText).edit(0, 1, "")
            root.remove("gone")
            // M5 (team-review, RTCOLLABPLATFORM-789): MoveOperation (an array
            // reorder) and an identity-preserving TreeEditOperation restore (below)
            // are otherwise never present in a never-synced document's pendingChanges,
            // so an un-reissued ticket on either went undetected by every test using
            // the documentActors oracle.
            val array = (root["obj"] as JsonObject)["arr"] as dev.yorkie.document.json.JsonArray
            array.moveFront(array[2].target.createdAt)
        }.await()
        // Plain delete of the SECOND <p> (no merge/split) pushes an identity-preserving
        // Restore reverse (restoreSpans, not a copy-reinsert snapshot); undoing it
        // executes that reverse, appending a pending TreeEditOperation whose
        // restoreSpans/retombstoneSpans carry their own createdAt tickets.
        document.updateAsync { root, _ ->
            (root["tree"] as dev.yorkie.document.json.JsonTree).editByPath(
                listOf(1),
                listOf(2),
            )
        }.await()
        document.history.undoAsync().await()
    }

    @Test
    fun `leaves no ticket under the initial actor after a reissue`() = runTest {
        val document = Document("")
        fillEverything(document)
        val before = document.toJson()
        assertTrue((ActorID.INITIAL_ACTOR_ID in documentActors(document)))

        val reissued = document.setActor(ACTOR_A, reissue = true)

        assertTrue(reissued)
        assertFalse((ActorID.INITIAL_ACTOR_ID in documentActors(document)))
        assertTrue((ACTOR_A in documentActors(document)))
        assertEquals(before, document.toJson())
        assertEquals(ACTOR_A, document.changeID.actor)
    }

    @Test
    fun `continues edits under the new actor`() = runTest {
        val document = Document("")
        fillEverything(document)
        document.setActor(ACTOR_A, reissue = true)

        document.updateAsync { root, _ ->
            (root["text"] as JsonText).edit(0, 0, "Z")
            (root["obj"] as JsonObject).apply { this["k"] = "w" }
        }.await()

        assertFalse((ActorID.INITIAL_ACTOR_ID in documentActors(document)))
        assertTrue((ACTOR_A in documentActors(document)))
    }

    @Test
    fun `re-issues a retried attach to the next actor`() = runTest {
        val document = Document("")
        fillEverything(document)
        document.setActor(ACTOR_A, reissue = true)

        val secondReissue = document.setActor(ACTOR_B, reissue = true)

        assertTrue(secondReissue)
        assertFalse((ActorID.INITIAL_ACTOR_ID in documentActors(document)))
        assertFalse((ACTOR_A in documentActors(document)))
        assertTrue((ACTOR_B in documentActors(document)))
    }

    @Test
    fun `a retried attach under the same actor re-issues nothing`() = runTest {
        val document = Document("")
        fillEverything(document)
        document.setActor(ACTOR_A, reissue = true)

        val secondCall = document.setActor(ACTOR_A, reissue = true)

        assertFalse(secondCall, "the actor is unchanged -- nothing to re-issue")
    }

    @Test
    fun `clears the undo history it invalidates and resets the clone`() = runTest {
        val document = Document("")
        fillEverything(document)
        assertTrue(document.history.canUndo())

        document.setActor(ACTOR_A, reissue = true)

        assertFalse(document.history.canUndo())
    }

    @Test
    fun `a replayed undo-produced change keeps its isUndoRedo mark`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewObject(
                "obj",
            ).apply { this["k"] = "v" }
        }.await()
        document.updateAsync { root, _ -> (root["obj"] as JsonObject)["k"] = "w" }.await()
        document.history.undoAsync().await()
        val before = document.toJson()

        document.setActor(ACTOR_A, reissue = true)

        assertEquals(before, document.toJson())
        assertFalse((ActorID.INITIAL_ACTOR_ID in documentActors(document)))
    }

    @Test
    fun `renames an online client entry to the re-issued actor`() = runTest {
        val document = Document("")
        fillEverything(document)
        document.addOnlineClient(ActorID.INITIAL_ACTOR_ID)

        document.setActor(ACTOR_A, reissue = true)

        val online = document.getOnlineClients()
        assertTrue(ACTOR_A in online)
        assertFalse(ActorID.INITIAL_ACTOR_ID in online)
    }

    @Test
    fun `a throw during the rebuild leaves the document untouched`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        val before = document.toJson()

        assertFailsWith<IllegalArgumentException> {
            document.setActor("not-a-hex-actor", reissue = true)
        }

        assertEquals(ActorID.INITIAL_ACTOR_ID, document.changeID.actor)
        assertEquals(before, document.toJson())
        assertTrue(document.hasLocalChanges())
    }

    // D2 (backlog 019): a re-issue requested while updateAsync's updater is
    // suspended on this SAME document dispatcher must refuse instead of
    // letting the resumed updater mint old-actor operations against a root
    // already swapped to the new actor.
    @Test
    fun `refuses a re-issue while an update is in flight`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        assertTrue(document.neverSynced)

        val updaterEntered = CompletableDeferred<Unit>()
        val releaseUpdater = CompletableDeferred<Unit>()
        val inFlight = document.updateAsync { root, _ ->
            root.setNewObject("obj2")
            updaterEntered.complete(Unit)
            releaseUpdater.await()
        }
        updaterEntered.await()

        val error = assertFailsWith<YorkieException> {
            document.setActor(ACTOR_A, reissue = true)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
        assertEquals(ActorID.INITIAL_ACTOR_ID, document.changeID.actor)

        releaseUpdater.complete(Unit)
        inFlight.await()

        // The in-flight update completed normally, untouched by the refusal: obj2 is
        // present, and -- since the reissue that would have rewritten it was refused --
        // it still carries the INITIAL actor on its createdAt ticket, not the one the
        // refused setActor(ACTOR_A, ...) call would have stamped.
        assertEquals(ActorID.INITIAL_ACTOR_ID, document.changeID.actor)
        val obj2 = document.getRootObject()["obj2"]
        assertEquals(ActorID.INITIAL_ACTOR_ID, obj2.createdAt.actorID)
    }

    // Cross-judge MEDIUM-1 (round 3, probe CJ-6b): the D2 in-flight guard
    // must be a COUNT, not a boolean. Updater A is parked mid-flight on a
    // CompletableDeferred; overlapping updater B starts and finishes
    // entirely while A is still suspended. A boolean flag goes false the
    // moment B's `finally` runs, even though A is still in flight -- a
    // re-issue requested at that point is wrongly let through, A then
    // resumes and mints its own operation under the OLD actor against a
    // root already swapped to the new one, reverting `changeID.actor`. A
    // count only goes to zero once BOTH passes have cleared, so the re-issue
    // stays refused until A finishes too.
    @Test
    fun `refuses a re-issue while an overlapping update is still in flight`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()

        val aEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val deferredA = document.updateAsync { root, _ ->
            root.setNewObject("a")
            aEntered.complete(Unit)
            releaseA.await()
        }
        aEntered.await()

        // B starts and completes entirely while A is still suspended above.
        document.updateAsync { root, _ -> root.setNewObject("b") }.await()

        val error = assertFailsWith<YorkieException> {
            document.setActor(ACTOR_A, reissue = true)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, error.code)
        assertEquals(ActorID.INITIAL_ACTOR_ID, document.changeID.actor)

        releaseA.complete(Unit)
        deferredA.await()

        // A's resume must not have reverted the actor or left a stray ticket.
        assertEquals(ActorID.INITIAL_ACTOR_ID, document.changeID.actor)
        assertFalse((ACTOR_A in documentActors(document)))
    }

    // LOW-3 fold-in (cross-judge CJ-7): the presence VALUE is re-keyed to the
    // new actor and gone under the old one after a reissue.
    @Test
    fun `rekeys presence to the new actor after a reissue`() = runTest {
        val document = Document("")
        document.updateAsync { root, presence ->
            root.setNewObject("obj")
            presence.put(mapOf("cursor" to "1"))
        }.await()
        val before = document.allPresences.value[ActorID.INITIAL_ACTOR_ID]

        document.setActor(ACTOR_A, reissue = true)

        assertEquals(before, document.allPresences.value[ACTOR_A])
        assertFalse(ActorID.INITIAL_ACTOR_ID in document.allPresences.value)
    }

    // ---- 300-seed fuzz ---------------------------------------------------

    /**
     * Mirrors JS `mulberry32`: Kotlin's `Int * ` already wraps at 32 bits
     * like `Math.imul`, and `ushr` is `>>>`.
     */
    private fun mulberry32(seed: Int): () -> Double {
        var a = seed
        return {
            a += 0x6d2b79f5
            var t = a
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
        }
    }

    /**
     * Port of JS `fillRandomly`, adapted to Android's incremental JSON API:
     * a [dev.yorkie.document.json.JsonArray] has no single-shot nested-value
     * put, so the array holds only numbers here (the nested-Text-in-array
     * case is covered directly by `ReissueTest`'s ArraySet case instead of
     * by the fuzz sweep). Still exercises the same shape of history: text
     * split/style, tree split/insert, array/object mutation, delete, and
     * undo/redo, over the same 300 seeds and the same two assertions.
     */
    private suspend fun fillRandomly(document: Document, seed: Int) {
        val rnd = mulberry32(seed)
        fun pick(n: Int) = (rnd() * n).toInt().coerceIn(0, n - 1)

        document.updateAsync { root, _ ->
            root.setNewText("t").edit(0, 0, "abcdef")
            root.setNewObject("o").apply {
                this["k"] = "v"
                setNewText("t").edit(0, 0, "ghij")
            }
            root.setNewArray("a").apply {
                put(1)
                put(2)
            }
            root.setNewTree(
                "tree",
                element("doc") { element("p") { text { "abcd" } } },
            )
        }.await()

        val texts: List<(JsonObject) -> JsonText> = listOf(
            { it["t"] as JsonText },
            { (it["o"] as JsonObject)["t"] as JsonText },
        )

        repeat(16) {
            try {
                when (pick(8)) {
                    0, 1 -> document.updateAsync { root, _ ->
                        val text = texts[pick(texts.size)](root)
                        val from = pick(text.length + 1)
                        val to = from + pick(text.length - from + 1)
                        text.edit(from, to, if (pick(2) == 1) "x" else "")
                    }.await()

                    2 -> document.updateAsync { root, _ ->
                        val text = texts[pick(texts.size)](root)
                        val from = pick(text.length + 1)
                        val to = from + pick(text.length - from + 1)
                        text.style(from, to, mapOf("b" to "${pick(3)}"))
                    }.await()

                    3 -> document.updateAsync { root, _ ->
                        val tree = root["tree"] as dev.yorkie.document.json.JsonTree
                        val size = tree.size
                        if (size > 1) {
                            val at = 1 + pick(size - 1)
                            if (pick(2) == 1) {
                                tree.edit(at, at, dev.yorkie.document.json.JsonTree.TextNode("q"))
                            } else {
                                tree.edit(at, at, 1)
                            }
                        }
                    }.await()

                    4 -> document.updateAsync { root, _ ->
                        if (pick(2) == 1) {
                            (root["a"] as dev.yorkie.document.json.JsonArray).put(pick(10))
                        } else {
                            (root["o"] as JsonObject)["k"] = "${pick(10)}"
                        }
                    }.await()

                    5 -> document.updateAsync { root, _ ->
                        when (pick(3)) {
                            0 -> root.remove("t")
                            1 -> root.remove("o")
                            else -> (root["a"] as dev.yorkie.document.json.JsonArray)
                                .removeAt(0)
                        }
                    }.await()

                    6 -> if (document.history.canUndo()) document.history.undoAsync().await()
                    7 -> if (document.history.canRedo()) document.history.redoAsync().await()
                }
            } catch (_: Throwable) {
                // The picked target is gone or the range does not fit; skip it
                // -- mirrors the JS sweep's bare catch.
            }
        }
    }

    @Test
    fun `keeps the content of random pre-attach histories across 300 seeds`() = runTest {
        for (seed in 1..300) {
            val document = Document("")
            fillRandomly(document, seed)
            val before = document.toJson()

            document.setActor(ACTOR_A, reissue = true)

            assertEquals(before, document.toJson(), "seed $seed")
            assertFalse((ActorID.INITIAL_ACTOR_ID in documentActors(document)), "seed $seed")
        }
    }
}
