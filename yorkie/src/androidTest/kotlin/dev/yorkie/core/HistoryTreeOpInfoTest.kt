package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.TreeElementNode
import dev.yorkie.document.crdt.toTreeNode
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for spec 036 Commit 1 (JS `790422ce`, #1421, unit
 * 1D): the JVM `dev.yorkie.document.TreeUndoOpInfoTest` follow-mirror
 * (probe P1) and removed-ancestor (probe P2) cases, exercised over the REAL
 * sync loop (a real Yorkie server, compose pin `yorkieteam/yorkie:0.7.24`)
 * instead of the bare in-memory `crossSync` helper that test uses -- the
 * wire-encoded, server-relayed path those JVM cases cannot reach on their
 * own. Oracle for every case here: both documents' XML equal, the server's
 * OWN replayed root equal (admin `GetDocuments`, pattern:
 * [TreeSplitRemoveStyleServerRootTest.serverRoot]), `docSize` equal to a
 * from-scratch [CrdtRoot] rebuild on each client.
 */
@RunWith(AndroidJUnit4::class)
class HistoryTreeOpInfoTest {

    private val http = OkHttpClient()

    /**
     * The server's own replayed root for [documentKey], via admin
     * `GetDocuments` -- pattern: [TreeSplitRemoveStyleServerRootTest.serverRoot].
     */
    private fun serverRoot(documentKey: String): String {
        val yorkieServerUrl = getYorkieServerUrl()
        val adminToken = http.postApi<Map<String, Any>>(
            url = "$yorkieServerUrl/yorkie.v1.AdminService/LogIn",
            requestMap = mapOf("username" to TEST_API_ID, "password" to TEST_API_PW),
            gson = gson,
        )["token"] as String
        val listProjectsResponse = http.postApi<Map<String, Any>>(
            url = "$yorkieServerUrl/yorkie.v1.AdminService/ListProjects",
            headers = mapOf("Authorization" to "Bearer $adminToken"),
            requestMap = emptyMap(),
            gson = gson,
        )

        @Suppress("UNCHECKED_CAST")
        val secretKey = (listProjectsResponse["projects"] as List<Map<String, Any>>)
            .first { it["name"] == "default" }["secretKey"] as String

        val documentsResponse = http.postApi<Map<String, Any>>(
            url = "$yorkieServerUrl/yorkie.v1.AdminService/GetDocuments",
            headers = mapOf("Authorization" to "API-Key $secretKey"),
            requestMap = mapOf("documentKeys" to listOf(documentKey), "includeRoot" to true),
            gson = gson,
        )

        @Suppress("UNCHECKED_CAST")
        val documents = documentsResponse["documents"] as List<Map<String, Any>>
        return documents.first()["root"] as String
    }

    private suspend fun Document.xml(key: String): String = getRoot().getAs<JsonTree>(key).toXml()

    private fun Document.crdtTree(key: String): CrdtTree = getRootObject()[key] as CrdtTree

    /** [document]'s `docSize` must equal a from-scratch [CrdtRoot] rebuild of its own root. */
    private fun assertRebuild(document: Document) {
        val rebuild = CrdtRoot(document.getRootObject().deepCopy())
        assertEquals(
            rebuild.docSize,
            document.getDocSize(),
            "docSize must equal a from-scratch rebuild",
        )
    }

    /**
     * Captures every [OperationInfo.TreeEditOpInfo] published on [document]
     * while [act] runs -- pattern:
     * [dev.yorkie.document.json.JsonTreeTest.collectTreeEditOpInfos], adapted
     * to a one-shot capture around a single action instead of a long-lived
     * collector.
     */
    private suspend fun captureTreeOpInfos(
        document: Document,
        act: suspend () -> Unit,
    ): List<OperationInfo.TreeEditOpInfo> = coroutineScope {
        val events = mutableListOf<Document.Event>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
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

    /**
     * Port of the JVM `TreeUndoOpInfoTest.assertFollowMirror` (JS SDK
     * `790422ce`, #1421), run here over the real sync loop: [preRoot] is
     * [source]'s tree structure captured BEFORE [act] (an undo/redo, or a
     * sync that delivers a peer's undo/redo) runs. A standalone mirror
     * `Document` -- no client, never attached -- starts from that structural
     * snapshot and replays ONLY the [OperationInfo.TreeEditOpInfo]s [act]
     * publishes; the published opInfos alone, with no access to CRDT
     * identity, must be enough to reconstruct [source]'s resulting tree.
     */
    private suspend fun assertFollowMirror(
        source: Document,
        key: String,
        preRoot: TreeElementNode,
        act: suspend () -> Unit,
    ) {
        val opInfos = captureTreeOpInfos(source, act)
        assertTrue(
            opInfos.isNotEmpty(),
            "a measurable undo/redo must publish at least one opInfo",
        )

        val mirror = Document("mirror-$key")
        mirror.updateAsync { root, _ -> root.setNewTree(key, preRoot) }.await().getOrThrow()
        opInfos.forEach { opInfo ->
            mirror.updateAsync { root, _ ->
                val mirrorTree = root.getAs<JsonTree>(key)
                val contents = opInfo.nodes.orEmpty().toTypedArray()
                mirrorTree.editByPath(opInfo.fromPath, opInfo.toPath, *contents)
            }.await().getOrThrow()
        }

        assertEquals(
            source.xml(key),
            mirror.xml(key),
            "replaying only the published opInfos must reconstruct the same tree",
        )
        mirror.close()
    }

    /**
     * One of the 5 JS `history_tree_opinfo_test` edit shapes (port
     * yorkie-js-sdk `790422ce`, #1421; mirrors the JVM
     * `TreeUndoOpInfoTest.assertShapeReportsRealOpInfo`'s shape tests):
     * builds [key]'s tree on [d1], syncs, runs [performEdit], syncs, then
     * checks LOCAL undo, REMOTE undo (via [sync]), LOCAL redo, and REMOTE
     * redo each publish a non-empty, mirror-replayable
     * [OperationInfo.TreeEditOpInfo] list over the real sync loop.
     */
    private suspend fun runShape(
        d1: Document,
        d2: Document,
        sync: suspend () -> Unit,
        key: String,
        setupTree: JsonTree.ElementNode,
        performEdit: suspend (JsonTree) -> Unit,
        beforeEditXml: String,
        afterEditXml: String,
    ) {
        d1.updateAsync { root, _ -> root.setNewTree(key, setupTree) }.await().getOrThrow()
        sync()
        assertEquals(beforeEditXml, d1.xml(key))
        assertEquals(beforeEditXml, d2.xml(key))

        d1.updateAsync { root, _ -> performEdit(root.getAs<JsonTree>(key)) }.await().getOrThrow()
        sync()
        assertEquals(afterEditXml, d1.xml(key))
        assertEquals(afterEditXml, d2.xml(key))

        // local undo
        val preUndoRoot = d1.crdtTree(key).root.toTreeNode() as TreeElementNode
        assertFollowMirror(d1, key, preUndoRoot) { d1.history.undoAsync().await().getOrThrow() }
        assertEquals(beforeEditXml, d1.xml(key))

        // remote undo reaches d2
        val preRemoteUndoRoot = d2.crdtTree(key).root.toTreeNode() as TreeElementNode
        assertFollowMirror(d2, key, preRemoteUndoRoot, act = sync)
        assertEquals(d1.xml(key), d2.xml(key), "undo converges on both replicas")

        // local redo
        val preRedoRoot = d1.crdtTree(key).root.toTreeNode() as TreeElementNode
        assertFollowMirror(d1, key, preRedoRoot) { d1.history.redoAsync().await().getOrThrow() }
        assertEquals(afterEditXml, d1.xml(key))

        // remote redo reaches d2
        val preRemoteRedoRoot = d2.crdtTree(key).root.toTreeNode() as TreeElementNode
        assertFollowMirror(d2, key, preRemoteRedoRoot, act = sync)
        assertEquals(d1.xml(key), d2.xml(key), "redo converges on both replicas")

        assertRebuild(d1)
        assertRebuild(d2)
    }

    @Test
    fun test_undo_and_redo_report_where_they_landed_and_reach_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            // Shape 1/5: insert text ("abcd" -> "abXcd").
            runShape(
                d1,
                d2,
                ::sync,
                key = "t1",
                setupTree = element("root") { text { "abcd" } },
                performEdit = { tree -> tree.edit(2, 2, text { "X" }) },
                beforeEditXml = "<root>abcd</root>",
                afterEditXml = "<root>abXcd</root>",
            )

            // Shape 2/5: delete text ("abcd" -> "ad").
            runShape(
                d1,
                d2,
                ::sync,
                key = "t2",
                setupTree = element("root") { text { "abcd" } },
                performEdit = { tree -> tree.edit(1, 3) },
                beforeEditXml = "<root>abcd</root>",
                afterEditXml = "<root>ad</root>",
            )

            // Shape 3/5: insert a whole element ("<p>a</p>" -> "<p>a</p><p>b</p>").
            runShape(
                d1,
                d2,
                ::sync,
                key = "t3",
                setupTree = element("root") { element("p") { text { "a" } } },
                performEdit = { tree ->
                    tree.editByPath(listOf(1), listOf(1), element("p") { text { "b" } })
                },
                beforeEditXml = "<root><p>a</p></root>",
                afterEditXml = "<root><p>a</p><p>b</p></root>",
            )

            // Shape 4/5: delete a whole element ("<p>a</p><p>b</p>" -> "<p>a</p>").
            runShape(
                d1,
                d2,
                ::sync,
                key = "t4",
                setupTree = element("root") {
                    element("p") { text { "a" } }
                    element("p") { text { "b" } }
                },
                performEdit = { tree -> tree.editByPath(listOf(1), listOf(2)) },
                beforeEditXml = "<root><p>a</p><p>b</p></root>",
                afterEditXml = "<root><p>a</p></root>",
            )

            // Shape 5/5: delete two whole elements in one edit
            // ("<p>a</p><p>b</p><p>c</p>" -> "<p>a</p>").
            runShape(
                d1,
                d2,
                ::sync,
                key = "t5",
                setupTree = element("root") {
                    element("p") { text { "a" } }
                    element("p") { text { "b" } }
                    element("p") { text { "c" } }
                },
                performEdit = { tree -> tree.editByPath(listOf(1), listOf(3)) },
                beforeEditXml = "<root><p>a</p><p>b</p><p>c</p></root>",
                afterEditXml = "<root><p>a</p></root>",
            )

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }

    // Probe P2, over the real sync loop: an undo whose node lands under a
    // PEER's removed ancestor. d1 deletes just the leaf text (its own undo
    // entry); d2 concurrently removes the whole paragraph that text lives in,
    // before either side has synced the other's edit. d1's later undo still
    // executes (structurally revives the text node) but cannot measure a
    // position under the now-tombstoned "<p>", so it must publish exactly
    // once with an EMPTY operations list -- never suppressed -- and the
    // change still reaches d2 and the server, advancing d2's version vector
    // for d1's actor even though `operations` is empty (JS
    // `history_tree_opinfo_test`'s own integration case: the undo must still
    // be delivered). Port yorkie-js-sdk `790422ce` (#1421).
    //
    // Re-probes backlog 013 (`restore under a tombstoned parent diverges`,
    // pinned by `TreeUpstreamDefectPinTest`): un-tombstoning the leaf text
    // under its still-removed parent is counted LIVE by the running
    // `docSize` even though the node stays structurally unreachable (hidden)
    // from the root, so a from-scratch rebuild -- which only walks
    // live-reachable nodes -- disagrees with it here. Shared JS/Go/Android,
    // deliberately not fixed Android-only; this test RECORDS the divergence
    // instead of asserting `docSize == rebuild`, the same way the open
    // §9.5 pair in `TreeReachedSetServerRootTest` records instead of
    // asserting (D19). The visible XML/JSON -- what every oracle below this
    // comment actually checks -- is unaffected: all three (d1, d2, the
    // server) still agree on the hidden node reporting nothing.
    @Test
    fun test_undo_under_a_removed_ancestor_reaches_the_peer_and_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("root") {
                        element("p") { text { "ab" } }
                        element("p") { text { "cd" } }
                    },
                )
            }.await().getOrThrow()
            sync()

            // d1 deletes just the first paragraph's text -- its own undo entry.
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 3) }
                .await().getOrThrow()
            assertEquals("<root><p></p><p>cd</p></root>", d1.xml("t"))

            // d2 concurrently removes the WHOLE first paragraph -- the
            // ancestor of the text node d1 is about to undo -- before either
            // side has synced the other's edit.
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1)) }
                .await().getOrThrow()
            assertEquals("<root><p>cd</p></root>", d2.xml("t"))

            sync()
            assertEquals("<root><p>cd</p></root>", d1.xml("t"))
            assertEquals(
                d1.xml("t"),
                d2.xml("t"),
                "both replicas converge on the concurrent deletes",
            )

            val c1Actor = c1.requireActorId()
            val vvBeforeUndo = d2.getVersionVector().get(c1Actor) ?: 0L

            val d1Events = mutableListOf<Document.Event>()
            coroutineScope {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.collect(d1Events::add)
                }
                d1.history.undoAsync().await().getOrThrow()
                job.cancel()
            }
            val d1LocalChanges = d1Events.filterIsInstance<Document.Event.LocalChange>()
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
                d1.xml("t"),
                "the revive is structural only -- the node stays under its tombstoned parent",
            )

            val d2Events = mutableListOf<Document.Event>()
            coroutineScope {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.collect(d2Events::add)
                }
                sync()
                job.cancel()
            }
            val d2RemoteChanges = d2Events.filterIsInstance<Document.Event.RemoteChange>()
            assertEquals(
                1,
                d2RemoteChanges.size,
                "the peer publishes the delivered undo exactly once",
            )
            assertTrue(
                d2RemoteChanges.single().changeInfo.operations.isEmpty(),
                "the peer's own copy cannot measure a position either",
            )
            assertEquals(
                d1.xml("t"),
                d2.xml("t"),
                "both replicas converge after the undo is delivered",
            )

            val vvAfterUndo = d2.getVersionVector().get(c1Actor)
            assertTrue(
                vvAfterUndo != null && vvAfterUndo > vvBeforeUndo,
                "the peer's version vector for the undoing actor must advance despite empty ops",
            )

            // backlog 013 re-probe (see the class-level comment above this
            // test): RECORD the running vs. from-scratch-rebuild docSize.live
            // instead of asserting them equal -- the revived "ab" text stays
            // hidden under the tombstoned "<p>" on both replicas, which is
            // what the XML/JSON/server-root oracles below actually check.
            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            println(
                "spec 036 backlog 013 re-probe -- d1.docSize=${d1.getDocSize()} " +
                    "d1Rebuild.docSize=${d1Rebuild.docSize} d2.docSize=${d2.getDocSize()} " +
                    "d2Rebuild.docSize=${d2Rebuild.docSize}",
            )

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }
}
