package dev.yorkie.document

import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue as jAssertTrue

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
        jAssertTrue(canUndoAfterE1)

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
        jAssertTrue(target.history.canRedo())

        target.updateAsync(skipHistory = true) { root, _ ->
            root["k"] = "bookkeeping"
        }.await()
        jAssertTrue(target.history.canRedo())

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
    fun `pre-skipHistory two-parameter updateAsync stays on the JVM binary surface`() {
        // given: callers compiled before skipHistory existed link against
        // updateAsync(message, updater) — kept via a hidden bridge overload.
        val bridges = Document::class.java.methods.filter {
            it.name == "updateAsync" && it.parameterCount == 2
        }

        // then
        assertEquals(1, bridges.size)
    }
}
