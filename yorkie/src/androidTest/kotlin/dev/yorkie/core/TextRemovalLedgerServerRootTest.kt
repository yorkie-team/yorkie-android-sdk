package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.json.JsonText
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for the Text removal ledger (yorkie-js-sdk #1514,
 * server twin yorkie#2310) against a real Yorkie server pinned to
 * `yorkieteam/yorkie:0.7.26`, with the server's OWN replayed root as a third
 * oracle beyond the two clients agreeing with each other (pattern:
 * [StyleTombstoneUndoServerRootTest.serverRoot]).
 *
 * d1 deletes "234" while d2 concurrently deletes the overlapping "345"; once
 * synced, "2345" is gone from both ("016789"). d1 then undoes its own
 * deletion: its undo can only cancel its own actor's removal, so "2" (which
 * only d1 ever removed) comes back, while "34" (removed by both) stays
 * removed -- an undo must never revive a concurrent peer's independent
 * deletion of the same text. Several more sync rounds give the server's
 * usual snapshot/minimum-version-vector schedule a chance to run garbage
 * collection on the still-removed "34"/"5" without racing it: this test
 * asserts convergence, not a specific purge count, matching
 * [StyleTombstoneUndoServerRootTest]'s own stance.
 */
@RunWith(AndroidJUnit4::class)
class TextRemovalLedgerServerRootTest {

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
    fun test_undo_meeting_a_concurrent_deletion_converges_with_the_server_root() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync().await()
                c2.syncAsync().await()
                c1.syncAsync().await()
            }

            d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "0123456789") }.await()
            sync()

            // Concurrent: d1 deletes "234", d2 deletes the overlapping "345",
            // neither having seen the other's change yet.
            d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(2, 5, "") }.await()
            d2.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(3, 6, "") }.await()
            sync()

            assertJsonContentEquals(d1.toJson(), d2.toJson())
            assertEquals("016789", d1.getRoot().getAs<JsonText>("t").toString())

            // d1 undoes its own deletion: "2" (which only d1 ever removed)
            // comes back, but "34" (removed by both) stays removed -- the
            // undo must cancel only d1's own removal, never d2's.
            d1.history.undoAsync().await()
            sync()

            // A few more rounds for the server's usual snapshot/minimum-
            // version-vector schedule to run a GC pass on the still-removed
            // "34"/"5" without this test racing it.
            sync()
            sync()

            val expected = "0126789"
            assertEquals(expected, d1.getRoot().getAs<JsonText>("t").toString())
            assertEquals(expected, d2.getRoot().getAs<JsonText>("t").toString())
            assertJsonContentEquals(d1.toJson(), d2.toJson())
            assertJsonContentEquals(d1.toJson(), serverRoot(documentKey))
        }
    }
}
