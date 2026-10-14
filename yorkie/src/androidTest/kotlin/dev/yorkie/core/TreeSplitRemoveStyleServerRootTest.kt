package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for yorkie-js-sdk v0.7.24 (`c8928853` / #1375)
 * against a real Yorkie server (compose pin `yorkieteam/yorkie:0.7.24`).
 * Ports `TreeRemoveStyleSplitTest`'s `converges whichever side arrives
 * first` JVM case as a two-client, Manual-sync,
 * over-the-wire run, with the server's OWN replayed root
 * (pattern: [GcContainmentTest.serverRoot]) and a from-scratch
 * [CrdtRoot] rebuild of each client's own root as two further oracles beyond
 * the two clients agreeing with each other.
 *
 * d1 splits `<span bold="true">abcde</span>` at the boundary between "abc"
 * and "de" (`editByPath(.., splitLevel = 1)`); concurrently, BEFORE seeing
 * that split, d2 `removeStyleByPath`-es the whole original span's `bold`
 * attribute. Once both changes cross the wire, `CrdtTree.removeStyle`'s
 * `advancePastUnknownSplitSiblings` call (from the #1375 port) must carry
 * d2's attribute range across the boundary d1's split introduced, so the
 * removal reaches BOTH resulting spans rather than stopping at the first:
 * confirmed locally (JVM, in-process `exchange`, not this file) to converge
 * on `<doc><p><span>abc</span><span>de</span></p></doc>` -- no `bold` left on
 * either side. Red at `0d0b2aac`: `removeStyle` had no advance at all, so the
 * second span would keep `bold="true"` on whichever replica applied the
 * split locally before the remote `removeStyle` arrived.
 */
@RunWith(AndroidJUnit4::class)
class TreeSplitRemoveStyleServerRootTest {

    private val http = OkHttpClient()

    /**
     * The server's own replayed root for [documentKey], via admin
     * `GetDocuments` -- pattern: [GcContainmentTest.serverRoot].
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
    fun test_concurrent_split_and_remove_style_converge_with_server_root_and_doc_size_rebuild() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    key = "t",
                    initialRoot = element("doc") {
                        element("p") {
                            element("span") {
                                attr { "bold" to "true" }
                                text { "abcde" }
                            }
                        }
                    },
                )
            }.await().getOrThrow()
            sync()

            // Concurrently, before either side has seen the other's change:
            // d1 splits the span at the "abc"/"de" boundary, d2 removes the
            // whole original span's `bold` attribute.
            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("t")
                    .editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
            }.await().getOrThrow()
            d2.updateAsync { root, _ ->
                root.getAs<JsonTree>("t")
                    .removeStyleByPath(listOf(0, 0), listOf(0, 1), listOf("bold"))
            }.await().getOrThrow()
            sync()

            val expectedXml = "<doc><p><span>abc</span><span>de</span></p></doc>"
            val d1Xml = d1.getRoot().getAs<JsonTree>("t").asXml()
            val d2Xml = d2.getRoot().getAs<JsonTree>("t").asXml()
            assertEquals(expectedXml, d1Xml)
            assertEquals(expectedXml, d2Xml)

            // docSize equals a from-scratch CrdtRoot(deepCopy) rebuild on
            // both clients -- the #1392 docSize-rebuild invariant, checked here too
            // since this run already has both replicas settled.
            val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
            val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
            assertEquals(d1Rebuild.docSize, d1.getDocSize())
            assertEquals(d2Rebuild.docSize, d2.getDocSize())
            assertEquals(d1.getDocSize(), d2.getDocSize())

            // The server's OWN replayed root, not just what the two clients
            // show each other, must equal both clients.
            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }
}
