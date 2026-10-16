package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.TreeElementNode
import dev.yorkie.document.crdt.toTreeNode
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_split_opinfo_test.ts` (JS SDK `a74b2513`, #1455): a remote
 * split reports where it landed, not where it was asked for. When the
 * receiver concurrently inserted at the split boundary, the split keeps
 * that insert on its left (section 7.3 boundary insert migration), so the
 * [OperationInfo.TreeEditOpInfo] has to say so -- an editor binding
 * replaying it by path otherwise moves the insert to the new node and its
 * model no longer matches the tree.
 *
 * 11 cases x 3 ticket orders = 33 sub-cases, each replaying the OTHER
 * replica's delivered [OperationInfo.TreeEditOpInfo]s onto a structural
 * mirror of its own pre-exchange tree (the same "follow-mirror" contract
 * [TreeUndoOpInfoTest] already checks) and comparing the result to the real
 * post-exchange tree. RED at `ea72ed86`: 12 of 33 sub-cases failed (18
 * passed), confirmed against the upstream JS suite at the same commit's
 * parent (12 failed / 18 passed / 3 `it.fails`).
 */
class TreeSplitOpInfoTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun JsonObject.tree(key: String = "t") = getAs<JsonTree>(key)
    private suspend fun Document.tree(key: String = "t") = getRoot().getAs<JsonTree>(key)
    private suspend fun Document.xml(key: String = "t") = tree(key).toXml()
    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    /** Two replicas synced from `<doc><p><t>abc</t></p></doc>`. */
    private suspend fun replicas(actorA: String, actorB: String): Pair<Document, Document> {
        val a = Document("tree-split-opinfo").apply { setActor(actorA) }
        val b = Document("tree-split-opinfo").apply { setActor(actorB) }
        a.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("t") { text { "abc" } }
                    }
                },
            )
        }.await()
        crossSync(a, b, overWire = true)
        return a to b
    }

    /** Replays [ops] onto a fresh mirror seeded with [preRoot], returns the resulting XML. */
    private suspend fun replay(
        preRoot: TreeElementNode,
        ops: List<OperationInfo.TreeEditOpInfo>,
    ): String {
        val mirror = Document("mirror")
        mirror.updateAsync { root, _ -> root.setNewTree("t", preRoot) }.await()
        ops.forEach { opInfo ->
            mirror.updateAsync { root, _ ->
                val contents = opInfo.nodes.orEmpty().toTypedArray()
                root.tree().editByPath(
                    opInfo.fromPath,
                    opInfo.toPath,
                    *contents,
                    splitLevel = opInfo.splitLevel,
                )
            }.await()
        }
        return mirror.tree().toXml()
    }

    private val insertXY: (JsonTree) -> Unit = { t ->
        t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), text { "XY" })
    }

    private data class Case(
        val name: String,
        val insert: (JsonTree) -> Unit,
        val split: (JsonTree) -> Unit,
        /** A known, unfixed gap (ancestor-level reorder): still asserted, not fixed. */
        val fails: Boolean = false,
    )

    private val cases = listOf(
        Case(
            name = "text split of the inline element",
            insert = insertXY,
            split = { t -> t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1) },
        ),
        Case(
            name = "Enter: text split, then paragraph split",
            insert = insertXY,
            split = { t ->
                t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
                t.splitByPath(listOf(0, 1))
            },
        ),
        Case(
            name = "paragraph split with a concurrent element insert at the boundary",
            insert = { t ->
                t.editByPath(listOf(0, 1), listOf(0, 1), element("t") { text { "XY" } })
            },
            split = { t -> t.splitByPath(listOf(0, 1)) },
        ),
        Case(
            name = "text and paragraph split in one edit",
            insert = insertXY,
            split = { t -> t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 2) },
        ),
        Case(
            // Known gap, reported but not fixed: the split is corrected at
            // its first level only. Here the paragraph level is the one
            // section 7.3 makes keep the concurrent `<t>XY</t>` on its
            // left, across the `<t></t>` the first level minted, so the
            // result is the two children reordered as well as split. No
            // split position, at any level, describes a reorder.
            name = "concurrent element insert at the paragraph boundary, split level 2",
            insert = { t ->
                t.editByPath(listOf(0, 1), listOf(0, 1), element("t") { text { "XY" } })
            },
            split = { t -> t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 2) },
            fails = true,
        ),
        Case(
            name = "both split the same text boundary",
            insert = { t -> t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1) },
            split = { t -> t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1) },
        ),
        Case(
            name = "both split the same paragraph boundary",
            insert = { t -> t.splitByPath(listOf(0, 1)) },
            split = { t -> t.splitByPath(listOf(0, 1)) },
        ),
        Case(
            name = "split away from the insert (control)",
            insert = insertXY,
            split = { t -> t.editByPath(listOf(0, 0, 2), listOf(0, 0, 2), splitLevel = 1) },
        ),
        Case(
            // The split boundary migrates past the concurrent XY, so it no
            // longer coincides with the position the content was inserted
            // at and the insert is emitted as its own change after the
            // split one.
            name = "text split that also inserts content, at the insert boundary",
            insert = insertXY,
            split = { t ->
                t.editByPath(
                    listOf(0, 0, 3),
                    listOf(0, 0, 3),
                    text { "Z" },
                    splitLevel = 1,
                )
            },
        ),
        Case(
            // Nothing migrates the boundary here, so the split and the
            // insert still share a position and the insert merges onto the
            // split change.
            name = "text split that also inserts content, away from the insert",
            insert = insertXY,
            split = { t ->
                t.editByPath(
                    listOf(0, 0, 2),
                    listOf(0, 0, 2),
                    text { "Z" },
                    splitLevel = 1,
                )
            },
        ),
        Case(
            name = "text and paragraph split in one edit, with inserted content",
            insert = insertXY,
            split = { t ->
                t.editByPath(
                    listOf(0, 0, 3),
                    listOf(0, 0, 3),
                    text { "Z" },
                    splitLevel = 2,
                )
            },
        ),
    )

    // [name, A's actor, B's actor, local head-start changes A makes first].
    // B syncs its clock to A's on the seed exchange, so without a head
    // start B's split carries the newer ticket whatever the actor order;
    // the head start gives A's insert the newer ticket instead.
    private data class Order(
        val name: String,
        val actorA: String,
        val actorB: String,
        val headStart: Int,
    )

    private val orders = listOf(
        Order("split ticket newer, inserter actor lower", actor1, actor2, 0),
        Order("split ticket newer, inserter actor higher", actor2, actor1, 0),
        Order("insert ticket newer", actor1, actor2, 2),
    )

    @Test
    fun `replaying a remote split OpInfo reconstructs the receiver's tree`() = runTest {
        for (case in cases) {
            for (order in orders) {
                val label = "${case.name} (${order.name})"
                val (a, b) = replicas(order.actorA, order.actorB)
                repeat(order.headStart) { i ->
                    a.updateAsync { root, _ -> root["n"] = i }.await()
                }

                a.updateAsync { root, _ -> case.insert(root.tree()) }.await()
                b.updateAsync { root, _ -> case.split(root.tree()) }.await()

                // Snapshot each replica's own tree AFTER its own local edit,
                // but before the exchange -- that is the pre-state the
                // OTHER replica's remote OpInfo is reported against.
                val preRootA = a.crdtTree().root.toTreeNode() as TreeElementNode
                val preRootB = b.crdtTree().root.toTreeNode() as TreeElementNode

                lateinit var opsA: List<OperationInfo.TreeEditOpInfo>
                lateinit var opsB: List<OperationInfo.TreeEditOpInfo>
                // Capture both sides' incoming remote ops from the single exchange.
                coroutineScope {
                    val eventsA = mutableListOf<Document.Event>()
                    val eventsB = mutableListOf<Document.Event>()
                    val jobA = launch(UnconfinedTestDispatcher()) { a.events.collect(eventsA::add) }
                    val jobB = launch(UnconfinedTestDispatcher()) { b.events.collect(eventsB::add) }
                    crossSync(a, b, overWire = true)
                    jobA.cancel()
                    jobB.cancel()
                    opsA = eventsA.filterIsInstance<Document.Event.RemoteChange>()
                        .flatMap { it.changeInfo.operations }
                        .filterIsInstance<OperationInfo.TreeEditOpInfo>()
                    opsB = eventsB.filterIsInstance<Document.Event.RemoteChange>()
                        .flatMap { it.changeInfo.operations }
                        .filterIsInstance<OperationInfo.TreeEditOpInfo>()
                }

                assertEquals(a.xml(), b.xml(), "replicas diverged ($label)")

                // B received the split's OpInfo; replaying it must match B's own tree.
                assertEquals(b.xml(), replay(preRootB, opsB), "B replay of $label")

                // A received the insert's OpInfo (and, in the known-gap
                // case, a misreported split): replaying it either matches
                // A's own tree, or -- for the recorded ancestor-reorder gap
                // -- still does not, which is the still-failing shape this
                // case pins rather than fixes.
                if (case.fails) {
                    assertNotEquals(
                        a.xml(),
                        replay(preRootA, opsA),
                        "known gap did not reproduce for $label -- the ancestor-reorder " +
                            "case may be fixed; update Case.fails",
                    )
                } else {
                    assertEquals(a.xml(), replay(preRootA, opsA), "A replay of $label")
                }
            }
        }
    }

    /**
     * Ports the editor shape the bug was found in, as a two-document
     * `crossSync(overWire = true)` test (the spec's resolved instrumented
     * scope -- see exec plan section 0): alice keeps typing at the end of
     * an inline element while bob presses Enter there. Bob's Enter is a
     * split of the inline element's text followed by a split of the
     * paragraph. Alice's concurrent text stays on the left of the split, so
     * the split alice is told about has to be after it.
     */
    @Test
    fun `reports the split after text the receiver typed at the boundary`() = runTest {
        val alice = Document("tree-split-opinfo-integration").apply { setActor(actor1) }
        val bob = Document("tree-split-opinfo-integration").apply { setActor(actor2) }
        alice.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("t") { text { "가나다0123" } }
                    }
                },
            )
        }.await()
        crossSync(alice, bob, overWire = true)

        alice.updateAsync { root, _ ->
            root.tree().editByPath(listOf(0, 0, 7), listOf(0, 0, 7), text { "4" })
        }.await()
        alice.updateAsync { root, _ ->
            root.tree().editByPath(listOf(0, 0, 8), listOf(0, 0, 8), text { "5" })
        }.await()
        bob.updateAsync { root, _ ->
            val t = root.tree()
            t.editByPath(listOf(0, 0, 7), listOf(0, 0, 7), splitLevel = 1)
            t.splitByPath(listOf(0, 1))
        }.await()

        lateinit var ops: List<OperationInfo.TreeEditOpInfo>
        coroutineScope {
            val events = mutableListOf<Document.Event>()
            val job = launch(UnconfinedTestDispatcher()) { alice.events.collect(events::add) }
            crossSync(alice, bob, overWire = true)
            job.cancel()
            ops = events.filterIsInstance<Document.Event.RemoteChange>()
                .flatMap { it.changeInfo.operations }
                .filterIsInstance<OperationInfo.TreeEditOpInfo>()
        }

        assertEquals(alice.xml(), bob.xml())
        assertEquals("<doc><p><t>가나다012345</t></p><p><t></t></p></doc>", alice.xml())
        assertEquals(
            listOf(listOf(0, 0, 9) to listOf(0, 0, 9), listOf(0, 1) to listOf(0, 1)),
            ops.map { it.fromPath to it.toPath },
        )
        assertTrue(ops.isNotEmpty())
    }
}
