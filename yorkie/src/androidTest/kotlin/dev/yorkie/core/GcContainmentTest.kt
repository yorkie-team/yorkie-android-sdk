package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonElement
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.gson
import dev.yorkie.util.YorkieException
import dev.yorkie.util.postApi
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import com.google.gson.JsonObject as GsonJsonObject

/**
 * Ports `gc_containment_test.ts`'s integration case (yorkie-js-sdk v0.7.21,
 * `1ad69a12`, PR #1341, closes #1340) as an instrumented two-client test
 * against a real Yorkie server (compose pin `yorkieteam/yorkie:0.7.21`), with
 * iOS liveness pings (`5054ed7e2a`) added at the end. Two peers reorder, edit
 * and undo the same array-of-objects over many rounds; any exception out of a
 * sync call is the #1340 wedge this port fixes.
 *
 * The step sequence is JS-faithful, step for step with
 * `test/integration/gc_containment_test.ts` @ v0.7.21 — a per-round reorder
 * of `x` specifically (not the tail), conditional undo/redo/"remove y" keyed
 * off `r % 3` / `r % 4` / `r % 5`, and an unsynced "edit body" (d1) +
 * "reorder" (d2) race resolved by undoing BOTH replicas before the round's
 * sync. Dropping the final "undo both" step would silently hide a real
 * divergence instead of surfacing it, so every step here is load-bearing.
 * With the container-value wire encoder fixed (RTCOLLABPLATFORM-772 — it now
 * sends a container value's members, not just its `type`), 3 consecutive
 * runs of this sequence converge on what the two clients show each other.
 *
 * Client-vs-client convergence alone is not sufficient: before the reverse
 * op's own top-level `removedAt` strip was added, that field carried the
 * removal ticket, so the server's own replayed root silently dropped the
 * restored container while both Android clients (which clear the field on
 * execute) agreed with each other. [serverRoot] below reads the server's own
 * root through the
 * admin API; both this test and
 * [test_keeps_a_removed_and_undone_todo_item_on_the_server_after_sync]
 * assert it against the clients, not just the clients against each other.
 */
@RunWith(AndroidJUnit4::class)
class GcContainmentTest {

    private val http = OkHttpClient()

    /**
     * The server's own replayed root for [documentKey], via admin
     * `GetDocuments` — not what a client shows another client. LogIn ->
     * ListProjects -> secretKey -> API-Key, the pattern in
     * `OfflinePersistenceTest.kt:96-116`.
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

    private fun idOf(item: JsonElement?): String? {
        return (item as? JsonObject)?.getAs<JsonPrimitive>("id")?.value as? String
    }

    private fun findById(items: JsonArray, id: String): JsonObject? {
        for (i in 0 until items.size) {
            val item = items[i] as? JsonObject ?: continue
            if (idOf(item) == id) return item
        }
        return null
    }

    private fun buildItem(
        obj: JsonObject,
        id: String,
        label: String,
    ): JsonObject = obj.apply {
        this["id"] = id
        setNewObject("box").apply {
            this["w"] = 10
            this["h"] = 20
        }
        setNewObject("body").apply {
            this["text"] = label
        }
    }

    /**
     * Snapshot of an item's mutable fields, captured before a reorder so the
     * reorder can reinsert the item at the head carrying the SAME content —
     * a pure move, not a content change.
     */
    private data class ItemFields(val boxW: Int, val boxH: Int, val bodyText: String)

    private fun captureFields(item: JsonObject): ItemFields {
        val box = item.getAs<JsonObject>("box")
        val body = item.getAs<JsonObject>("body")
        return ItemFields(
            boxW = box.getAs<JsonPrimitive>("w").value as Int,
            boxH = box.getAs<JsonPrimitive>("h").value as Int,
            bodyText = body.getAs<JsonPrimitive>("text").value as String,
        )
    }

    private fun JsonObject.applyFields(id: String, fields: ItemFields) {
        this["id"] = id
        setNewObject("box").apply {
            this["w"] = fields.boxW
            this["h"] = fields.boxH
        }
        setNewObject("body").apply {
            this["text"] = fields.bodyText
        }
    }

    /**
     * Reorders `x` to the head, preserving its current field content: a move
     * done as remove + re-add at head (`removeAt` + `putNewObject(head)`),
     * the shape `test/integration/gc_containment_test.ts` exercises — distinct
     * from an [dev.yorkie.document.operation.ArraySetOperation].
     */
    private fun reorderX(items: JsonArray) {
        val index = (0 until items.size).firstOrNull { i -> idOf(items[i]) == "x" }
        if (index != null) {
            val fields = captureFields(items[index] as JsonObject)
            items.removeAt(index)
            items.putNewObject(TimeTicket.InitialTimeTicket).applyFields("x", fields)
        }
    }

    @Test
    fun test_keeps_syncing_while_two_peers_reorder_edit_and_undo_one_element() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            // syncAsync returns Result.failure instead of throwing
            // (Client.kt:516-559), so a wedge — the #1340 defect this test
            // pins — could not fail a bare `.await()`. getOrThrow() converts
            // that failure into a real exception on every non-tolerant call
            // below.
            suspend fun sync() {
                repeat(4) {
                    c1.syncAsync(d1).await().getOrThrow()
                    c2.syncAsync(d2).await().getOrThrow()
                }
            }

            // Counts how many tolerated attempts actually completed, so
            // `tolerantSuccesses >= 1` below proves this helper exercises its
            // success path and is not dead code that only ever catches.
            // getOrThrow() surfaces a Result.failure (updateAsync,
            // undoAsync, redoAsync all return Result rather than throwing) as
            // a catchable exception; without it every attempt would count as
            // a "success" regardless of outcome.
            var tolerantSuccesses = 0
            suspend fun tolerant(block: suspend () -> Unit) {
                try {
                    block()
                    tolerantSuccesses++
                } catch (e: YorkieException) {
                    // Tolerated: undo/redo racing a peer's concurrent structural
                    // edit on the same array is allowed to no-op or fail here —
                    // the invariant this test pins is that sync itself never
                    // throws (the #1340 wedge), not undo/redo semantics.
                }
            }

            d1.updateAsync { root, _ ->
                val items = root.setNewArray("items")
                buildItem(items.putNewObject(), "x", "x-0")
                buildItem(items.putNewObject(), "y", "y-0")
            }.await().getOrThrow()
            sync()

            repeat(8) { r ->
                // 1. d1 reorders x to the head, keeping its current content.
                d1.updateAsync { root, _ -> reorderX(root.getAs<JsonArray>("items")) }
                    .await().getOrThrow()

                // 2. d2 edits x's body/box by id (immune to d1's reordering).
                d2.updateAsync { root, _ ->
                    val items = root.getAs<JsonArray>("items")
                    findById(items, "x")?.apply {
                        setNewObject("body").apply { this["text"] = "o".repeat(r + 1) }
                        setNewObject("box").apply {
                            this["w"] = 10
                            this["h"] = 20 + r
                        }
                    }
                }.await().getOrThrow()

                // 3. sync.
                sync()

                // 4. conditional d1 undo.
                if (r % 3 == 0) {
                    tolerant { d1.history.undoAsync().await().getOrThrow() }
                }

                // 5. conditional d2 "remove y" + undo.
                if (r % 4 == 0) {
                    tolerant {
                        d2.updateAsync { root, _ ->
                            val items = root.getAs<JsonArray>("items")
                            val index =
                                (0 until items.size).firstOrNull { i -> idOf(items[i]) == "y" }
                            if (index != null) items.removeAt(index)
                        }.await().getOrThrow()
                        d2.history.undoAsync().await().getOrThrow()
                    }
                }

                // 6. conditional redo on both replicas.
                if (r % 5 == 0) {
                    tolerant { d1.history.redoAsync().await().getOrThrow() }
                    tolerant { d2.history.redoAsync().await().getOrThrow() }
                }

                // 7. no sync in between: d1 edits x's body, d2 reorders x.
                d1.updateAsync { root, _ ->
                    val items = root.getAs<JsonArray>("items")
                    findById(items, "x")?.apply {
                        setNewObject("body").apply { this["text"] = "d1-$r" }
                    }
                }.await().getOrThrow()
                d2.updateAsync { root, _ -> reorderX(root.getAs<JsonArray>("items")) }
                    .await().getOrThrow()

                // 8. undo BOTH replicas (never silently drop this step — see the class KDoc).
                tolerant { d1.history.undoAsync().await().getOrThrow() }
                tolerant { d2.history.undoAsync().await().getOrThrow() }

                // 9. sync.
                sync()
            }

            // Final edit of x, then the iOS liveness pings, then one last sync.
            d1.updateAsync { root, _ ->
                val items = root.getAs<JsonArray>("items")
                findById(items, "x")?.apply {
                    setNewObject("box").apply {
                        this["w"] = 1
                        this["h"] = 1
                    }
                }
            }.await().getOrThrow()
            d1.updateAsync { root, _ -> root["pingD1"] = 1 }.await().getOrThrow()
            d2.updateAsync { root, _ -> root["pingD2"] = 2 }.await().getOrThrow()
            sync()

            assertJsonContentEquals(d1.toJson(), d2.toJson())

            val d1Json = gson.fromJson(d1.toJson(), GsonJsonObject::class.java)
            val d2Json = gson.fromJson(d2.toJson(), GsonJsonObject::class.java)
            assertEquals(1, d1Json.getAsJsonPrimitive("pingD1").asInt)
            assertEquals(2, d1Json.getAsJsonPrimitive("pingD2").asInt)
            assertEquals(1, d2Json.getAsJsonPrimitive("pingD1").asInt)
            assertEquals(2, d2Json.getAsJsonPrimitive("pingD2").asInt)

            assertTrue(tolerantSuccesses >= 1, "expected at least one tolerant attempt to succeed")

            // The server's OWN replayed root, not just what the two clients
            // show each other, must equal both clients.
            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(serverJson, d1.toJson())
            assertJsonContentEquals(serverJson, d2.toJson())
        }
    }

    /**
     * A todo-list item holding only primitives (`title`, `done`) — the most
     * common undo-a-delete shape — removed and undone on c1, then synced.
     * The server's own root must equal both clients, with both items
     * present: before the reverse op's own top-level `removedAt` strip was
     * added, the server root dropped the restored item even though both
     * clients agreed with each other.
     */
    @Test
    fun test_keeps_a_removed_and_undone_todo_item_on_the_server_after_sync() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            // See the class's first test for why getOrThrow() is required on
            // every call below.
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                val items = root.setNewArray("items")
                items.putNewObject().apply {
                    this["title"] = "a"
                    this["done"] = false
                }
                items.putNewObject().apply {
                    this["title"] = "b"
                    this["done"] = true
                }
            }.await().getOrThrow()
            sync()

            d1.updateAsync { root, _ -> root.getAs<JsonArray>("items").removeAt(0) }
                .await().getOrThrow()
            d1.history.undoAsync().await().getOrThrow()
            sync()

            val serverJson = serverRoot(documentKey)
            assertJsonContentEquals(d1.toJson(), d2.toJson())
            assertJsonContentEquals(serverJson, d1.toJson())

            val serverItems = gson.fromJson(serverJson, GsonJsonObject::class.java)
                .getAsJsonArray("items")
            assertEquals(2, serverItems.size())
        }
    }

    /**
     * A live-server oracle for the array-set-after-move path (the array-set
     * leak fix, the clone-anchor fix, the move dead-position registration and
     * the container-value wire encoder together) — `[0,1,2]`,
     * `moveAfterByIndex(0,2)`, `arr[1]=99`, synced, then undone and synced
     * again. The server's own root must equal both clients at each point,
     * not just the clients each other.
     */
    @Test
    fun test_keeps_an_array_set_after_a_move_equal_on_the_server_after_sync_and_undo() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, documentKey ->
            suspend fun sync() {
                c1.syncAsync(d1).await().getOrThrow()
                c2.syncAsync(d2).await().getOrThrow()
            }

            d1.updateAsync { root, _ ->
                root.setNewArray("arr").apply {
                    put(0)
                    put(1)
                    put(2)
                }
            }.await().getOrThrow()
            sync()

            d1.updateAsync { root, _ ->
                root.getAs<JsonArray>("arr").moveAfterByIndex(0, 2)
            }.await().getOrThrow()
            d1.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[1] = 99 }
                .await().getOrThrow()
            sync()

            var serverJson = serverRoot(documentKey)
            assertJsonContentEquals(d1.toJson(), d2.toJson())
            assertJsonContentEquals(serverJson, d1.toJson())

            d1.history.undoAsync().await().getOrThrow()
            sync()

            serverJson = serverRoot(documentKey)
            assertJsonContentEquals(d1.toJson(), d2.toJson())
            assertJsonContentEquals(serverJson, d1.toJson())
        }
    }
}
