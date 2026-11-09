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
 * Instrumented twin of [TextRemovalLedgerServerRootTest] for the SNAPSHOT encode path: a
 * late joiner that pulls a server SNAPSHOT (not the full change history) must decode
 * `removal_events` from it, not just from per-change wire bytes. d1 and d2 run the same
 * concurrent-deletion-then-undo dance against a real `yorkieteam/yorkie:0.7.26` server
 * (d1 deletes "234", d2 concurrently deletes the overlapping "345", d1 undoes its own
 * deletion only -- "2" revives, "34" stays removed, held by d2's still-live deletion), then
 * d1 immediately REDOES it (re-removing "2", back to "34" AND "2" both held removed) --
 * done here, before any further local change, since a later edit would clear the redo
 * stack. d1 then pushes enough further changes to cross the server's snapshot threshold,
 * so when d3 attaches, the server answers with a SNAPSHOT rather than replaying every
 * change. d3 must still see both "2" and "34" correctly removed (decoded from the
 * snapshot's ledger) and converge with d1/d2 and the server's own replayed root.
 */
@RunWith(AndroidJUnit4::class)
class TextRemovalLedgerSnapshotServerRootTest {

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
    fun test_late_joiner_after_a_server_snapshot_decodes_the_removal_ledger_then_redo_converges() {
        createThreeClientsAndDocuments { c1, c2, c3, d1, d2, d3, documentKey ->
            // d3 joins LATE, below -- only c1/d1 and c2/d2 attach up front.
            c1.activateAsync().await()
            c2.activateAsync().await()
            c3.activateAsync().await()
            attachAndAwaitWatch(c1, d1, Manual)
            attachAndAwaitWatch(c2, d2, Manual)

            suspend fun syncTwo() {
                c1.syncAsync().await()
                c2.syncAsync().await()
                c1.syncAsync().await()
            }

            d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "0123456789") }.await()
            syncTwo()

            // Concurrent: d1 deletes "234", d2 deletes the overlapping "345".
            d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(2, 5, "") }.await()
            d2.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(3, 6, "") }.await()
            syncTwo()
            assertEquals("016789", d1.getRoot().getAs<JsonText>("t").toString())

            // d1 undoes its own deletion only: "2" revives, "34" (removed by both) stays
            // removed, held by d2's still-live deletion.
            d1.history.undoAsync().await()
            syncTwo()
            assertEquals("0126789", d1.getRoot().getAs<JsonText>("t").toString())
            assertEquals("0126789", d2.getRoot().getAs<JsonText>("t").toString())

            // Redo NOW, before any further local change (a later edit would clear the redo
            // stack): re-removes "2", converging back to "016789" with BOTH "2" and "34"
            // held removed.
            d1.history.redoAsync().await()
            syncTwo()
            assertEquals("016789", d1.getRoot().getAs<JsonText>("t").toString())
            assertEquals("016789", d2.getRoot().getAs<JsonText>("t").toString())

            // Push the running change count past the server's snapshot threshold so a
            // late joiner (d3, attached below) pulls a SNAPSHOT, not the full change
            // history -- the held "2"/"34" pieces' ledgers must survive THAT encode path.
            for (idx in 0 until DEFAULT_SNAPSHOT_THRESHOLD / 2) {
                d1.updateAsync { root, _ ->
                    root.getAs<JsonText>("t").edit(0, 0, "${idx % 10}")
                }.await()
                c1.syncAsync().await()
                c2.syncAsync().await()
            }
            val expected = d1.getRoot().getAs<JsonText>("t").toString()
            assertEquals(expected, d2.getRoot().getAs<JsonText>("t").toString())

            attachAndAwaitWatch(c3, d3, Manual)
            c1.syncAsync().await()
            c3.syncAsync().await()

            assertEquals(expected, d3.getRoot().getAs<JsonText>("t").toString())
            assertJsonContentEquals(d1.toJson(), d2.toJson())
            assertJsonContentEquals(d1.toJson(), d3.toJson())
            assertJsonContentEquals(d1.toJson(), serverRoot(documentKey))

            c1.detachDocument(d1).await()
            c2.detachDocument(d2).await()
            c3.detachDocument(d3).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c3.deactivateAsync().await()
            d1.close()
            d2.close()
            d3.close()
            c1.close()
            c2.close()
            c3.close()
        }
    }
}
