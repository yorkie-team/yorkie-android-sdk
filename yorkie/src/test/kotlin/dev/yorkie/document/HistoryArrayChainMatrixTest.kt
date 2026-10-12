package dev.yorkie.document

import dev.yorkie.document.json.JsonArray
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Port of JS `history_array_test.ts` "should return to each state correctly:
 * op1-op2-op3" @ v0.7.21 (RTCOLLABPLATFORM-772, update half of
 * `28f4ad26`/#1059). The JS test reuses a single `applyOp1` for every
 * position (there is no distinct `applyOp2`/`applyOp3` in that describe
 * block), so [applyOp1] here mirrors it exactly: add inserts after index 0,
 * move relocates index 0 after index 2, remove drops index 0, set overwrites
 * index 1. Every op1 x op2 x op3 combination over add/remove/move/set is
 * applied to a fresh 5-element array, then undone back through each
 * intermediate JSON snapshot.
 *
 * Skips the same case JS skips: `op1 == move && op3 == set` (JS
 * TODO(hackerwins) — a set after a move anchors at the element's dead
 * original slot for concurrent convergence, so undo does not restore the
 * moved position; fixing it needs a proto-level change, out of scope here).
 * `op1 == move && op2 == move && op3 == set` is already covered by the first
 * clause and adds no cases of its own.
 *
 * 12 of the 60 non-skipped chains fail before the update-time history
 * reconcile in `Document.kt`; all 60 pass after it.
 */
class HistoryArrayChainMatrixTest {

    private val ops = listOf("add", "remove", "move", "set")

    private suspend fun applyOp1(doc: Document, op: String) {
        doc.updateAsync(op) { root, _ ->
            val list = root.getAs<JsonArray>("list")
            when (op) {
                "add" -> {
                    val prev = list.getOrNull(0) ?: return@updateAsync
                    list.put("insV", prev.id)
                }

                "move" -> {
                    if (list.size < 3) return@updateAsync
                    val from = list[0]
                    val to = list[2]
                    list.moveAfter(to.id, from.id)
                }

                "remove" -> if (list.size > 0) list.removeAt(0)
                "set" -> if (list.size > 1) list[1] = "s"
            }
        }.await()
    }

    @Test
    fun `undo returns to each intermediate state for every op1-op2-op3 chain`() = runTest {
        var total = 0
        val failures = mutableListOf<String>()
        for (op1 in ops) {
            for (op2 in ops) {
                for (op3 in ops) {
                    if (op1 == "move" && op3 == "set") continue // JS-skipped: TODO(hackerwins)
                    total++
                    val caseName = "$op1-$op2-$op3"
                    val doc = Document("test-doc")
                    doc.updateAsync("init") { root, _ ->
                        root.setNewArray("list").apply {
                            listOf("a", "b", "c", "d", "e").forEach { put(it) }
                        }
                    }.await()

                    val states = mutableListOf(doc.toJson())
                    applyOp1(doc, op1)
                    states += doc.toJson()
                    applyOp1(doc, op2)
                    states += doc.toJson()
                    applyOp1(doc, op3)
                    states += doc.toJson()

                    for (i in 3 downTo 1) {
                        val result = doc.history.undoAsync().await()
                        val back = doc.toJson()
                        if (result.isFailure || back != states[i - 1]) {
                            val errName = result.exceptionOrNull()?.javaClass?.simpleName
                            failures += "$caseName: undo to S${i - 1} got=$back " +
                                "want=${states[i - 1]} err=$errName"
                            break
                        }
                    }
                }
            }
        }
        assertEquals(60, total, "expected 60 non-skipped chains (64 total minus 4 JS-skipped)")
        assertTrue(
            failures.isEmpty(),
            "${failures.size}/$total chains failed:\n${failures.joinToString("\n")}",
        )
    }
}
