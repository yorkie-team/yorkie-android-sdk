package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.gson
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for #1426 (JS `b173c093`): redoing a split that re-creates
 * an element a merge took away must re-point the style operation that followed it at the new element, over the
 * REAL sync loop (a real Yorkie server, compose pin `yorkieteam/yorkie:0.7.24`)
 * and AFTER EACH CLIENT's own [dev.yorkie.document.Document.garbageCollect] has
 * collected the merged-away element (real tombstones a real wire round trip
 * produced, not a hand-built version vector) -- not just the in-process
 * `crossSync` wire round trip the JVM `dev.yorkie.document.TreeRedoSplitStyleTest`'s
 * "peer applies redo of a mid split with the right piece bold after GC" case
 * already covers. The GC itself is client-side ([dev.yorkie.core.Client] has no
 * server-triggered compaction API); only the sync traffic around it is real.
 *
 * Oracle: no `IllegalArgumentException: cannot find node of CrdtTreePos`
 * (the upstream symptom this commit fixes) on either client, both documents'
 * XML equal at every step, the server's OWN replayed root equal (admin
 * `GetDocuments`, pattern: [TreeSplitRemoveStyleServerRootTest.serverRoot]),
 * `docSize` equal to a from-scratch [CrdtRoot] rebuild on each client, and a
 * SECOND undo/redo round trip -- the redo re-mints the split elements again,
 * so the entry it just pushed has to name the new ones too.
 */
@RunWith(AndroidJUnit4::class)
class TreeRedoSplitServerRootTest {

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

    private fun JsonTree.asXml() = toXml()

    @Test
    fun test_redo_of_a_split_and_a_style_applies_on_the_peer_after_client_gc_and_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            // "hello world" in an inline in a paragraph -- two levels to
            // split; same seed/shape as the JVM TreeRedoSplitStyleTest's
            // "peer applies redo of a mid split with the right piece bold
            // after GC" (already green there, in-process). This run exercises
            // it over the real wire and after the real server's own GC.
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    key = "t",
                    initialRoot = element("root") {
                        element("paragraph") {
                            element("inline") {
                                text { "hello world" }
                            }
                        }
                    },
                )
            }.await().getOrThrow()
            d1.clearHistory()
            sync()

            val beforeXml = "<root><paragraph><inline>hello world</inline></paragraph></root>"
            assertEquals(beforeXml, d1.getRoot().getAs<JsonTree>("t").asXml())
            assertEquals(beforeXml, d2.getRoot().getAs<JsonTree>("t").asXml())

            // Split "hello world" after "hello " (a mid split) then bold the
            // right-hand piece -- in ONE history entry, as the JS case does.
            d1.updateAsync { root, _ ->
                val tree = root.getAs<JsonTree>("t")
                tree.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 1)
                tree.styleByPath(listOf(0, 1), mapOf("bold" to "true"))
            }.await().getOrThrow()
            sync()
            val editedXml = d1.getRoot().getAs<JsonTree>("t").asXml()
            assertEquals(editedXml, d2.getRoot().getAs<JsonTree>("t").asXml(), "edit converges")

            d1.history.undoAsync().await().getOrThrow()
            sync()
            val undoneXml = d1.getRoot().getAs<JsonTree>("t").asXml()
            assertEquals(beforeXml, undoneXml)
            assertEquals(undoneXml, d2.getRoot().getAs<JsonTree>("t").asXml(), "undo converges")

            // Both replicas have now seen the undo over the wire -- sync once
            // more so each side's version vector reflects the other's before
            // collecting, then collect for real on both: the merged-away
            // "inline" (and the split-created, now-undone pieces) are
            // actually purged, not simulated with a hand-built vector.
            sync()
            val actors = listOf(c1.requireActorId(), c2.requireActorId())
            d1.garbageCollect(maxVectorOf(actors))
            d2.garbageCollect(maxVectorOf(actors))
            // The premise this "after GC" run relies on (the merge-away is really
            // gone, not simulated): unlike the in-process JVM twin, a real sync's
            // OWN pull already runs Document.applyChangePack's unconditional
            // trailing garbageCollect(pack.versionVector), so the explicit calls
            // above measure 0 purged here -- the server round trip already
            // collected it during the preceding sync() calls. Checking garbageLength
            // instead of a purge count is invariant to exactly when that happened:
            // either way, nothing should be left outstanding before the redo.
            assertEquals(0, d1.garbageLength, "d1 must have nothing left to purge here")
            assertEquals(0, d2.garbageLength, "d2 must have nothing left to purge here")

            // The redo must re-point the style operation at the NEW split
            // elements -- this is the exact case that threw
            // `IllegalArgumentException: cannot find node of CrdtTreePos`
            // before this commit, now that the merged-away element is
            // actually gone on both replicas.
            d1.history.redoAsync().await().getOrThrow()
            assertEquals(editedXml, d1.getRoot().getAs<JsonTree>("t").asXml(), "redo, locally")
            sync()
            assertEquals(editedXml, d2.getRoot().getAs<JsonTree>("t").asXml(), "redo, on the peer")

            val d1RebuildAfterRedo = CrdtRoot(d1.getRootObject().deepCopy())
            val d2RebuildAfterRedo = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1RebuildAfterRedo.docSize, d1.getDocSize())
            assertEquals(d2RebuildAfterRedo.docSize, d2.getDocSize())
            assertEquals(d1.getDocSize(), d2.getDocSize())

            val serverJsonAfterRedo = serverRoot(documentKey)
            assertJsonContentEquals(serverJsonAfterRedo, d1.toJson())
            assertJsonContentEquals(serverJsonAfterRedo, d2.toJson())

            // A second round trip: the redo re-minted the split elements, so
            // the entry it just pushed has to name the new ones too.
            d1.history.undoAsync().await().getOrThrow()
            sync()
            assertEquals(
                undoneXml,
                d1.getRoot().getAs<JsonTree>("t").asXml(),
                "second undo, locally",
            )
            assertEquals(
                undoneXml,
                d2.getRoot().getAs<JsonTree>("t").asXml(),
                "second undo, on the peer",
            )

            d1.history.redoAsync().await().getOrThrow()
            sync()
            assertEquals(
                editedXml,
                d1.getRoot().getAs<JsonTree>("t").asXml(),
                "second redo, locally",
            )
            assertEquals(
                editedXml,
                d2.getRoot().getAs<JsonTree>("t").asXml(),
                "second redo, on the peer",
            )

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
