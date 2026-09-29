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
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import com.google.gson.JsonObject as GsonJsonObject

/**
 * Ports `gc_containment_test.ts`'s integration case (yorkie-js-sdk v0.7.21,
 * `1ad69a12`, PR #1341, closes #1340) as an instrumented two-client test
 * against a real Yorkie server (AC7, compose pin `yorkieteam/yorkie:0.7.21`),
 * with iOS liveness pings (`5054ed7e2a`) added at the end. Two peers reorder,
 * edit and undo the same array-of-objects over many rounds, on Manual sync;
 * any exception out of a sync call is the #1340 wedge this port fixes.
 */
@RunWith(AndroidJUnit4::class)
class GcContainmentTest {

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

    @Test
    fun test_keeps_syncing_while_two_peers_reorder_edit_and_undo_one_element() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            suspend fun sync() {
                repeat(4) {
                    c1.syncAsync(d1).await()
                    c2.syncAsync(d2).await()
                }
            }

            suspend fun tolerant(block: suspend () -> Unit) {
                try {
                    block()
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
            }.await()
            sync()

            // 8 rounds: d1 reorders (removeAt the tail, reinsert at head),
            // d2 edits x's body/box by id (immune to d1's reordering).
            repeat(8) { round ->
                d1.updateAsync { root, _ ->
                    val items = root.getAs<JsonArray>("items")
                    val removed = items.removeAt(items.size - 1) as? JsonObject
                    val id = idOf(removed) ?: "x"
                    buildItem(items.putNewObject(TimeTicket.InitialTimeTicket), id, "$id-$round")
                }.await()

                d2.updateAsync { root, _ ->
                    val items = root.getAs<JsonArray>("items")
                    findById(items, "x")?.apply {
                        setNewObject("body").apply { this["text"] = "edited-$round" }
                        setNewObject("box").apply {
                            this["w"] = round
                            this["h"] = round
                        }
                    }
                }.await()

                sync()
            }

            tolerant { d1.history.undoAsync().await() }
            sync()
            tolerant { d1.history.redoAsync().await() }
            sync()
            tolerant {
                d1.updateAsync { root, _ ->
                    val items = root.getAs<JsonArray>("items")
                    val index = (0 until items.size).firstOrNull { i -> idOf(items[i]) == "y" }
                    if (index != null) items.removeAt(index)
                }.await()
                d1.history.undoAsync().await()
            }
            sync()

            // "edit body" via the array-set path this port fixes (ArraySetOperation),
            // rather than a nested-key SetOperation on an already-live element.
            d1.updateAsync { root, _ ->
                val items = root.getAs<JsonArray>("items")
                val index = (0 until items.size).firstOrNull { i -> idOf(items[i]) == "x" } ?: 0
                buildItem(items.setNewObject(index), "x", "edited-body")
            }.await()
            sync()
            d2.updateAsync { root, _ ->
                val items = root.getAs<JsonArray>("items")
                val removed = items.removeAt(items.size - 1) as? JsonObject
                val id = idOf(removed) ?: "y"
                buildItem(items.putNewObject(TimeTicket.InitialTimeTicket), id, "$id-reordered")
            }.await()
            sync()

            tolerant { d2.history.undoAsync().await() }

            sync()

            d1.updateAsync { root, _ -> root["pingD1"] = 1 }.await()
            d2.updateAsync { root, _ -> root["pingD2"] = 2 }.await()
            sync()

            assertJsonContentEquals(d1.toJson(), d2.toJson())

            val d1Json = gson.fromJson(d1.toJson(), GsonJsonObject::class.java)
            val d2Json = gson.fromJson(d2.toJson(), GsonJsonObject::class.java)
            assertEquals(1, d1Json.getAsJsonPrimitive("pingD1").asInt)
            assertEquals(2, d1Json.getAsJsonPrimitive("pingD2").asInt)
            assertEquals(1, d2Json.getAsJsonPrimitive("pingD1").asInt)
            assertEquals(2, d2Json.getAsJsonPrimitive("pingD2").asInt)
        }
    }
}
