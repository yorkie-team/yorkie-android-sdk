package dev.yorkie.document

import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Ports the canStyle/ordering/reverse-op-capture/gc/undo-gate cases from
 * yorkie-js-sdk `style_tombstone_test.ts` (e0609c7a, #1368, server twin
 * yorkie#2012, `6731bb6c`) as JVM Document-level tests: `canStyle` no
 * longer reads `removedAt` -- only whether the change
 * knew the node existed (`ticketKnown`) -- the reverse operation's previous
 * values come from the first LIVE node in range, falling back to the first
 * node when every node in range is a tombstone, the `nodeIsLive` gc deltas
 * flow through `accAttrWrite`, and `Document`'s undo/redo gate tests whether
 * an operation RAN instead of whether it produced an `OpInfo`.
 */
class StyleTombstoneConvergenceTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun crdtText(d: Document, key: String = "t"): CrdtText =
        d.getRootObject()[key] as CrdtText

    /**
     * Dumps every node of the text, live and tombstoned, with its
     * attributes -- including removed ones, marked with `*`. Mirrors JS
     * SDK's `nodeAttrs` test helper (`style_tombstone_test.ts:109-126`): two
     * replicas are compared on this rather than on rendered content, because
     * the disagreement #1368 fixes is invisible in the rendering until
     * something revives the tombstone.
     */
    private fun nodeAttrs(d: Document, key: String = "t"): List<String> {
        return crdtText(d, key).rgaTreeSplit.map { node ->
            val attrs = node.value.attributesWithTimeTicket
                .map { "${it.key}=${it.value}${if (it.isRemoved) "*" else ""}" }
                .sorted()
            val removedSuffix = if (node.isRemoved) " (removed)" else ""
            "\"${node.value.content}\"$removedSuffix [${attrs.joinToString(",")}]"
        }
    }

    /**
     * Pins both halves of `docSize` against a from-scratch rebuild, then
     * collects and pins that nothing is left over. Mirrors JS SDK
     * `assertLedgerExact` (`style_tombstone_test.ts:141-158`).
     */
    private fun assertLedgerExact(
        d: Document,
        msg: String,
        actors: List<String>,
    ) {
        val rebuilt = CrdtRoot(d.getRootObject().deepCopy())
        assertEquals(rebuilt.docSize.live, d.getDocSize().live, "$msg: live")
        assertEquals(rebuilt.docSize.gc, d.getDocSize().gc, "$msg: gc")

        d.garbageCollect(maxVectorOf(actors))
        assertEquals(0, d.garbageLength, "$msg: garbage left behind")
        assertEquals(DataSize(0, 0), d.getDocSize().gc, "$msg: collection left gc residue")
    }

    /**
     * A style concurrent with a removal, on both ticket orderings. The only
     * difference between the two cases is which actor's ticket sorts
     * higher, which must not decide whether the replicas agree. Mirrors JS
     * SDK's parametrized `lands on the tombstone everywhere` case
     * (`style_tombstone_test.ts:218-254`).
     */
    @Test
    fun `style concurrent with a removal lands on the tombstone on both replicas, either order`() =
        runTest {
            for (styleOnD1 in listOf(true, false)) {
                val d1 = Document("test-doc")
                val d2 = Document("test-doc")
                d1.setActor(actor1)
                d2.setActor(actor2)

                d1.updateAsync { root, _ ->
                    root.setNewText("t").edit(0, 0, "abcdefghij")
                }.await()
                crossSync(d1, d2)

                val styler = if (styleOnD1) d1 else d2
                val deleter = if (styleOnD1) d2 else d1
                styler.updateAsync { root, _ ->
                    root.getAs<JsonText>("t").style(4, 6, mapOf("b" to "1"))
                }.await()
                deleter.updateAsync { root, _ ->
                    root.getAs<JsonText>("t").edit(4, 6, "")
                }.await()
                crossSync(d1, d2)

                assertEquals(
                    listOf("\"abcd\" []", "\"ef\" (removed) [b=1]", "\"ghij\" []"),
                    nodeAttrs(d1),
                    "styleOnD1=$styleOnD1",
                )
                assertEquals(
                    nodeAttrs(d1),
                    nodeAttrs(d2),
                    "the replicas disagree on the tombstoned node's attributes " +
                        "(styleOnD1=$styleOnD1)",
                )

                // The style grew a node whose gc charge was taken when it was
                // removed; without moving those bytes through gc (accAttrWrite's
                // nodeIsLive parameter), the replica that received the style
                // reports a different size than the one that issued it.
                assertLedgerExact(d1, "on d1 (styleOnD1=$styleOnD1)", listOf(actor1, actor2))
                assertLedgerExact(d2, "on d2 (styleOnD1=$styleOnD1)", listOf(actor1, actor2))
            }
        }

    /**
     * Two clients delete the same run concurrently; a third, which has seen
     * only one of the two deletions, styles a range covering it. This is
     * the case that forces `canStyle` not to read `removedAt`: `removedAt`
     * is last-writer-wins and MUTABLE, while a style is evaluated once, when
     * it arrives, so any predicate over it answers differently depending on
     * which of the two concurrent removals has landed. Mirrors JS SDK
     * `agrees across delivery orders when two removals are concurrent`
     * (`style_tombstone_test.ts:418-479`).
     */
    @Test
    fun `agrees across delivery orders when two removals are concurrent`() = runTest {
        suspend fun grab(d: Document): List<Change> {
            val pack = d.createChangePack()
            val lastSeq = pack.changes.lastOrNull()?.id?.clientSeq ?: 0u
            d.applyChangePack(
                ChangePack(
                    d.getKey(),
                    CheckPoint(0, lastSeq),
                    emptyList(),
                    null,
                    false,
                    VersionVector(),
                ),
            )
            return pack.changes
        }

        suspend fun feed(d: Document, changes: List<Change>) {
            d.applyChangePack(
                ChangePack(
                    d.getKey(),
                    CheckPoint.InitialCheckPoint,
                    changes,
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }

        fun actor(hex: String): Document = Document("test-doc").apply { setActor(hex) }

        val seed = actor("000000000000000000000009")
        seed.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
        val p0 = grab(seed)

        val docB = actor("000000000000000000000001")
        val docC = actor("000000000000000000000002")
        val docX = actor("000000000000000000000003")
        for (d in listOf(docB, docC, docX)) feed(d, p0)

        docB.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
        val pB = grab(docB)
        docC.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
        val pC = grab(docC)

        // X knows B's removal but not C's.
        feed(docX, pB)
        docX.updateAsync { root, _ ->
            root.getAs<JsonText>(
                "t",
            ).style(0, 8, mapOf("b" to "1"))
        }.await()
        val pS = grab(docX)

        val orders = listOf(
            "C,B,S" to listOf(pC, pB, pS),
            "B,S,C" to listOf(pB, pS, pC),
            "B,C,S" to listOf(pB, pC, pS),
        )

        var first: List<String>? = null
        for ((name, seq) in orders) {
            val d = actor("00000000000000000000000a")
            feed(d, p0)
            for (batch in seq) feed(d, batch)

            val got = nodeAttrs(d)
            val expected = first
            if (expected == null) {
                first = got
            } else {
                assertEquals(expected, got, "delivery order $name diverges")
            }
        }
    }

    /**
     * A style range that opens on a tombstone: the reverse operation's
     * prior values must come from the first LIVE node, not from the dead
     * run the user had already deleted. Capturing from the tombstone would
     * make the undo write an attribute onto text that never carried it.
     * Local-only (no sync, asserts on rendered content only), so it does
     * not depend on the undo/redo executed-operations gate. Mirrors JS SDK
     * `does not restore a tombstone's attribute on undo`
     * (`style_tombstone_test.ts:563-579`).
     */
    @Test
    fun `undo of a style spanning a tombstone does not restore the tombstone's attribute`() =
        runTest {
            val d = Document("test-doc")
            d.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
            d.updateAsync { root, _ -> root.getAs<JsonText>("t").style(0, 4, mapOf("b" to "OLD")) }
                .await()
            d.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(0, 4, "") }.await()
            assertEquals("""[{"val":"efghij"}]""", crdtText(d).toJson())

            d.updateAsync { root, _ -> root.getAs<JsonText>("t").style(0, 4, mapOf("b" to "NEW")) }
                .await()
            d.history.undoAsync().await()
            assertEquals(
                """[{"val":"efgh"},{"val":"ij"}]""",
                crdtText(d).toJson(),
                "the undo restored an attribute the visible text never carried",
            )
        }

    /**
     * The six-operation local sequence from the issue, single actor, no
     * sync: step 4 styles a range spanning the node step 3 deleted, and it
     * lands there -- so step 5's undo strips it, and step 6 brings "ef" back
     * WITHOUT the attribute step 2 gave it. This is the cost of `canStyle`
     * not reading `removedAt`, deliberate per the JS commit. Mirrors JS SDK
     * `lands on a node the same actor already deleted`
     * (`style_tombstone_test.ts:178-216`).
     */
    @Test
    fun `local style after a same-actor delete lands on the tombstone, undo peels it back`() =
        runTest {
            val d = Document("test-doc")
            d.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
            d.updateAsync { root, _ ->
                root.getAs<JsonText>("t").style(4, 6, mapOf("b" to "OLDOLDOLDOLDOLD"))
            }.await()
            d.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
            d.updateAsync { root, _ -> root.getAs<JsonText>("t").style(0, 8, mapOf("b" to "NEW")) }
                .await()

            assertEquals(
                """[{"attrs":{"b":"NEW"},"val":"abcd"},{"attrs":{"b":"NEW"},"val":"ghij"}]""",
                crdtText(d).toJson(),
            )
            assertEquals(
                listOf("\"abcd\" [b=NEW]", "\"ef\" (removed) [b=NEW]", "\"ghij\" [b=NEW]"),
                nodeAttrs(d),
                "the dead node took the style too; RHT.set drops the value it held",
            )

            d.history.undoAsync().await()
            assertEquals(
                """[{"val":"abcd"},{"val":"ghij"}]""",
                crdtText(d).toJson(),
            )

            d.history.undoAsync().await()
            assertEquals(
                """[{"val":"abcd"},{"val":"ef"},{"val":"ghij"}]""",
                crdtText(d).toJson(),
                "the restored run lost the attribute it carried: the cost of the contract",
            )

            // The local path now reaches a tombstone, so it also books
            // through the gc half of accAttrWrite -- a branch no local
            // history could reach before #1368.
            assertLedgerExact(
                d,
                "after the local style reached a tombstone",
                listOf(d.changeID.actor),
            )
        }

    /**
     * The tree half of the convergence contract: a remote `style` whose
     * range was decided before a concurrent split follows `insNextID` to
     * the split siblings, one of which is a tombstone by the time it
     * arrives. `accAttrWrite`'s `nodeIsLive` parameter keeps that from
     * walking `live` into the negative. Mirrors JS SDK `keeps the ledger
     * exact for a remote style on a removed tree node`
     * (`style_tombstone_test.ts:349-373`).
     */
    @Test
    fun `remote tree style reaching a removed split sibling keeps the ledger exact`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "abcdefgh" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d2 styles a range decided before d1 splits.
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 10, mapOf("b" to "L".repeat(16)))
        }.await()

        // d1 splits the paragraph and removes the right half.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(5, 5, 1)
        }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(6, 11, 0)
        }.await()

        crossSync(d1, d2)

        assertEquals(
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            d2.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertTrue(d1.getDocSize().live.data >= 0, "live went negative")
        assertLedgerExact(d1, "on the replica that removed the node", listOf(actor1, actor2))
        assertLedgerExact(d2, "on the replica that issued the style", listOf(actor1, actor2))
    }

    /**
     * The same, but the tombstoned node already holds the key being written
     * and the incoming value is much shorter. The write has to debit the
     * superseded value as well as credit the installed one, and both halves
     * have to land in gc: booking either to live walks it down by the signed
     * difference between the two sizes, which is how this first went
     * negative. Mirrors JS SDK `shrinks an attribute on a tombstone without
     * touching live` (`style_tombstone_test.ts:265-292`).
     */
    @Test
    fun `shrinks an attribute on a tombstone without touching live`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonText>("t").style(4, 6, mapOf("b" to "L".repeat(20)))
        }.await()
        crossSync(d1, d2)

        d2.updateAsync { root, _ -> root.getAs<JsonText>("t").style(0, 8, mapOf("b" to "x")) }
            .await()
        d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
        crossSync(d1, d2)

        assertEquals(
            listOf("\"abcd\" [b=x]", "\"ef\" (removed) [b=x]", "\"gh\" [b=x]", "\"ij\" []"),
            nodeAttrs(d1),
        )
        assertEquals(nodeAttrs(d1), nodeAttrs(d2))
        assertTrue(d1.getDocSize().live.data >= 0, "live went negative")
        assertLedgerExact(d1, "on the replica that deleted the node", listOf(actor1, actor2))
        assertLedgerExact(d2, "on the replica that issued the style", listOf(actor1, actor2))
    }

    /**
     * A tombstoned node can also have an attribute REVIVED on it: a remote
     * removeStyle tombstones the key (the reverse of an undone style), a
     * later remote style sets it again. The pair the first one registered
     * carried zero -- the attribute's bytes were still inside the node's
     * charge at that point -- but the revive replaces it with a live node,
     * so the node's charge no longer covers it and the map entry has to give
     * back its own size on the way out. Mirrors JS SDK `gives an attribute
     * back its own size when a revive unregisters it`
     * (`style_tombstone_test.ts:309-331`).
     */
    @Test
    fun `gives an attribute back its own size when a revive unregisters it`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonText>("t").style(4, 6, mapOf("b" to "L".repeat(12)))
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonText>("t").style(0, 8, mapOf("b" to "x")) }
            .await()
        d2.history.undoAsync().await()
        d2.updateAsync { root, _ -> root.getAs<JsonText>("t").style(0, 8, mapOf("b" to "yy")) }
            .await()

        crossSync(d1, d2)

        assertEquals(nodeAttrs(d1), nodeAttrs(d2))
        assertLedgerExact(d1, "on the replica that deleted the node", listOf(actor1, actor2))
        assertLedgerExact(d2, "on the replica that issued the styles", listOf(actor1, actor2))
    }

    /**
     * The tree's `removeStyle` half of the same question. A remote
     * `removeStyle` whose range was decided before a concurrent split
     * follows `insNextID` to the split siblings, one of which is a
     * tombstone by the time it arrives. A live attribute on a tombstoned
     * node is not in `live` -- `CrdtTree.dataSize` excludes the node -- so
     * booking it out of `live` walks `live` down by the attribute's size,
     * without bound and into the negative. Mirrors JS SDK `keeps the ledger
     * exact for a remote removeStyle on a removed tree node`
     * (`style_tombstone_test.ts:343-373`).
     */
    @Test
    fun `keeps the ledger exact for a remote removeStyle on a removed tree node`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "abcdefgh" } }
                },
            )
        }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 10, mapOf("b" to "L".repeat(16)))
        }.await()
        crossSync(d1, d2)

        // d2 removes the style over a range decided before d1 splits.
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyle(0, 10, listOf("b"))
        }.await()

        // d1 splits the paragraph and removes the right half.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(5, 5, 1) }.await()
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 11, 0) }.await()

        crossSync(d1, d2)

        assertTrue(d1.getDocSize().live.data >= 0, "live went negative")
        assertLedgerExact(d1, "on the replica that removed the node", listOf(actor1, actor2))
        assertLedgerExact(d2, "on the replica that issued the removeStyle", listOf(actor1, actor2))
    }

    /**
     * Toggling a tree attribute on and off has to return the ledger to
     * where it started, on the CLONE as well as on the root --
     * `Document.updateAsync` reads the clone's total against
     * `maxSizeLimit`. Mirrors JS SDK `does not drift the clone ledger when
     * a tree attribute is toggled` (`style_tombstone_test.ts:419-437`).
     */
    @Test
    fun `does not drift the clone ledger when a tree attribute is toggled`() = runTest {
        val d = Document("test-doc")
        d.setMaxSizePerDocument(2000)
        d.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "abcd" } }
                },
            )
        }.await()

        val value = "v".repeat(200)
        repeat(40) { i ->
            d.updateAsync { root, _ -> root.getAs<JsonTree>("t").style(0, 6, mapOf("b" to value)) }
                .await()
            val result = runCatching {
                d.updateAsync { root, _ ->
                    root.getAs<JsonTree>("t").removeStyle(0, 6, listOf("b"))
                }.await()
            }
            assertTrue(
                result.isSuccess,
                "toggle $i tripped maxSizeLimit; the document itself is ${d.getDocSize()}",
            )
        }
    }

    /**
     * The undo mutates the tombstone (the style's reverse removes the
     * attribute the style itself had installed there) but produces no
     * `OpInfo` -- the node has no index to report. Gating on `opInfos`
     * would drop that mutation on the floor: it already changed this
     * replica's root and can never be applied again, so the other replica
     * is left permanently disagreeing. Mirrors JS SDK `sends an undo whose
     * style lands only on a tombstone` (`style_tombstone_test.ts:536-564`),
     * the case the executed-operations gate (whether the operation ran,
     * not whether it produced an `OpInfo`) exists for.
     */
    @Test
    fun `sends an undo whose style lands only on a tombstone`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abcdefghij") }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonText>("t").style(4, 6, mapOf("b" to "1")) }
            .await()
        d2.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(4, 6, "") }.await()
        crossSync(d1, d2)
        assertEquals(nodeAttrs(d1), nodeAttrs(d2), "sanity: converged first")

        d1.history.undoAsync().await()
        val pendingChanges = d1.createChangePack().changes
        assertTrue(
            pendingChanges.isNotEmpty(),
            "the undo mutated the tombstone, so it has to reach the other replica",
        )

        crossSync(d1, d2)
        assertEquals(
            nodeAttrs(d1),
            nodeAttrs(d2),
            "the replicas disagree after an undo that showed nothing",
        )
    }

    /**
     * `executedOperations` must still exclude an operation whose target
     * vanished entirely while the undo was pending -- unlike a style that
     * ran and merely produced no `OpInfo` (the case directly above), this
     * operation's own `execute` never found a target to act on at all
     * (every `Operation`'s "parent not found" branch leaves both `opInfos`
     * and `reverseOps` empty), so it is correctly absent from
     * `executedOperations` and the undo gate still skips propagating it.
     *
     * The target can only vanish out from under a PENDING local undo entry
     * via a REMOTE removal: a local undo-of-remove always restores its own
     * target by identity, so it can never be the one left dangling. Here
     * d1's pending undo entry reverses its own local `"k" = 2` write; d2
     * removes the whole object concurrently, and d1 collects it before
     * undoing: with `executedOperations` empty (target removed while undo
     * pending), nothing is propagated.
     */
    @Test
    fun `undo whose target was purged while pending is gated out, not propagated`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewObject("obj")["k"] = 1 }.await()
        crossSync(d1, d2)

        // Pushes a pending undo entry: reverse SetOperation(obj, "k", 1).
        d1.updateAsync { root, _ -> root.getAs<JsonObject>("obj")["k"] = 2 }.await()

        // d2 removes "obj" entirely, concurrently with d1's own update above.
        d2.updateAsync { root, _ -> root.remove("obj") }.await()
        crossSync(d1, d2)

        d1.garbageCollect(maxVectorOf(listOf(actor1, actor2)))
        assertEquals(0, d1.garbageLength, "sanity: obj was actually purged on d1")

        val before = d1.toJson()
        d1.history.undoAsync().await()

        assertEquals(before, d1.toJson(), "a no-op undo must not mutate the document")
        assertEquals(
            0,
            d1.createChangePack().changes.size,
            "nothing ran, so nothing should be queued for the wire",
        )
    }

    /**
     * Pins the ONLY way a tree style reaches an already-removed node: the
     * split-sibling propagation loop ([CrdtTree.style]'s `insNextID` walk),
     * not the main `canStyle` traversal. `CrdtTree.style`/`removeStyle` both
     * call [IndexTree.tokensBetween] with its default `includeRemoved =
     * false` ([CrdtTree.traverseInPosRange]), so the main loop's callback
     * (`CrdtTree.kt:307-308`, where [CrdtTreeNode.canStyle] and its
     * `!node.isRemoved` change-gate sibling at `:342` are evaluated) is
     * NEVER invoked with a removed node -- `node.isRemoved` is always false
     * there. That makes a mutant which has `canStyle` re-read `removedAt`,
     * and a mutant which drops the `!node.isRemoved` sibling gate at `:342`,
     * EQUIVALENT MUTANTS for tree nodes: no reachable call ever exercises
     * either branch's removed-node case, so no test -- adversarial or
     * otherwise -- can distinguish them from the fix. (Confirmed
     * empirically, not just by reading: reverting either hunk leaves every
     * test in this file, plus this one, green.) This KDoc states the
     * equivalence directly, since `tokensBetween(..., includeRemoved =
     * false)` never visits a removed node outside the split-sibling loop
     * below.
     *
     * The split-sibling loop itself (`CrdtTree.kt:371-402`) is a genuinely
     * different, UNGATED path: it calls `next.setAttributes(...)` and emits
     * `next`'s own [OperationInfo.TreeStyleOpInfo] with no live/removed
     * check on `next` at all, so a style decided before a concurrent split
     * lands on -- and reports a change for -- a removed split sibling
     * unconditionally. That is pinned below (the tombstone carries the
     * attribute) as real, current behaviour; it is a separate, disclosed,
     * not-fixed-here gap from the `:342` gate's removed-node case.
     */
    @Test
    fun `remote tree style on a removed split sibling lands on the tombstone`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "abcdefgh" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d2 styles a range decided before d1 splits.
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 10, mapOf("b" to "L".repeat(16)))
        }.await()

        // d1 splits the paragraph and removes the right half.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(5, 5, 1) }.await()
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 11, 0) }.await()

        val events = mutableListOf<Document.Event>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            d1.events.collect(events::add)
        }
        crossSync(d1, d2)
        job.cancel()

        val styleInfos = events.filterIsInstance<Document.Event.RemoteChange>()
            .flatMap { it.changeInfo.operations }
            .filterIsInstance<OperationInfo.TreeStyleOpInfo>()
        assertEquals(
            2,
            styleInfos.size,
            "the live left half's main-loop report, plus the split-sibling loop's own " +
                "report for the tombstone",
        )

        // The split leaves the doc with two "p" elements: the live left
        // half, then the removed right half.
        val paragraphs = (d1.getRootObject()["t"] as CrdtTree).root.allChildren
            .filter { !it.isText && it.type == "p" }
        assertEquals(2, paragraphs.size, "sanity: the split produced two paragraphs")
        val rightHalf = paragraphs[1]
        assertTrue(rightHalf.isRemoved, "sanity: the split's right half is a tombstone")

        // Exactly one reported TreeStyleOpInfo must be the split-sibling
        // loop's OWN entry for `rightHalf` -- toPath [1] is rightHalf's
        // position directly under the tree root -- carrying the "b"
        // attribute this style op wrote, not just any non-empty report.
        val tombstoneStyleInfo = styleInfos.singleOrNull { it.toPath == listOf(1) }
        assertEquals(
            mapOf("b" to "L".repeat(16)),
            tombstoneStyleInfo?.attributes,
            "the split-sibling propagation loop must report its own change for the tombstone",
        )

        assertTrue(
            rightHalf.getAttrs().has("b"),
            "the split-sibling propagation loop must still reach the tombstone",
        )
    }
}
