package dev.yorkie.document

import android.util.Base64
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.traverseAll
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `split_ticket_test.ts` (JS SDK `b69169c0`, #1404 -- extends the
 * pre-existing `4ec66cc0` cases with three new ones) as JVM tests: an
 * element split's tickets are recorded on the operation and re-stamped with
 * the actor when it is set after the edit, and a split reverse in an
 * undo entry mints its own tickets instead of reconstructing them from
 * `executedAt`, which would otherwise land on the NEXT operation's ticket in
 * the same undo entry and leave two live nodes under one id on every
 * replica. The last case is the Android dispatch pin (the
 * Kotlin analogue of iOS's static-dispatch finding): `Operation.setActor`
 * must be `open` and overridden by [TreeEditOperation] so a caller holding
 * only an [Operation]-typed reference -- every call site today -- still
 * re-stamps the split tickets, not just `executedAt`; mirrors iOS
 * `TreeEditOperationSetActorTests`.
 */
class TreeSplitTicketTest {

    private fun actorOf(n: Int) = "00000000000000000000000$n"

    /**
     * `<r><d><p>ab</p></d><d><p>cd</p></d><d><p>ef</p></d></r>`, the shape
     * two successive level-2 merges need, and so the shape that puts two
     * splitLevel-2 reverses into one undo entry (JS `threeBlockDoc`).
     */
    private suspend fun threeBlockDoc(): Document {
        val doc = Document("doc")
        doc.setActor(actorOf(1))
        doc.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    listOf("ab", "cd", "ef").forEach { value ->
                        element("d") { element("p") { text { value } } }
                    }
                },
            )
        }.await()
        return doc
    }

    /** Ids naming more than one (live or tombstoned) node under `t`. */
    private fun duplicatedIds(doc: Document): List<CrdtTreeNodeID> {
        val tree = doc.getRootObject()["t"] as CrdtTree
        val counts = mutableMapOf<CrdtTreeNodeID, Int>()
        traverseAll(tree.root) { node, _ -> counts[node.id] = (counts[node.id] ?: 0) + 1 }
        return counts.filterValues { it > 1 }.keys.toList()
    }

    /** Ids of the live (non-tombstoned) nodes under `t`, in traversal order. */
    private fun liveIds(doc: Document): List<CrdtTreeNodeID> {
        val tree = doc.getRootObject()["t"] as CrdtTree
        val ids = mutableListOf<CrdtTreeNodeID>()
        traverseAll(tree.root) { node, _ -> if (!node.isRemoved) ids.add(node.id) }
        return ids
    }

    /**
     * Round-trips [this] through `toPBChangePack()` -> `toChangePack()`.
     * `ChangeID.versionVector` encodes its actor keys via
     * `android.util.Base64`, unmocked (a stub throwing "not mocked") under
     * plain JVM unit tests -- bridges both directions to the real
     * `java.util.Base64` codec for the duration of the call, mirroring
     * `dev.yorkie.helper.crossSyncOverWire`.
     */
    private fun ChangePack.wireRoundTrip(): ChangePack {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        return try {
            toPBChangePack().toChangePack()
        } finally {
            unmockkStatic(Base64::class)
        }
    }

    // Pre-existing 4ec66cc0 case, regression lock -- this test exercises
    // the forward-edit path, not executeUndoRedoInternal, so it is already
    // green at ea06769d; a red run is not constructible for this case.
    @Test
    fun `does not land on the content the same edit inserts`() = runTest {
        val doc = Document("doc")
        doc.updateAsync { root, _ ->
            root.setNewTree("t", element("r") { element("p") { text { "ab" } } })
        }.await()

        doc.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("t")
            tree.edit(2, 2, 1, text { "q" })
            tree.edit(1, 1, text { "z" })
        }.await()

        assertEquals(emptyList(), duplicatedIds(doc))
    }

    // Pre-existing 4ec66cc0 case, regression lock -- OperationConverter
    // already carries splitTickets end to end (OperationConverter.kt:158/:309);
    // a red run is not constructible for this case.
    @Test
    fun `survives the round trip to the wire`() = runTest {
        val doc = Document("doc")
        doc.updateAsync { root, _ ->
            root.setNewTree("t", element("r") { element("p") { text { "ab" } } })
        }.await()
        doc.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(2, 2, 1, text { "q" })
        }.await()

        val pack = doc.createChangePack()
        val restored = pack.wireRoundTrip()

        val sent = pack.changes.flatMap { it.operations }
            .filterIsInstance<TreeEditOperation>()
            .flatMap { it.splitTickets }
        val received = restored.changes.flatMap { it.operations }
            .filterIsInstance<TreeEditOperation>()
            .flatMap { it.splitTickets }

        assertTrue(sent.isNotEmpty(), "the edit split an element, so it issued tickets")
        assertEquals(sent, received, "a replica reads back the tickets the originator issued")
    }

    // New case: red at ea06769d -- the split ticket's actor stays the
    // placeholder INITIAL_ACTOR_ID after setActor, because Operation.setActor
    // is final and TreeEditOperation has no override to re-stamp splitTickets.
    @Test
    fun `re-stamps the tickets when the actor is set after the edit`() = runTest {
        val doc = Document("doc")
        doc.updateAsync { root, _ ->
            root.setNewTree("t", element("r") { element("p") { text { "ab" } } })
        }.await()
        doc.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(2, 2, 1, text { "q" })
        }.await()

        val actorID = actorOf(9)
        doc.setActor(actorID)

        val pack = doc.createChangePack()
        val sent = pack.changes.flatMap { it.operations }
            .filterIsInstance<TreeEditOperation>()
            .flatMap { it.splitTickets }
        assertTrue(sent.isNotEmpty(), "the edit split an element, so it issued tickets")
        assertTrue(
            sent.all { it.actorID == actorID },
            "a split ticket carries the change its edit belongs to: $sent",
        )

        val received = pack.wireRoundTrip().changes.flatMap { it.operations }
            .filterIsInstance<TreeEditOperation>()
            .flatMap { it.splitTickets }
        assertEquals(sent, received, "a replica reads back the tickets the originator issued")
    }

    // New case: red at ea06769d -- `lamport 3 delimiter 3` occurs twice
    // after undo (duplicatedIds non-empty), because the second splitLevel-2
    // reverse's fallback ticket reconstruction lands on the ticket the first
    // reverse already consumed in this same undo entry.
    @Test
    fun `gives each split reverse in one undo entry its own tickets`() = runTest {
        val doc = threeBlockDoc()
        val before = doc.getRoot().getAs<JsonTree>("t").toXml()

        doc.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("t")
            tree.edit(4, 8)
            tree.edit(6, 10)
        }.await()
        assertEquals("<r><d><p>abcdef</p></d></r>", doc.getRoot().getAs<JsonTree>("t").toXml())

        doc.history.undoAsync().await()
        assertEquals(before, doc.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(emptyList(), duplicatedIds(doc), "no duplicate id after the first undo")

        // A redo replays the merges and a second undo mints the splits
        // again, each from its own tickets.
        doc.history.redoAsync().await()
        assertEquals("<r><d><p>abcdef</p></d></r>", doc.getRoot().getAs<JsonTree>("t").toXml())
        doc.history.undoAsync().await()
        assertEquals(before, doc.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(emptyList(), duplicatedIds(doc), "no duplicate id after redo then undo again")
    }

    // New case (wire twin): the same undo entry, applied to a fresh
    // replica across an actual protobuf round trip, agrees on XML, live ids
    // and has no duplicate id either.
    @Test
    fun `applies such an undo entry on a replica across the wire`() = runTest {
        val doc = threeBlockDoc()
        doc.updateAsync { root, _ ->
            val tree = root.getAs<JsonTree>("t")
            tree.edit(4, 8)
            tree.edit(6, 10)
        }.await()
        doc.history.undoAsync().await()

        val replica = Document("doc")
        replica.setActor(actorOf(2))
        val restored = doc.createChangePack().wireRoundTrip()
        replica.applyChangePack(
            ChangePack(
                replica.getKey(),
                CheckPoint(0, 0u),
                restored.changes,
                null,
                false,
                VersionVector(),
            ),
        )

        assertEquals(
            doc.getRoot().getAs<JsonTree>("t").toXml(),
            replica.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals(liveIds(doc), liveIds(replica))
        assertEquals(emptyList(), duplicatedIds(replica))
    }

    // Android dispatch pin: setActor called through an Operation-typed
    // reference -- the shape every real call site uses (Change.setActor
    // iterates List<Operation>) -- re-stamps executedAt AND every split
    // ticket; an empty ticket list stays empty. red at ea06769d (setActor is
    // final on Operation; TreeEditOperation has no override at all, so this
    // would not even compile before the fix).
    @Test
    fun `setActor through an Operation reference re-stamps every split ticket`() {
        val initialActor = TimeTicket.InitialTimeTicket.actorID
        val anchor = CrdtTreePos(
            CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0),
            CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0),
        )
        val tickets = listOf(
            TimeTicket(1L, 1u, initialActor),
            TimeTicket(1L, 2u, initialActor),
        )
        val op: Operation = TreeEditOperation(
            parentCreatedAt = TimeTicket.InitialTimeTicket,
            fromPos = anchor,
            toPos = anchor,
            contents = null,
            splitLevel = 2,
            executedAt = TimeTicket(1L, 0u, initialActor),
            splitTickets = tickets,
        )

        val newActor = actorOf(9)
        op.setActor(newActor)

        val reStamped = op as TreeEditOperation
        assertEquals(newActor, reStamped.executedAt.actorID)
        assertTrue(
            reStamped.splitTickets.all { it.actorID == newActor },
            "every split ticket must carry the new actor: ${reStamped.splitTickets}",
        )
        // Lamport/delimiter stay untouched -- only the actor component moves.
        assertEquals(
            tickets.map {
                it.lamport to it.delimiter
            },
            reStamped.splitTickets.map { it.lamport to it.delimiter },
        )

        // An empty list stays empty (an ordinary, non-splitting edit).
        val plainOp: Operation = TreeEditOperation(
            parentCreatedAt = TimeTicket.InitialTimeTicket,
            fromPos = anchor,
            toPos = anchor,
            contents = null,
            splitLevel = 0,
            executedAt = TimeTicket(1L, 0u, initialActor),
        )
        plainOp.setActor(newActor)
        assertTrue((plainOp as TreeEditOperation).splitTickets.isEmpty())
    }
}
