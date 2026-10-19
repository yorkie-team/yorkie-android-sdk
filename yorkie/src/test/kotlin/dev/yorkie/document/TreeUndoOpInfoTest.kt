package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.TreeChangeType
import dev.yorkie.document.crdt.TreeElementNode
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.crdt.TreeTextNode
import dev.yorkie.document.crdt.toTreeNode
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import dev.yorkie.util.IndexTreeNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Direct-[CrdtTree] unit cases for [CrdtTree.restore]/[CrdtTree.retombstone]'s
 * per-node `edits` (JS SDK `790422ce`, #1421): the order a revived or
 * re-removed subtree reports its changes in, and the degrade-to-nothing
 * contract for a node whose position cannot be measured; plus the
 * per-operation [OperationInfo.TreeEditOpInfo]/`executedRanges` wiring that
 * consumes those `edits` -- JS `history_tree_opinfo_test`'s 5 edit shapes
 * (local AND remote, undo AND redo), a peer's earlier edit shifting
 * the restored index, and a whole revived subtree reporting its
 * parent before its child; plus the event-gate flip at
 * `Document.executeUndoRedoInternal`/`applyChangesInternal` that lets an
 * executed-but-empty-opInfos undo/remote restore still publish exactly once,
 * `operations = []`, instead of being silently dropped.
 */
class TreeUndoOpInfoTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private suspend fun Document.xml(key: String = "t"): String =
        getRoot().getAs<JsonTree>(key).toXml()

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    /**
     * Builds a standalone "mirror" document whose tree starts as a
     * structural (CRDT-identity-free) copy of [source]'s CURRENT live tree,
     * runs [action] (an undo or redo on [source]) and captures the
     * [OperationInfo.TreeEditOpInfo]s it publishes, then replays ONLY those
     * opInfos onto the mirror via [JsonTree.editByPath] and asserts the
     * result equals [source]'s tree afterward -- the same "follow-mirror"
     * check JS SDK's own `history_tree_opinfo_test` performs (port
     * yorkie-js-sdk `790422ce`, #1421): the published opInfos alone, with no
     * access to CRDT identity, must be enough to reconstruct the change.
     */
    private suspend fun assertFollowMirror(
        source: Document,
        key: String = "t",
        action: suspend () -> List<OperationInfo.TreeEditOpInfo>,
    ) {
        val preRoot = source.crdtTree(key).root.toTreeNode() as TreeElementNode
        val opInfos = action()
        assertTrue(
            opInfos.isNotEmpty(),
            "a restore/retombstone that changed the tree must publish at least one opInfo",
        )

        val mirror = Document("mirror")
        mirror.updateAsync { root, _ -> root.setNewTree(key, preRoot) }.await()
        opInfos.forEach { opInfo ->
            mirror.updateAsync { root, _ ->
                val mirrorTree = root.getAs<JsonTree>(key)
                val contents = opInfo.nodes.orEmpty().toTypedArray()
                mirrorTree.editByPath(opInfo.fromPath, opInfo.toPath, *contents)
            }.await()
        }

        assertEquals(
            source.xml(key),
            mirror.xml(key),
            "replaying only the published opInfos must reconstruct the same tree",
        )
    }

    /**
     * One of the 5 JS `history_tree_opinfo_test` edit shapes (port
     * yorkie-js-sdk `790422ce`, #1421): builds a 2-replica fixture via
     * [setup], runs [edit] on `d1`, then checks that BOTH the local undo/redo
     * AND the remote peer's applied undo/redo publish a non-empty,
     * mirror-replayable [OperationInfo.TreeEditOpInfo] list -- never the old
     * always-`[0, 0]`, node-less placeholder. Red at `35c90e39`
     * for every one of these four checks.
     */
    private suspend fun assertShapeReportsRealOpInfo(
        setup: suspend (Document) -> Unit,
        edit: suspend (Document) -> Unit,
    ) {
        val d1 = Document("d1")
        val d2 = Document("d2")
        setup(d1)
        crossSync(d1, d2)
        edit(d1)
        crossSync(d1, d2)
        assertEquals(d1.xml(), d2.xml(), "both replicas see the edit before any undo/redo")

        fun captureTreeOpInfos(
            document: Document,
            act: suspend () -> Unit,
        ): suspend () -> List<OperationInfo.TreeEditOpInfo> = {
            coroutineScope {
                val events = mutableListOf<Document.Event>()
                val job = launch(UnconfinedTestDispatcher()) {
                    document.events.collect(events::add)
                }
                act()
                job.cancel()
                events.flatMap { event ->
                    when (event) {
                        is Document.Event.LocalChange -> event.changeInfo.operations
                        is Document.Event.RemoteChange -> event.changeInfo.operations
                        else -> emptyList()
                    }
                }.filterIsInstance<OperationInfo.TreeEditOpInfo>()
            }
        }

        // local undo
        assertFollowMirror(d1, action = captureTreeOpInfos(d1) { d1.history.undoAsync().await() })
        // remote undo reaches d2
        assertFollowMirror(d2, action = captureTreeOpInfos(d2) { crossSync(d1, d2) })
        assertEquals(d1.xml(), d2.xml(), "undo converges on both replicas")

        // local redo
        assertFollowMirror(d1, action = captureTreeOpInfos(d1) { d1.history.redoAsync().await() })
        // remote redo reaches d2
        assertFollowMirror(d2, action = captureTreeOpInfos(d2) { crossSync(d1, d2) })
        assertEquals(d1.xml(), d2.xml(), "redo converges on both replicas")
    }

    // Shape 1/5: insert text ("abcd" -> "abXcd").
    @Test
    fun `insert text shape reports real opInfo on undo and redo, local and remote`() = runTest {
        assertShapeReportsRealOpInfo(
            setup = { document ->
                document.updateAsync { root, _ ->
                    root.setNewTree("t", element("root") { text { "abcd" } })
                }.await()
            },
            edit = { document ->
                document.updateAsync { root, _ ->
                    root.getAs<JsonTree>("t").edit(2, 2, text { "X" })
                }.await()
                assertEquals("<root>abXcd</root>", document.xml())
            },
        )
    }

    // Shape 2/5: delete text ("abcd" -> "ad").
    @Test
    fun `delete text shape reports real opInfo on undo and redo, local and remote`() = runTest {
        assertShapeReportsRealOpInfo(
            setup = { document ->
                document.updateAsync { root, _ ->
                    root.setNewTree("t", element("root") { text { "abcd" } })
                }.await()
            },
            edit = { document ->
                document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 3) }.await()
                assertEquals("<root>ad</root>", document.xml())
            },
        )
    }

    // Shape 3/5: insert a whole element ("<p>a</p>" -> "<p>a</p><p>b</p>").
    @Test
    fun `insert element shape reports real opInfo on undo and redo, local and remote`() = runTest {
        assertShapeReportsRealOpInfo(
            setup = { document ->
                document.updateAsync { root, _ ->
                    root.setNewTree("t", element("root") { element("p") { text { "a" } } })
                }.await()
            },
            edit = { document ->
                document.updateAsync { root, _ ->
                    root.getAs<JsonTree>("t")
                        .editByPath(listOf(1), listOf(1), element("p") { text { "b" } })
                }.await()
                assertEquals("<root><p>a</p><p>b</p></root>", document.xml())
            },
        )
    }

    // Shape 4/5: delete a whole element ("<p>a</p><p>b</p>" -> "<p>a</p>").
    @Test
    fun `delete element shape reports real opInfo on undo and redo, local and remote`() = runTest {
        assertShapeReportsRealOpInfo(
            setup = { document ->
                document.updateAsync { root, _ ->
                    root.setNewTree(
                        "t",
                        element("root") {
                            element("p") { text { "a" } }
                            element("p") { text { "b" } }
                        },
                    )
                }.await()
            },
            edit = { document ->
                document.updateAsync { root, _ ->
                    root.getAs<JsonTree>("t").editByPath(listOf(1), listOf(2))
                }.await()
                assertEquals("<root><p>a</p></root>", document.xml())
            },
        )
    }

    // Shape 5/5: delete two whole elements in one edit
    // ("<p>a</p><p>b</p><p>c</p>" -> "<p>a</p>").
    @Test
    fun `delete two elements shape reports real opInfo on undo and redo, local and remote`() =
        runTest {
            assertShapeReportsRealOpInfo(
                setup = { document ->
                    document.updateAsync { root, _ ->
                        root.setNewTree(
                            "t",
                            element("root") {
                                element("p") { text { "a" } }
                                element("p") { text { "b" } }
                                element("p") { text { "c" } }
                            },
                        )
                    }.await()
                },
                edit = { document ->
                    document.updateAsync { root, _ ->
                        root.getAs<JsonTree>("t").editByPath(listOf(1), listOf(3))
                    }.await()
                    assertEquals("<root><p>a</p></root>", document.xml())
                },
            )
        }

    // A peer's earlier insert shifts the restored index -- the
    // revived node's IDENTITY (left/right sibling) is unaffected, but the
    // DOCUMENT-INDEX position reported must reflect the peer's edit, not the
    // stale pre-edit offset (the whole point of measuring at restore time
    // instead of carrying a stored, never-moving index).
    @Test
    fun `a peer's earlier insert shifts the restored opInfo's reported position`() = runTest {
        val d1 = Document("d1")
        val d2 = Document("d2")
        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d1 deletes the second paragraph -- an identity-preserving delete.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").editByPath(listOf(1), listOf(2)) }
            .await()
        crossSync(d1, d2)
        assertEquals("<root><p>ab</p></root>", d1.xml())
        // "<p>ab</p>" pads to 2 (open/close) + 2 chars = 4: the deleted
        // "<p>cd</p>" originally started right after it, at index 4.
        val originalFrom = 4

        // d2 inserts a brand-new paragraph BEFORE where the deleted one used
        // to sit -- this does not touch the deleted node's identity at all.
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>(
                "t",
            ).editByPath(listOf(0), listOf(0), element("p") { text { "XYZ" } })
        }.await()
        crossSync(d1, d2)
        assertEquals("<root><p>XYZ</p><p>ab</p></root>", d1.xml())
        // "<p>XYZ</p>" pads to 2 + 3 chars = 5.
        val insertedWidth = 5

        lateinit var undoOps: List<OperationInfo.TreeEditOpInfo>
        assertFollowMirror(
            d1,
            action = {
                coroutineScope {
                    val events = mutableListOf<Document.Event>()
                    val job = launch(UnconfinedTestDispatcher()) { d1.events.collect(events::add) }
                    d1.history.undoAsync().await()
                    job.cancel()
                    undoOps = (events.last() as Document.Event.LocalChange).changeInfo.operations
                        .filterIsInstance<OperationInfo.TreeEditOpInfo>()
                    undoOps
                }
            },
        )

        assertEquals("<root><p>XYZ</p><p>ab</p><p>cd</p></root>", d1.xml())
        assertEquals(
            originalFrom + insertedWidth,
            undoOps.first().from,
            "the restored position must shift by the peer's insert, not report the stale index",
        )

        crossSync(d1, d2)
        assertEquals(d1.xml(), d2.xml())
    }

    // A whole revived subtree reports its parent BEFORE its child,
    // the parent rendered with `children: []` (the child has not come back
    // yet at that point in the list) -- mirrors the direct-CrdtTree
    // case, now observed through the published Document event.
    @Test
    fun `a restored subtree reports its parent before its child`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>(
                "t",
            ).editByPath(listOf(1), listOf(2))
        }
            .await()
        assertEquals("<root><p>ab</p></root>", document.xml())

        lateinit var opInfos: List<OperationInfo.TreeEditOpInfo>
        assertFollowMirror(
            document,
            action = {
                coroutineScope {
                    val events = mutableListOf<Document.Event>()
                    val job = launch(UnconfinedTestDispatcher()) {
                        document.events.collect(events::add)
                    }
                    document.history.undoAsync().await()
                    job.cancel()
                    opInfos = (events.last() as Document.Event.LocalChange).changeInfo.operations
                        .filterIsInstance<OperationInfo.TreeEditOpInfo>()
                    opInfos
                }
            },
        )

        assertEquals("<root><p>ab</p><p>cd</p></root>", document.xml())
        assertEquals(
            2,
            opInfos.size,
            "the parent <p> and its text child each report their own edit",
        )
        val (parentOpInfo, childOpInfo) = opInfos

        assertEquals(listOf(1), parentOpInfo.fromPath)
        assertEquals(parentOpInfo.fromPath, parentOpInfo.toPath, "an insertion collapses from==to")
        val parentNode = parentOpInfo.nodes.orEmpty().single() as TreeElementNode
        assertEquals("p", parentNode.type)
        assertEquals(
            emptyList(),
            parentNode.childNodes,
            "the parent is rendered before its own child comes back, exactly like the direct case",
        )

        assertEquals(listOf(1, 0), childOpInfo.fromPath)
        val childNode = childOpInfo.nodes.orEmpty().single() as TreeTextNode
        assertEquals("cd", childNode.value)
    }

    // An undo whose node lands under a PEER's removed ancestor --
    // never one this same actor's own undo stack already restored, which a
    // single local actor can never observe (its own entries pop in strict
    // LIFO order, so an ancestor its own later entry removed is always
    // already back by the time an earlier entry for one of its descendants
    // is undone). d1 deletes just the leaf text; d2 concurrently removes the
    // whole paragraph that text lives in; only then does d1 undo its own
    // entry, landing under an ancestor IT never removed. The restore still
    // executes (structurally revives the text node) but cannot measure a
    // position under the now-tombstoned "<p>", so it must publish exactly
    // once with an EMPTY operations list -- never suppressed, and the change
    // still reaches the peer. Port yorkie-js-sdk `790422ce` (#1421); the
    // event-gate flip that makes this observable (`executedOperations`, not
    // opInfos) is covered by the Document.kt change.
    @Test
    fun `a tree undo under a removed ancestor publishes once with empty ops, local and remote`() =
        runTest {
            val d1 = Document("d1")
            val d2 = Document("d2")
            d1.setActor(actor1)
            d2.setActor(actor2)
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("root") {
                        element("p") { text { "ab" } }
                        element("p") { text { "cd" } }
                    },
                )
            }.await()
            crossSync(d1, d2)

            // d1 deletes just the first paragraph's text -- its own undo entry.
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 3) }.await()
            assertEquals("<root><p></p><p>cd</p></root>", d1.xml())

            // d2 concurrently removes the WHOLE first paragraph -- the
            // ancestor of the text node d1 is about to undo -- before either
            // side has seen the other's edit.
            d2.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1))
            }.await()
            assertEquals("<root><p>cd</p></root>", d2.xml())

            // Deliver both concurrent edits to each other: d1 now sees its
            // own pending restore's ancestor already tombstoned by d2.
            crossSync(d1, d2)
            assertEquals("<root><p>cd</p></root>", d1.xml())
            assertEquals(d1.xml(), d2.xml(), "both replicas converge on the concurrent deletes")

            lateinit var d1LocalChanges: List<Document.Event.LocalChange>
            coroutineScope {
                val events = mutableListOf<Document.Event>()
                val job = launch(UnconfinedTestDispatcher()) { d1.events.collect(events::add) }
                d1.history.undoAsync().await()
                job.cancel()
                d1LocalChanges = events.filterIsInstance<Document.Event.LocalChange>()
            }

            assertEquals(
                1,
                d1LocalChanges.size,
                "the undo publishes exactly once, never suppressed",
            )
            assertTrue(
                d1LocalChanges.single().changeInfo.operations.isEmpty(),
                "the revived node has no measurable position under its removed ancestor",
            )
            assertTrue(d1.hasLocalChanges(), "the undo still queues a change to send")
            assertEquals(
                "<root><p>cd</p></root>",
                d1.xml(),
                "the revive is structural only -- the node stays under its tombstoned parent",
            )

            lateinit var d2RemoteChanges: List<Document.Event.RemoteChange>
            coroutineScope {
                val events = mutableListOf<Document.Event>()
                val job = launch(UnconfinedTestDispatcher()) { d2.events.collect(events::add) }
                crossSync(d1, d2)
                job.cancel()
                d2RemoteChanges = events.filterIsInstance<Document.Event.RemoteChange>()
            }

            assertEquals(
                1,
                d2RemoteChanges.size,
                "the peer publishes the delivered undo exactly once too",
            )
            assertTrue(
                d2RemoteChanges.single().changeInfo.operations.isEmpty(),
                "the peer's own copy cannot measure a position either",
            )
            assertEquals(d1.xml(), d2.xml(), "both replicas converge after the undo is delivered")
        }

    /** Hands out strictly increasing tickets for a single actor. */
    private fun ticketer(): () -> TimeTicket {
        var lamport = 0L
        return {
            lamport += 1
            TimeTicket(lamport, 0u, "000000000000000000000001")
        }
    }

    @Test
    fun `restore reports a revived subtree's parent before its still-empty children`() {
        val issue = ticketer()
        fun element(type: String) = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), type)
        fun text(value: String) = CrdtTreeText(CrdtTreeNodeID(issue(), 0), value)

        val tree = CrdtTree(element("r"), issue())
        val p = element("p")
        tree.edit(tree.findPos(0) to tree.findPos(0), listOf(p), 0, issue(), issue)
        val ab = text("ab")
        tree.edit(tree.findPos(1) to tree.findPos(1), listOf(ab), 0, issue(), issue)
        assertEquals("<r><p>ab</p></r>", tree.toXml())

        val deleted = tree.edit(tree.findPos(0) to tree.findPos(4), null, 0, issue())
        assertEquals(
            listOf(p.id, ab.id),
            deleted.removedSpans.map { it.id },
            "the delete cascade captures spans parent-before-child",
        )
        assertEquals("<r></r>", tree.toXml())

        val editedAt = issue()
        val result = tree.restore(deleted.removedSpans, editedAt)

        assertEquals(2, result.edits.size)
        val (parentEdit, childEdit) = result.edits

        // The parent is measured -- and rendered -- before its own child
        // comes back, exactly like JS's `toTreeNode` over live children only.
        assertEquals(
            listOf(TreeElementNode("p", childNodes = emptyList())),
            parentEdit.change.value,
        )
        assertEquals(2, parentEdit.insertedSize, "an empty <p></p> pads to 2")
        assertEquals(
            parentEdit.change.from,
            parentEdit.change.to,
            "an insertion collapses from==to",
        )
        assertEquals(editedAt.actorID, parentEdit.change.actorID)

        assertEquals(listOf(TreeTextNode("ab")), childEdit.change.value)
        assertEquals(2, childEdit.insertedSize)
        assertEquals("<r><p>ab</p></r>", tree.toXml(), "both nodes are revived")
    }

    @Test
    fun `retombstone reports a deletion range measured before the node is removed`() {
        val issue = ticketer()
        val root = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), "root")
        val tree = CrdtTree(root, issue())
        val insertedAt = issue()
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeText(CrdtTreeNodeID(insertedAt, 0), "0123456789")),
            0,
            issue(),
        )
        val span = TreeRestoreSpan(
            id = CrdtTreeNodeID(insertedAt, 3),
            nodeType = "text",
            isText = true,
            length = 4,
            value = "3456",
        )

        val executedAt = issue()
        val result = tree.retombstone(listOf(span), executedAt)

        val edit = result.edits.single()
        assertEquals(TreeChangeType.Content, edit.change.type)
        assertEquals(0, edit.insertedSize, "a deletion inserts nothing")
        assertEquals(null, edit.change.value, "a deletion carries no content")
        assertEquals(executedAt.actorID, edit.change.actorID)
        assertTrue(edit.change.from < edit.change.to, "a deletion is a real range, not collapsed")
        assertEquals("<root>012789</root>", tree.toXml())
    }

    @Test
    fun `restore degrades to an empty edit when the revived node cannot be measured`() {
        val issue = ticketer()
        fun element(type: String) = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), type)
        fun text(value: String) = CrdtTreeText(CrdtTreeNodeID(issue(), 0), value)

        val tree = CrdtTree(element("r"), issue())
        val hello = text("hello")
        tree.edit(tree.findPos(0) to tree.findPos(0), listOf(hello), 0, issue(), issue)
        assertEquals("<r>hello</r>", tree.toXml())

        val deleted = tree.edit(tree.findPos(0) to tree.findPos(5), null, 0, issue())
        val span = deleted.removedSpans.single()
        assertTrue(hello.isRemoved)

        // Force the tombstoned text node's parent pointer at an unrelated,
        // never-attached element: a hostile structure visibleRangeOf must
        // survive without throwing. toIndex's floor search for `hello`
        // inside that decoy parent fails ("invalid pos: ..."), reproducing
        // the throw JS's comment guards against. Port yorkie-js-sdk
        // `790422ce` (#1421).
        val decoy = element("decoy")
        IndexTreeNode::class.java.getDeclaredField("parent").apply {
            isAccessible = true
        }.set(hello, decoy)

        val result = tree.restore(listOf(span), issue())

        assertEquals(listOf(hello), result.untombstoned, "the node is still revived")
        assertTrue(result.edits.isEmpty(), "an unmeasurable node reports no edit, never a throw")
        assertFalse(hello.isRemoved)
    }

    // Re-check of an api-compat review note: the `applyChangesInternal` event
    // gate flipped to `executedOperations` is GLOBAL, not
    // Tree-restore-specific -- any executed op whose OpInfo list happens to
    // be empty now publishes on the remote path. A style targeting a node
    // the PEER concurrently removed is the other shape that hits this (JS:
    // "a style on a node a peer removed concurrently has no index to
    // report"): the style still applies to the tombstone (`canStyle` no
    // longer refuses a removed node, #1368, `StyleTombstoneConvergenceTest`),
    // but there is no live range left to report, so
    // `TreeStyleOperation.execute` yields `opInfos = emptyList()` while
    // `executed` stays true. Consistent with JS parity and the existing
    // divergence notes -- not a contradiction.
    @Test
    fun `a remote style on a concurrently removed node publishes once with empty ops`() = runTest {
        val d1 = Document("d1")
        val d2 = Document("d2")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d1.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { element("p") { text { "ab" } } })
        }.await()
        crossSync(d1, d2)

        // Concurrently: d1 styles the whole "<p>"; d2 removes that same
        // "<p>" -- before either side has seen the other's change.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 4, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1))
        }.await()

        lateinit var d2RemoteChanges: List<Document.Event.RemoteChange>
        coroutineScope {
            val events = mutableListOf<Document.Event>()
            val job = launch(UnconfinedTestDispatcher()) { d2.events.collect(events::add) }
            crossSync(d1, d2)
            job.cancel()
            d2RemoteChanges = events.filterIsInstance<Document.Event.RemoteChange>()
        }

        assertEquals(
            1,
            d2RemoteChanges.size,
            "the remote style publishes exactly once, never suppressed",
        )
        assertTrue(
            d2RemoteChanges.single().changeInfo.operations.isEmpty(),
            "a style on a node the peer removed concurrently has no index to report",
        )
        assertEquals(d1.xml(), d2.xml(), "both replicas converge")
    }
}
