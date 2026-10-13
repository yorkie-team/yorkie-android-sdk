package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for spec 036 Commit 4 (JS `6932da6a`, #1447, unit
 * 4D): a LOCAL Text/Tree index that splits a UTF-16 surrogate pair (one
 * non-BMP character, "😀", same fixture as the JVM `SurrogateIndexTest`) is
 * rejected over the REAL sync loop (a real Yorkie server, compose pin
 * `yorkieteam/yorkie:0.7.24`) exactly as the JVM in-process cases already
 * pin, and a whole-character edit on either side of the pair still
 * converges -- including the server's OWN replica (admin `GetDocuments`,
 * pattern: [TreeRedoSplitServerRootTest.serverRoot]).
 *
 * Oracle: a rejected update fails [kotlin.Result.isFailure], leaves the
 * local document, [Document.hasLocalChanges] and the server's own root
 * untouched; every valid edit converges on both clients, the server's own
 * root, and `docSize` equal to a from-scratch [CrdtRoot] rebuild.
 */
@RunWith(AndroidJUnit4::class)
class SurrogateIndexServerRootTest {

    private val http = OkHttpClient()

    /**
     * The server's own replayed root for [documentKey], via admin
     * `GetDocuments` -- pattern: [TreeRedoSplitServerRootTest.serverRoot].
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

    /** [document]'s `docSize` must equal a from-scratch [CrdtRoot] rebuild of its own root. */
    private fun assertRebuild(document: Document) {
        val rebuild = CrdtRoot(document.getRootObject().deepCopy())
        assertEquals(
            rebuild.docSize,
            document.getDocSize(),
            "docSize must equal a from-scratch rebuild",
        )
    }

    @Test
    fun test_text_and_tree_with_a_non_bmp_character_converge_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            // Same fixture as the JVM SurrogateIndexTest: one non-BMP
            // character ("😀", a high + low surrogate) followed by "x".
            // Text index 1 and Tree index 2 are the only positions that cut
            // the emoji in half.
            d1.updateAsync { root, _ ->
                root.setNewText("txt").edit(0, 0, "😀x")
                root.setNewTree(
                    key = "tree",
                    initialRoot = element("r") {
                        element("p") {
                            text { "😀x" }
                        }
                    },
                )
            }.await().getOrThrow()
            d1.clearHistory()
            sync()

            assertEquals("😀x", d1.getRoot().getAs<JsonText>("txt").toString())
            assertEquals("😀x", d2.getRoot().getAs<JsonText>("txt").toString())
            assertEquals("<r><p>😀x</p></r>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertEquals("<r><p>😀x</p></r>", d2.getRoot().getAs<JsonTree>("tree").toXml())

            val serverRootBeforeRejections = serverRoot(documentKey)

            // A mid-pair Text edit is rejected: it fails the whole update,
            // leaves the document and the pending queue untouched, and never
            // reaches the peer or the server.
            val rejectedText = d1.updateAsync { root, _ ->
                root.getAs<JsonText>("txt").edit(1, 1, "y")
            }.await()
            assertTrue(rejectedText.isFailure, "a mid-pair Text edit must be rejected")
            assertEquals("😀x", d1.getRoot().getAs<JsonText>("txt").toString())
            assertFalse(d1.hasLocalChanges(), "the rejected edit queued no change")

            // A mid-pair Tree edit is rejected the same way.
            val rejectedTree = d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(2, 2, JsonTree.TextNode("y"))
            }.await()
            assertTrue(rejectedTree.isFailure, "a mid-pair Tree edit must be rejected")
            assertEquals("<r><p>😀x</p></r>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertFalse(d1.hasLocalChanges(), "the rejected edit queued no change")

            sync()
            assertEquals("😀x", d2.getRoot().getAs<JsonText>("txt").toString(), "d2 untouched")
            assertEquals(
                "<r><p>😀x</p></r>",
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
                "d2 untouched",
            )
            assertEquals(
                serverRootBeforeRejections,
                serverRoot(documentKey),
                "the server's own root is untouched by the rejected edits",
            )

            // Whole-character edits on either side of the pair still apply
            // and converge, locally and on the server.
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("txt").edit(0, 0, "A")
                root.getAs<JsonTree>("tree").edit(3, 3, JsonTree.TextNode("B"))
            }.await().getOrThrow()
            sync()

            assertEquals("A😀x", d1.getRoot().getAs<JsonText>("txt").toString())
            assertEquals("A😀x", d2.getRoot().getAs<JsonText>("txt").toString())
            assertEquals("<r><p>😀Bx</p></r>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertEquals("<r><p>😀Bx</p></r>", d2.getRoot().getAs<JsonTree>("tree").toXml())

            assertRebuild(d1)
            assertRebuild(d2)

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }
}
