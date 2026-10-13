package dev.yorkie.document

import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Port of yorkie-js-sdk `tree_restore_ticket_test.ts` (v0.7.23, `248551a1`,
 * #1364, yorkie#2008; a port itself of the Go
 * `TestTreeRestoreAgreesOnTheTombstoneTicketAcrossDeliveryOrders`).
 *
 * Ordering harness for [dev.yorkie.document.crdt.CrdtTree.recreateFromSpan]:
 * three concurrent changes (two competing `<p>` removals racing on LWW, plus
 * an unrelated undo that restores a purged text node under `<p>`) are
 * recorded once from one causal history, then replayed into six fresh
 * observers in all six orderings. A CRDT owes the same result for all six;
 * comparing `toXml()` alone proves nothing here (it is identical in every
 * order under both the buggy and fixed code), so the real assertion reads
 * the restored node's `removedAt` TICKET off `findFloorNode` — the recreated
 * node is a childless leaf no visible-content comparison would ever
 * distinguish — and checks it against its never-purged siblings "a"/"d",
 * which the winning `<p>` removal already stamped directly.
 *
 * Android exposes `Document.createChangePack` / `applyChangePack` (internal,
 * same module as this test — see `dev.yorkie.helper.crossSync`'s identical
 * record/replay shape), so the JS harness's capture-once/replay-in-order
 * approach ports directly. Each order also runs a collection pass and
 * compares docSize across every order afterward, matching the JS original's
 * own assertions (see `docSizesByOrder` below).
 */
class TreeRestoreTicketOrderTest {

    private fun actorOf(n: Int) = "0000000000000000000000" + n.toString().padStart(2, '0')

    private val actorAuthorA = actorOf(1)
    private val actorRemoverB = actorOf(2)
    private val actorRemoverC = actorOf(5)
    private val observerActors = (0 until 6).map { actorOf(10 + it) }

    private fun newReplica(actor: String): Document {
        return Document("tree-restore-ticket").apply { setActor(actor) }
    }

    /**
     * Drains [from]'s pending local changes so they can be replayed into
     * several observers in different orders, then self-acks so a later call
     * on the same replica does not re-send them. Mirrors
     * [dev.yorkie.helper.crossSync]'s self-ack shape.
     */
    private suspend fun recordChanges(from: Document, what: String): List<Change> {
        val pack = from.createChangePack()
        assertTrue(pack.changes.isNotEmpty(), "$what produced no change to deliver")
        val lastSeq = pack.changes.last().id.clientSeq
        from.applyChangePack(
            ChangePack(
                from.getKey(),
                CheckPoint(0, lastSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        return pack.changes
    }

    /**
     * Delivers recorded [changes] to [to]. The neutral checkpoint (clientSeq
     * 0) keeps the receiver's own pending local changes; an empty
     * [VersionVector] keeps garbage collection out of delivery — collection
     * is driven explicitly so each scenario controls when a tombstone
     * becomes a purge.
     */
    private suspend fun deliverInOrder(to: Document, changes: List<Change>) {
        to.applyChangePack(
            ChangePack(to.getKey(), CheckPoint(0, 0u), changes, null, false, VersionVector()),
        )
    }

    private suspend fun Document.xml(key: String = "t"): String = getRoot().getAs<JsonTree>(
        key,
    ).toXml()

    private fun tree(doc: Document): CrdtTree = doc.getRootObject()["t"] as CrdtTree

    /**
     * Renders a node's liveness as the TICKET it carries, not a boolean: two
     * replicas can both report `isRemoved == true` while holding different
     * `removedAt`, and that ticket is what `canDelete` compares — the
     * difference decides which replica purges the node on which GC pass.
     */
    private fun tombstoneOf(doc: Document, id: CrdtTreeNodeID): String {
        val node = tree(doc).findFloorNode(id) ?: return "absent"
        if (node.id != id) return "absent"
        return node.removedAt?.toString() ?: "live"
    }

    /** Every node reachable from the root right now, tombstones included. */
    private fun reachableCount(doc: Document): Int {
        var count = 0
        tree(doc).indexTree.traverseAll { _, _ -> count++ }
        return count
    }

    private fun firstTicket(changes: List<Change>): TimeTicket {
        for (change in changes) {
            for (op in change.operations) {
                return op.executedAt
            }
        }
        error("no operation to read a ticket from")
    }

    private class TicketFixture(
        val setup: List<Change>,
        val removeLow: List<Change>,
        val removeHigh: List<Change>,
        val restore: List<Change>,
        val lowAt: TimeTicket,
        val highAt: TimeTicket,
        val textID: CrdtTreeNodeID,
        val parentID: CrdtTreeNodeID,
        val siblingIDs: List<CrdtTreeNodeID>,
        val actors: List<String>,
    )

    /**
     * One logical history with THREE concurrent changes, recorded so every
     * delivery order replays identical changes: `removeLow` (B removes
     * `<p>`), `removeHigh` (C removes `<p>` concurrently with a HIGHER
     * ticket), `restore` (A undoes its own earlier removal of "bc",
     * concurrently). None of the three authors has seen either of the
     * others, so a replica may legitimately receive them in any of the six
     * orders.
     */
    private suspend fun newTicketFixture(): TicketFixture {
        val a = newReplica(actorAuthorA)
        val b = newReplica(actorRemoverB)
        val c = newReplica(actorRemoverC)
        // Every actor that will ever hold this document must be in the
        // vector, observers included: a collection pass only purges what the
        // whole cluster is past.
        val actors = listOf(actorAuthorA, actorRemoverB, actorRemoverC) + observerActors

        a.updateAsync { root, _ ->
            root.setNewTree("t", element("r") { element("p") { text { "abcd" } } })
        }.await()
        a.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 4) }.await()
        assertEquals("<r><p>ad</p></r>", a.xml())

        var textID: CrdtTreeNodeID? = null
        var parentID: CrdtTreeNodeID? = null
        val siblingIDs = mutableListOf<CrdtTreeNodeID>()
        tree(a).indexTree.traverseAll { node, _ ->
            when {
                node.isText && node.value == "bc" -> textID = node.id
                node.isText && (node.value == "a" || node.value == "d") -> siblingIDs.add(node.id)
                node.type == "p" -> parentID = node.id
            }
        }
        requireNotNull(textID) { "the tombstoned \"bc\" should be nameable pre-purge" }
        requireNotNull(parentID) { "the enclosing <p> should be nameable" }
        assertEquals(2, siblingIDs.size, "both never-purged siblings are named")

        val setup = recordChanges(a, "the setup edits")

        // B and C both need the setup collected, so "bc" is PURGED on them
        // too. Otherwise their removal would merely tombstone it and A's
        // restore would take the un-tombstone path instead of the recreate
        // path under test.
        deliverInOrder(b, setup)
        deliverInOrder(c, setup)
        listOf(a, b, c).forEach { it.garbageCollect(maxVectorOf(actors)) }
        assertEquals("<r><p>ad</p></r>", b.xml())
        assertEquals("<r><p>ad</p></r>", c.xml())
        assertEquals(
            "absent",
            tombstoneOf(a, textID!!),
            "the removed text must be PURGED, not merely tombstoned — otherwise the undo " +
                "takes the un-tombstone path and never reaches recreateFromSpan",
        )

        // Neither remover has seen the other, so the two removals are
        // concurrent and LWW decides which tombstone survives on every
        // replica.
        b.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 4) }.await()
        val removeLow = recordChanges(b, "b's removal of <p>")
        c.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 4) }.await()
        val removeHigh = recordChanges(c, "c's concurrent removal of <p>")

        a.history.undoAsync().await()
        val restore = recordChanges(a, "a's undo of its own text removal")

        return TicketFixture(
            setup,
            removeLow,
            removeHigh,
            restore,
            lowAt = firstTicket(removeLow),
            highAt = firstTicket(removeHigh),
            textID = textID!!,
            parentID = parentID!!,
            siblingIDs = siblingIDs,
            actors = actors,
        )
    }

    /** Returns a replica holding the collected setup, ready to receive the three concurrent changes. */
    private suspend fun observer(f: TicketFixture, actor: String): Document {
        val doc = newReplica(actor)
        deliverInOrder(doc, f.setup)
        doc.garbageCollect(maxVectorOf(f.actors))
        assertEquals("<r><p>ad</p></r>", doc.xml())
        return doc
    }

    @Test
    fun `restored node agrees with the winning removal ticket in all six delivery orders`() =
        runTest {
            val f = newTicketFixture()
            // The premise of the whole scenario: if the tickets came out the
            // other way round, "the later removal overwrites the parent's
            // tombstone" step never happens and the test would measure nothing.
            assertTrue(
                f.highAt > f.lowAt,
                "fixture needs c's removal to win the LWW race: low=${f.lowAt} high=${f.highAt}",
            )

            val steps = listOf(f.removeLow, f.removeHigh, f.restore)
            val stepNames = listOf("removeLow", "removeHigh", "restore")
            val orders = listOf(
                listOf(0, 1, 2),
                listOf(0, 2, 1),
                listOf(1, 0, 2),
                listOf(1, 2, 0),
                listOf(2, 0, 1),
                listOf(2, 1, 0),
            )

            val high = f.highAt.toString()
            val docSizesByOrder = mutableListOf<DocSize>()
            orders.forEachIndexed { i, order ->
                val label = order.joinToString("->") { stepNames[it] }
                val doc = observer(f, observerActors[i])
                order.forEach { idx -> deliverInOrder(doc, steps[idx]) }

                val parent = tombstoneOf(doc, f.parentID)
                val restored = tombstoneOf(doc, f.textID)

                // Pure LWW, no restore involved: a divergence here means the
                // FIXTURE broke, and the next finding could not be trusted.
                assertEquals(
                    high,
                    parent,
                    "[$label] <p> should settle on the winning removal ticket by plain LWW",
                )
                // The question this file exists to answer.
                assertEquals(
                    high,
                    restored,
                    "[$label] the restored node's tombstone ticket should be the winning " +
                        "removal's (low=${f.lowAt})",
                )
                // The sharpest witness: "a"/"d" were never purged, so the
                // removal that swept <p> wrote its ticket straight onto them.
                // The restored node has to agree with what it would have
                // carried had it never been purged.
                f.siblingIDs.forEach { siblingID ->
                    assertEquals(
                        restored,
                        tombstoneOf(doc, siblingID),
                        "[$label] restored node should agree with its never-purged sibling " +
                            "$siblingID",
                    )
                }

                // "Registered implies reachable" — every node CrdtTree's
                // nodeMapByID holds must have a path to the root, before a
                // collection pass has run at all.
                assertEquals(
                    tree(doc).nodeSize,
                    reachableCount(doc),
                    "[$label] every registered node must be reachable from the root (no orphan)",
                )

                // A collection pass is where production is actually
                // exercised, not just internal bookkeeping: every actor in
                // the vector (authors and observers alike) has "seen" every
                // step by construction, so maxVectorOf(f.actors) must sweep
                // <p> and the recreated node clean on every order. Purging
                // <p> alone unlinks it from the root but does not recurse
                // into its children ([CrdtTree.delete] removes only the one
                // node it is given) — if the recreated node's OWN pending
                // gcOnlySize pair were never registered, <p>'s purge would
                // detach the whole subtree from the root while leaving the
                // recreated node's id stuck in nodeMapByID forever: an
                // orphan the pre-collection check above cannot see, because
                // nothing has purged anything yet at that point.
                doc.garbageCollect(maxVectorOf(f.actors))
                assertEquals(
                    tree(doc).nodeSize,
                    reachableCount(doc),
                    "[$label] every registered node must still be reachable from the root " +
                        "after a collection pass (no orphan left behind by <p>'s purge)",
                )
                assertEquals(
                    0,
                    doc.garbageLength,
                    "[$label] a collection pass over every actor's max ticket must leave " +
                        "nothing to collect",
                )
                assertEquals(
                    DataSize(data = 0, meta = 0),
                    doc.getDocSize().gc,
                    "[$label] docSize.gc must drain to zero after collection",
                )
                docSizesByOrder += doc.getDocSize()
            }

            // The CRDT owes the SAME result regardless of delivery order:
            // every order's post-collection docSize must agree with every
            // other order's, not just its own internal bookkeeping.
            docSizesByOrder.drop(1).forEachIndexed { i, docSize ->
                assertEquals(
                    docSizesByOrder[0],
                    docSize,
                    "order ${i + 1} (${orders[i + 1]}) diverges from order 0's " +
                        "post-collection docSize",
                )
            }
        }
}
