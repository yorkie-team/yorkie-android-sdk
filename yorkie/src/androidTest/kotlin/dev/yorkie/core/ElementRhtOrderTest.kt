package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.Document
import dev.yorkie.gson
import dev.yorkie.util.postApi
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for yorkie-js-sdk `9970907c` (v0.7.22, yorkie-js-sdk#1343) against a real
 * Yorkie server (compose pin `yorkieteam/yorkie:0.7.22`) -- the exact live scenario in which
 * a fresh client decoded an undone container as empty in 9-11 of 12 runs.
 *
 * c1 sets `frame=v1`, sets `frame=v2`, undoes back to `v1`; both clients sync; then a FRESH third
 * client attaches the same key, decoding the server's own snapshot. [serverRoot] reads the
 * server's own replayed root through the admin API (pattern: [GcContainmentTest.serverRoot] /
 * `OfflinePersistenceTest.kt:96-116`) as a fourth, independent oracle. Before this release's fix
 * (`ElementRht.set` anchoring both the LWW winner check and the occupant eviction on
 * `positionedAt`), the fresh client's decode was order-dependent on the server's map-ordered wire
 * bytes and could read `frame` as absent.
 */
@RunWith(AndroidJUnit4::class)
class ElementRhtOrderTest {

    private val http = OkHttpClient()

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
    fun test_a_fresh_client_decodes_an_undo_restored_key_the_same_as_the_synced_replicas() =
        withTwoClientsAndDocuments(
            syncMode = Client.SyncMode.Manual,
        ) { c1, c2, d1, d2, documentKey ->
            d1.updateAsync { root, _ -> root["frame"] = "v1" }.await()
            d1.updateAsync { root, _ -> root["frame"] = "v2" }.await()
            d1.history.undoAsync().await()
            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()

            assertJsonContentEquals("""{"frame":"v1"}""", d1.toJson())
            assertJsonContentEquals("""{"frame":"v1"}""", d2.toJson())

            val freshClient = createClient()
            freshClient.activateAsync().await()
            val freshDoc = Document(documentKey)
            freshClient.attachDocument(freshDoc, syncMode = Client.SyncMode.Manual).await()
            assertJsonContentEquals("""{"frame":"v1"}""", freshDoc.toJson())

            assertJsonContentEquals(d1.toJson(), serverRoot(documentKey))

            freshClient.detachDocument(freshDoc).await()
            freshClient.deactivateAsync().await()
            freshDoc.close()
            freshClient.close()
        }
}
