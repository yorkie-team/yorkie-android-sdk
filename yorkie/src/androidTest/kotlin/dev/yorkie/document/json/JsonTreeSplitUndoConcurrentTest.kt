package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.TEST_API_ID
import dev.yorkie.core.TEST_API_PW
import dev.yorkie.core.getYorkieServerUrl
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of JS
 * `test/integration/history_tree_concurrent_test.ts` "undo one of two
 * concurrent splits of the same node" (v0.7.23, part of #1360's
 * `e41069df`) against a real Yorkie server, mirroring the JVM
 * `TreeSplitUndoConcurrentTest` but over the wire with two real clients and
 * an admin `GetDocuments` server-root oracle ([serverRoot], shape of
 * [dev.yorkie.core.GcContainmentTest.serverRoot]) — the server's own
 * replayed root, not just what the two clients show each other, must
 * converge too.
 */
@RunWith(AndroidJUnit4::class)
class JsonTreeSplitUndoConcurrentTest {

    private val http = OkHttpClient()

    /**
     * The server's own replayed root for [documentKey], via admin
     * `GetDocuments`. See [dev.yorkie.core.GcContainmentTest.serverRoot] for
     * the LogIn -> ListProjects -> secretKey -> API-Key pattern this mirrors.
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

    @Test
    fun test_undo_one_of_two_concurrent_splits_of_the_same_node() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            // <doc><p><span>abcde</span></p></doc>
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") {
                            element("span") { text { "abcde" } }
                        }
                    },
                )
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // d1 splits after `a`; d2 splits before `e` -- neither has seen
            // the other's split yet.
            d1.updateAsync { root, _ ->
                root.getAs<JsonTree>("t")
                    .editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonTree>("t")
                    .editByPath(listOf(0, 0, 4), listOf(0, 0, 4), splitLevel = 1)
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            assertEquals(
                "<doc><p><span>a</span><span>bcd</span><span>e</span></p></doc>",
                d1.getRoot().getAs<JsonTree>("t").toXml(),
            )

            // d2 undoes its OWN split (the bcd|e boundary). d1's split
            // (a|bcd) must survive.
            d2.history.undoAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            assertEquals(
                "<doc><p><span>a</span><span>bcde</span></p></doc>",
                d1.getRoot().getAs<JsonTree>("t").toXml(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            // The server's OWN replayed root must converge too, not just
            // what the two clients show each other.
            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
        }
    }
}
