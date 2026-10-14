package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.TreeElementNode
import dev.yorkie.document.crdt.TreeTextNode
import dev.yorkie.document.history.History
import dev.yorkie.document.history.HistoryOperation
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.gson
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for the OTHER half of #1426 (JS `b173c093`):
 * `reissueContentIDs`, the content-reissue counterpart to
 * [TreeRedoSplitServerRootTest]'s split-ticket case, over the REAL sync loop
 * (a real Yorkie server, compose pin `yorkieteam/yorkie:0.7.24`) and after
 * each client's own real [Document.garbageCollect].
 *
 * Reachability (see `TreeUndoContentIdentityTest`'s class KDoc): Android's
 * copy-reinsert reverse (`TreeEditOperation.removedNodeSnapshots`) is
 * unreachable via any public-API edit sequence found this round -- a plain
 * delete always takes the identity-preserving restore path
 * (`CrdtTree.edit`'s `spansComplete` guard). d1's undo/redo entry is seeded
 * directly (reflection onto `internalHistory`, the SAME technique
 * `TreeUndoContentIdentityTest`'s `undoAsync reserves minted tickets...`
 * case already uses for the identical reachability gap) -- this is still a
 * REAL two-client, real-server, real-GC run: only the entry's CONTENTS are
 * hand-built, nothing about how d1 executes it, pushes the resulting
 * change, or how d2/the server receive and apply that change is simulated.
 *
 * Oracle: both documents' XML equal at every step, the server's own replayed
 * root equal (admin `GetDocuments`), `docSize` equal to a from-scratch
 * [CrdtRoot] rebuild on each client -- no throw, no silently dropped content.
 */
@RunWith(AndroidJUnit4::class)
class TreeUndoContentReissueServerRootTest {

    private val http = OkHttpClient()

    /** Pattern: [TreeRedoSplitServerRootTest.serverRoot]. */
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

    private fun internalHistoryOf(document: Document): History =
        Document::class.java.getDeclaredField("internalHistory")
            .apply { isAccessible = true }
            .get(document) as History

    @Test
    fun test_copy_reinsert_repoint_applies_on_the_peer_after_gc_and_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                root.setNewTree("t", element("root") { element("p") { text { "ab" } } })
            }.await().getOrThrow()
            d1.clearHistory()
            sync()

            val beforeXml = "<root><p>ab</p></root>"
            assertEquals(beforeXml, d1.getRoot().getAs<JsonTree>("t").toXml())
            assertEquals(beforeXml, d2.getRoot().getAs<JsonTree>("t").toXml())

            val tree = d1.getRootObject()["t"] as CrdtTree
            val pID = tree.indexTree.root.allChildren.first { it.type == "p" }.id

            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1))
            }.await().getOrThrow()
            sync()
            val deletedXml = "<root></root>"
            assertEquals(deletedXml, d1.getRoot().getAs<JsonTree>("t").toXml())
            assertEquals(deletedXml, d2.getRoot().getAs<JsonTree>("t").toXml(), "delete converges")

            val history = internalHistoryOf(d1)
            history.popUndo() // the real delete's own (identity-preserving) reverse -- unused here

            val copyReinsertOp = TreeEditOperation(
                parentCreatedAt = tree.createdAt,
                fromPos = tree.findPos(0),
                toPos = tree.findPos(0),
                contents = null,
                splitLevel = 0,
                executedAt = TimeTicket.InitialTimeTicket,
                undoFromOffset = 0,
                undoToOffset = 0,
                removedNodeSnapshots = listOf(
                    TreeElementNode(type = "p", childNodes = listOf(TreeTextNode("ab"))),
                ),
                removedNodeOldIDs = listOf(pID),
            )
            // The later, same-entry op: insert "X" as pID's first child --
            // naming the OLD id precisely, so this only lands once re-pointed
            // at the fresh copy-reinsert's node.
            val insertXOp = TreeEditOperation(
                parentCreatedAt = tree.createdAt,
                fromPos = CrdtTreePos(pID, pID),
                toPos = CrdtTreePos(pID, pID),
                contents = listOf(
                    CrdtTreeText(CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0), "X"),
                ),
                splitLevel = 0,
                executedAt = TimeTicket.InitialTimeTicket,
            )
            history.pushUndo(
                listOf(HistoryOperation.Op(copyReinsertOp), HistoryOperation.Op(insertXOp)),
            )

            val undoResult = d1.history.undoAsync().await()
            assertTrue(undoResult.isSuccess, "undo must apply cleanly: $undoResult")
            val undoneXml = "<root><p>Xab</p></root>"
            assertEquals(
                undoneXml,
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "the insert must land on the fresh node, not vanish against the stale id",
            )
            sync()
            assertEquals(
                undoneXml,
                d2.getRoot().getAs<JsonTree>("t").toXml(),
                "the peer must receive the SAME re-pointed change over the wire",
            )

            // Real GC on both replicas -- the old (now fully dead) tombstone
            // this round's entry replaced is purged for real, not simulated.
            sync()
            val actors = listOf(c1.requireActorId(), c2.requireActorId())
            d1.garbageCollect(maxVectorOf(actors))
            d2.garbageCollect(maxVectorOf(actors))

            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1Rebuild.docSize, d1.getDocSize())
            assertEquals(d2Rebuild.docSize, d2.getDocSize())
            assertEquals(d1.getDocSize(), d2.getDocSize())

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }
}
