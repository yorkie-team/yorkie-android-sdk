package dev.yorkie.document

import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.presence.DocPresence
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Covers `updateAsync(skipHistory = true, ...)`: history-exempt local
 * updates skip both `pushUndo` and `clearRedo` while mutation, sync
 * queueing, and failure semantics stay unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSkipHistoryTest {
    private lateinit var target: Document

    @Before
    fun setUp() {
        target = Document("")
    }

    @After
    fun tearDown() {
        target.close()
    }

    @Test
    fun `skipHistory update adds no undo entry and preserves undo depth`() = runTest {
        // E1 - ordinary
        target.updateAsync { root, _ ->
            root["k1"] = "1"
        }.await()
        val canUndoAfterE1 = target.history.canUndo()
        assertTrue(canUndoAfterE1)

        // E2 - skipHistory, must not add an undo entry
        target.updateAsync(skipHistory = true) { root, _ ->
            root["k2"] = "2"
        }.await()
        val canUndoAfterE2 = target.history.canUndo()
        assertEquals(canUndoAfterE1, canUndoAfterE2)

        // E3 - ordinary
        target.updateAsync { root, _ ->
            root["k3"] = "3"
        }.await()

        // Two undos exactly consume E1 and E3; E2 is never a target.
        target.history.undoAsync().await()
        target.history.undoAsync().await()
        assertFalse(target.history.canUndo())
    }

    @Test
    fun `undo twice reverses E3 then E1 while E2 mutation survives`() = runTest {
        // Distinct keys per write: each key's own reverse op targets that
        // key's specific creation ticket, so undoing E1 after two more
        // writes to OTHER keys stays observably meaningful (a reverse op for
        // a repeatedly-overwritten single key would otherwise target a
        // stale, already-superseded ticket).
        target.updateAsync { root, _ ->
            root["k1"] = "1"
        }.await()

        target.updateAsync(skipHistory = true) { root, _ ->
            root["k2"] = "2"
        }.await()

        target.updateAsync { root, _ ->
            root["k3"] = "3"
        }.await()
        assertEquals("""{"k1":"1","k2":"2","k3":"3"}""", target.toJson())

        // Undo #1 reverses E3 (removes k3); k1 and k2 (E2's mutation) survive.
        target.history.undoAsync().await()
        assertEquals("""{"k1":"1","k2":"2"}""", target.toJson())

        // Undo #2 reverses E1 (removes k1); k2 (E2's mutation) still survives.
        target.history.undoAsync().await()
        assertEquals("""{"k2":"2"}""", target.toJson())
        assertFalse(target.history.canUndo())
    }

    @Test
    fun `skipHistory update as first write leaves undo unavailable`() = runTest {
        target.updateAsync(skipHistory = true) { root, _ ->
            root["k"] = "1"
        }.await()
        assertFalse(target.history.canUndo())
    }

    @Test
    fun `skipHistory update preserves a non-empty redo stack`() = runTest {
        target.updateAsync { root, _ ->
            root["k"] = "1"
        }.await()

        target.history.undoAsync().await()
        assertTrue(target.history.canRedo())

        target.updateAsync(skipHistory = true) { root, _ ->
            root["k"] = "bookkeeping"
        }.await()
        assertTrue(target.history.canRedo())

        target.history.redoAsync().await()
        assertEquals("1", target.getRoot().getAs<JsonPrimitive>("k").value)
    }

    @Test
    fun `failing updater inside skipHistory update leaves stacks and content unchanged`() =
        runTest {
            target.updateAsync { root, _ ->
                root["k"] = "1"
            }.await()
            val canUndoBefore = target.history.canUndo()
            val canRedoBefore = target.history.canRedo()

            val result = target.updateAsync(skipHistory = true) { root, _ ->
                root["k"] = "2"
                error("boom")
            }.await()

            assertTrue(result.isFailure)
            assertEquals("1", target.getRoot().getAs<JsonPrimitive>("k").value)
            assertEquals(canUndoBefore, target.history.canUndo())
            assertEquals(canRedoBefore, target.history.canRedo())
        }

    @Test
    fun `style undo fidelity survives a skipHistory bookkeeping tree write`() = runTest {
        // Build <doc><p></p></doc>, then insert "A" (ordinary edit).
        target.updateAsync { root, _ ->
            root.setNewTree("tree", element("doc") { element("p") {} })
        }.await()

        target.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(1, 1, text { "A" })
        }.await()
        assertEquals("<doc><p>A</p></doc>", target.getRoot().getAs<JsonTree>("tree").toXml())

        // Ordinary style: bold the "p" element wrapping "A".
        target.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").style(0, 1, mapOf("bold" to "true"))
        }.await()
        assertEquals(
            """<doc><p bold="true">A</p></doc>""",
            target.getRoot().getAs<JsonTree>("tree").toXml(),
        )

        // skipHistory bookkeeping write: a second, unrelated style attribute.
        target.updateAsync(skipHistory = true) { root, _ ->
            root.getAs<JsonTree>("tree").style(0, 1, mapOf("dirty" to "true"))
        }.await()
        assertEquals(
            """<doc><p bold="true" dirty="true">A</p></doc>""",
            target.getRoot().getAs<JsonTree>("tree").toXml(),
        )

        // First undo reverses the ordinary style (un-bolds), leaving "A" and the
        // skipHistory bookkeeping attribute intact.
        target.history.undoAsync().await()
        assertEquals(
            """<doc><p dirty="true">A</p></doc>""",
            target.getRoot().getAs<JsonTree>("tree").toXml(),
        )

        // Second undo reverses the original insert, removing "A"; bookkeeping stays.
        target.history.undoAsync().await()
        assertEquals(
            """<doc><p dirty="true"></p></doc>""",
            target.getRoot().getAs<JsonTree>("tree").toXml(),
        )
    }

    @Test
    fun `skipHistory tree deletion produces no undo entry and its mutation persists`() = runTest {
        // given: <doc><p>AB</p></doc>
        target.updateAsync { root, _ ->
            root.setNewTree("tree", element("doc") { element("p") { text { "AB" } } })
        }.await()

        // Ordinary deletion: removes "A"; restores on undo (contrast case, AC8).
        target.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(1, 2)
        }.await()
        assertEquals("<doc><p>B</p></doc>", target.getRoot().getAs<JsonTree>("tree").toXml())
        target.history.undoAsync().await()
        assertEquals("<doc><p>AB</p></doc>", target.getRoot().getAs<JsonTree>("tree").toXml())
        target.history.redoAsync().await()
        assertEquals("<doc><p>B</p></doc>", target.getRoot().getAs<JsonTree>("tree").toXml())
        val canUndoAfterOrdinary = target.history.canUndo()
        assertTrue(canUndoAfterOrdinary)

        // skipHistory deletion: removes "B"; produces zero reverse ops (AC8) — no new
        // undo entry is pushed, and the deletion is permanent (not reachable via undo).
        target.updateAsync(skipHistory = true) { root, _ ->
            root.getAs<JsonTree>("tree").edit(1, 2)
        }.await()
        assertEquals("<doc><p></p></doc>", target.getRoot().getAs<JsonTree>("tree").toXml())
        assertEquals(canUndoAfterOrdinary, target.history.canUndo())

        // The one available undo entry is still the ordinary deletion above, not the
        // skipHistory one. Its reverse op re-inserts "A" at the reconciled point (the
        // skipHistory deletion shifted/collapsed that point when it removed "B"); "B"
        // itself never comes back since the skipHistory deletion produced no reverse op.
        target.history.undoAsync().await()
        assertEquals("<doc><p>A</p></doc>", target.getRoot().getAs<JsonTree>("tree").toXml())
    }

    @Test
    fun `skipHistory overwrite of an ordinary key undoes as a no-op and redoes by LWW`() = runTest {
        // E1 - ordinary: k = "1"
        target.updateAsync { root, _ ->
            root["k"] = "1"
        }.await()
        assertEquals("1", target.getRoot().getAs<JsonPrimitive>("k").value)

        // E2 - skipHistory overwrite: k = "bookkeeping"
        target.updateAsync(skipHistory = true) { root, _ ->
            root["k"] = "bookkeeping"
        }.await()
        assertEquals("bookkeeping", target.getRoot().getAs<JsonPrimitive>("k").value)

        // Undo direction: E1's reverse op is a Remove targeting the specific "1"
        // element, which the skipHistory write already tombstoned (superseded by
        // "bookkeeping"). Removing an already-superseded element is a no-op on the
        // active value — remote-like semantics mean createdAt is not retargeted —
        // so "bookkeeping" is what survives, not "1".
        target.history.undoAsync().await()
        assertEquals("bookkeeping", target.getRoot().getAs<JsonPrimitive>("k").value)

        // Redo direction: replaying E1 is an ordinary Set of "1" with a freshly
        // issued (newer) ticket, so it wins LWW over "bookkeeping" exactly as any
        // genuinely remote write with a newer ticket would — this is the same
        // remote-like contract, not a special-cased no-op.
        target.history.redoAsync().await()
        assertEquals("1", target.getRoot().getAs<JsonPrimitive>("k").value)
    }

    @Test
    fun `skipHistory reconciles a pending tree undo entry across index shift`() = runTest {
        // given: <root><p></p></root>
        target.updateAsync { root, _ ->
            root.setNewTree("tree", element("root") { element("p") {} })
        }.await()

        // E1 - ordinary insert "A" at index 1
        target.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(1, 1, text { "A" })
        }.await()
        assertEquals("<root><p>A</p></root>", target.getRoot().getAs<JsonTree>("tree").toXml())

        // E2 - skipHistory insert "X" before "A", shifting E1's undo range right by one.
        target.updateAsync(skipHistory = true) { root, _ ->
            root.getAs<JsonTree>("tree").edit(1, 1, text { "X" })
        }.await()
        assertEquals("<root><p>XA</p></root>", target.getRoot().getAs<JsonTree>("tree").toXml())

        // Undo E1: reconciliation must have shifted the undo range past "X" so only
        // "A" (the user's edit) is removed; "X" (bookkeeping) survives.
        target.history.undoAsync().await()
        assertEquals("<root><p>X</p></root>", target.getRoot().getAs<JsonTree>("tree").toXml())

        // Redo replays symmetrically.
        target.history.redoAsync().await()
        assertEquals("<root><p>XA</p></root>", target.getRoot().getAs<JsonTree>("tree").toXml())
    }

    @Test
    @Ignore(
        "known bug: undoing a setNewText+edit done in the same updateAsync block " +
            "also reverses the SetOperation, removing the \"text\" key instead of only " +
            "emptying it (RTCOLLABPLATFORM-752 follow-up)",
    )
    fun `skipHistory reconciles a pending text undo entry across index shift`() = runTest {
        // given: setNewText, ordinary insert "A"
        target.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "A")
        }.await()
        assertEquals("A", target.getRoot().getAs<JsonText>("text").toString())

        // skipHistory insert "X" before "A", shifting the pending undo range right.
        target.updateAsync(skipHistory = true) { root, _ ->
            root.getAs<JsonText>("text").edit(0, 0, "X")
        }.await()
        assertEquals("XA", target.getRoot().getAs<JsonText>("text").toString())

        // Undo the ordinary insert: only "A" is removed, "X" survives.
        target.history.undoAsync().await()
        assertEquals("X", target.getRoot().getAs<JsonText>("text").toString())

        // Redo replays symmetrically.
        target.history.redoAsync().await()
        assertEquals("XA", target.getRoot().getAs<JsonText>("text").toString())
    }

    @Test
    @Ignore(
        "known bug: undoing a setNewText+edit done in the same updateAsync block " +
            "also reverses the SetOperation, removing the \"text\" key instead of only " +
            "emptying it (RTCOLLABPLATFORM-752 follow-up)",
    )
    fun `a user edit in the attach clearHistory-to-skipHistory window keeps its undo entry`() =
        runTest {
            // Reproduces the fixed attach ordering sequentially (spec 009 AC1): the racy
            // gated-concurrent variant is unsafe — see the discovery note in the round build
            // report — so this pins the identical observable window guarantee deterministically.

            // 1. Attach-path clearHistory, moved up ahead of applyStatus(Attached). Wipes any
            // prior/offline entries.
            target.clearHistory()

            // 2. A user edit lands in the window between that clearHistory and the initialRoot
            // update completing.
            target.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "user")
            }.await()
            assertTrue(target.history.canUndo())

            // 3. The initialRoot update itself, run with skipHistory = true so it never enters
            // history.
            target.updateAsync(skipHistory = true) { root, _ ->
                if ("seed" !in root.keys) {
                    root["seed"] = 42
                }
            }.await()

            // No trailing clearHistory wipes the user edit's entry, and the skipHistory update
            // added no entry of its own.
            assertTrue(target.history.canUndo())

            // Undo reverts only the user edit; the initialRoot value stays intact.
            target.history.undoAsync().await()
            assertEquals("", target.getRoot().getAs<JsonText>("text").toString())
            assertEquals(42, target.getRoot().getAs<JsonPrimitive>("seed").value)
            assertTrue(target.history.canRedo())
        }

    @Test
    fun `positional two-parameter updateAsync call compiles and runs`() = runTest {
        val updater: suspend (JsonObject, DocPresence) -> Unit =
            { root, _ -> root["k"] = "positional" }

        target.updateAsync("msg", updater).await()

        assertEquals("positional", target.getRoot().getAs<JsonPrimitive>("k").value)
    }

    @Test
    fun `pre-skipHistory two-parameter updateAsync binary surface is preserved`() {
        // The original pre-skipHistory updateAsync(message, updater) overload must keep
        // its exact `$default` synthetic (receiver + message + updater + mask + marker =
        // 5 params) so bytecode compiled against the old signature still links. The
        // 3-param overload's own `$default` synthetic has 6 params (skipHistory added),
        // so filtering on `== 5` isolates only the preserved original.
        val defaults = Document::class.java.declaredMethods.filter {
            it.name == "updateAsync\$default" && it.parameterCount == 5
        }

        assertEquals(1, defaults.size)
    }
}
