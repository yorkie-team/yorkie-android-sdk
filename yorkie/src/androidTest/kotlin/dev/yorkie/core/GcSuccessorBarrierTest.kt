package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.gson
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented headline for #1405 (`dbff8892`):
 * the two JS `gc_test.ts` integration cases that exercise the successor
 * barrier end to end over a real Yorkie server (compose pin
 * `yorkieteam/yorkie:0.7.24`), not just the JVM in-process shapes
 * [dev.yorkie.document.GcSuccessorBarrierTest] already pins.
 *
 * Both cases replay the exact sequence JS's own `gc_test.ts` uses (ported
 * 1:1, cited inline) -- a concurrent insert positioned next to a concurrent
 * delete, so the new node becomes the successor the forward skip depends on,
 * and the tombstone it is adjacent to cannot be purged until every replica
 * that might still need that skip has caught up. Oracle each: both
 * documents equal in content, the server's OWN root equal via admin
 * `GetDocuments` (pattern: [TreeSplitUndoServerRootTest.serverRoot]), and
 * `docSize` equals a from-scratch [CrdtRoot] rebuild on each client.
 */
@RunWith(AndroidJUnit4::class)
class GcSuccessorBarrierTest {

    private val http = OkHttpClient()

    /**
     * The server's own replayed root for [documentKey], via admin
     * `GetDocuments` -- pattern: [TreeSplitUndoServerRootTest.serverRoot].
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

    private fun JsonObject.rootText() = getAs<JsonText>("t")

    /**
     * Port of JS `gc_test.ts`'s "successor barrier drains within one round"
     * (`dbff8892`, #1405) -- the same interleaving as JS's "concurrent
     * garbage collection test" (and this project's
     * [dev.yorkie.core.GCTest.test_gc_with_text_for_multi_client] family,
     * which never happens to hit this exact race, so no existing `GCTest`
     * assertion moved), carried one round further: a delay is acceptable, a leak is
     * not, so retention has to reach zero once both replicas sync with no
     * further edits.
     */
    @Test
    fun test_successor_barrier_drains_within_one_round() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            d1.updateAsync { root, _ ->
                root.setNewText("t").apply {
                    edit(0, 0, "a")
                    edit(1, 1, "b")
                    edit(2, 2, "c")
                }
            }.await().getOrThrow()
            c1.syncAsync(d1).await().getOrThrow()
            c2.syncAsync(d2).await().getOrThrow()

            // d2 inserts next to what d1 is about to delete. The tombstone
            // d1 leaves is what stops the forward skip d2's insert is
            // positioned by.
            d2.updateAsync { root, _ -> root.rootText().edit(2, 2, "c") }
                .await().getOrThrow()
            d1.updateAsync { root, _ -> root.rootText().edit(1, 3, "") }
                .await().getOrThrow()
            c1.syncAsync(d1).await().getOrThrow()
            c2.syncAsync(d2).await().getOrThrow()

            d2.updateAsync { root, _ -> root.rootText().edit(2, 2, "1") }
                .await().getOrThrow()
            c2.syncAsync(d2).await().getOrThrow()
            c1.syncAsync(d1).await().getOrThrow()

            // Where JS's "concurrent garbage collection test" stops: one
            // tombstone each, still held back by the successor barrier
            // (dbff8892, #1405) -- purging it now would move the stopping
            // point of the forward skip d2's concurrent insert still
            // depends on.
            assertEquals(1, d1.garbageLength)
            assertEquals(1, d2.garbageLength)

            // One more round with no edits at all has to drain it: the
            // barrier must delay, not prevent.
            c1.syncAsync(d1).await().getOrThrow()
            c2.syncAsync(d2).await().getOrThrow()
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            // Further rounds must not resurrect anything.
            repeat(3) {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                assertEquals(0, d1.garbageLength)
                assertEquals(0, d2.garbageLength)
            }

            assertEquals(
                d1.getRoot().rootText().toString(),
                d2.getRoot().rootText().toString(),
            )
            assertEquals("ac1", d1.getRoot().rootText().toString())

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

    /**
     * Port of JS `gc_test.ts`'s "successor barrier drains after the
     * successor author detaches" (`dbff8892`, #1405). A barrier ticket is
     * authored by whoever created the successor node, not by the remover, so
     * that author may have left the document by the time the purge is due.
     * The min version vector the server returns always includes the
     * collecting client's own vector, and a client holding the successor
     * node has applied its author's change, so the author's actor is always
     * present in it -- dropping the author's row only removes a lower bound,
     * it does not make the barrier unsatisfiable.
     *
     * Manual three-client setup (not [withThreeClientsAndDocuments], which
     * auto-detaches at the end before this test can assert post-detach
     * state) -- pattern:
     * [TreeSplitUndoServerRootTest.test_undo_of_two_merges_in_one_entry_keeps_unique_ids_on_every_replica_and_server_root].
     */
    @Test
    fun test_successor_barrier_drains_after_the_successor_author_detaches() {
        createThreeClientsAndDocuments {
                rawClient1,
                rawClient2,
                rawClient3,
                rawDocument1,
                rawDocument2,
                rawDocument3,
                documentKey,
            ->
            rawClient1.activateAsync().await()
            rawClient2.activateAsync().await()
            rawClient3.activateAsync().await()

            // Fix the actor order the same way withTwoClientsAndDocuments /
            // withThreeClientsAndDocuments / TreeSplitUndoServerRootTest do.
            val pairs = listOf(
                rawClient1 to rawDocument1,
                rawClient2 to rawDocument2,
                rawClient3 to rawDocument3,
            ).sortedBy { it.first.requireActorId() }
            val (c1, d1) = pairs[0]
            val (c2, d2) = pairs[1]
            val (c3, d3) = pairs[2]

            try {
                attachAndAwaitWatch(c1, d1, Manual)
                attachAndAwaitWatch(c2, d2, Manual)
                attachAndAwaitWatch(c3, d3, Manual)

                d1.updateAsync { root, _ ->
                    root.setNewText("t").apply {
                        edit(0, 0, "a")
                        edit(1, 1, "b")
                        edit(2, 2, "c")
                    }
                }.await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c3.syncAsync(d3).await().getOrThrow()

                // c3 inserts "X" right behind "b" while c1 deletes "b": "X"
                // becomes the successor of the tombstone, so its barrier
                // ticket is c3's.
                d3.updateAsync { root, _ -> root.rootText().edit(2, 2, "X") }
                    .await().getOrThrow()
                d1.updateAsync { root, _ -> root.rootText().edit(1, 2, "") }
                    .await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
                c3.syncAsync(d3).await().getOrThrow()
                c1.syncAsync(d1).await().getOrThrow()
                assertEquals("aXc", d1.getRoot().rootText().toString())

                // c3 leaves; its row is removed from the server.
                c3.detachDocument(d3).await()

                // No further edits. The purge may be held back while c2 has
                // not seen "X", but it has to drain once c2 catches up,
                // although "X"'s author has gone.
                repeat(3) {
                    c2.syncAsync(d2).await().getOrThrow()
                    c1.syncAsync(d1).await().getOrThrow()
                }
                assertEquals(0, d1.garbageLength, "d1 retained the tombstone")
                assertEquals(0, d2.garbageLength, "d2 retained the tombstone")
                assertEquals(
                    d1.getRoot().rootText().toString(),
                    d2.getRoot().rootText().toString(),
                )

                val d1Rebuild = CrdtRoot(d1.getRootObject().deepCopy())
                val d2Rebuild = CrdtRoot(d2.getRootObject().deepCopy())
                assertEquals(d1Rebuild.docSize, d1.getDocSize())
                assertEquals(d2Rebuild.docSize, d2.getDocSize())
                assertEquals(d1.getDocSize(), d2.getDocSize())

                val serverJson = serverRoot(documentKey)
                assertJsonContentEquals(serverJson, d1.toJson())
                assertJsonContentEquals(serverJson, d2.toJson())
            } finally {
                c1.detachDocument(d1).await()
                c2.detachDocument(d2).await()
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
}
