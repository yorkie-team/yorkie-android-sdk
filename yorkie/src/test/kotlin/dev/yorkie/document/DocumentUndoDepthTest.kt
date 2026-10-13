package dev.yorkie.document

import dev.yorkie.document.history.MaxUndoRedoStackDepth
import dev.yorkie.document.json.JsonText
import dev.yorkie.util.YorkieException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [Document.Options.maxUndoDepth] (JS `document.ts`/`undo_depth_test.ts`'s
 * `describe('Document maxUndoDepth')`, `69a56c44`, #1424): the option wires through to
 * [dev.yorkie.document.history.History], is validated before [Document]'s own dispatcher is
 * created, and the Document-level undo/redo surface honors it end to end.
 *
 * JS also parametrizes its reject cases over `1.5`, `NaN` and `Infinity` — all three are
 * unconstructible here ([Document.Options.maxUndoDepth] is [Int], so "not an integer" is a
 * compile error, not a runtime case) and are therefore N/A, not ported.
 *
 * [dev.yorkie.document.history.HistoryTest] already covers the underlying stack-eviction
 * mechanics (default fallback, custom depth reported, oldest-first eviction on both stacks,
 * redo bound independent of the hard-coded default) directly against [History]; this file only
 * proves the [Document.Options]-to-[History] wiring and the public reject/validation contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentUndoDepthTest {

    private val createdDocs = mutableListOf<Document>()

    @After
    fun tearDown() {
        createdDocs.forEach { it.close() }
        createdDocs.clear()
    }

    /** Builds a [Document], seeds an empty [JsonText] outside history, then types [count] 'a's, one [Document.updateAsync] each. */
    private suspend fun typed(
        count: Int,
        options: Document.Options = Document.Options(),
    ): Document {
        val doc = Document("undo-depth", options).also { createdDocs += it }
        doc.updateAsync { root, _ -> root.setNewText("t") }.await()
        doc.clearHistory()
        repeat(
            count,
        ) { i -> doc.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(i, i, "a") }.await() }
        return doc
    }

    /** Undoes until [DocumentHistory.canUndo] is false and returns how many steps it took. */
    private suspend fun undoAll(doc: Document): Int {
        var steps = 0
        while (doc.history.canUndo()) {
            doc.history.undoAsync().await()
            steps++
        }
        return steps
    }

    @Test
    fun `keeps the default depth when the option is not given`() = runTest {
        val doc = typed(MaxUndoRedoStackDepth + 10)

        assertEquals(MaxUndoRedoStackDepth, undoAll(doc))
        assertEquals("a".repeat(10), doc.getRoot().getAs<JsonText>("t").toString())
    }

    @Test
    fun `keeps as many undo entries as the option allows`() = runTest {
        val doc = typed(120, Document.Options(maxUndoDepth = 100))

        assertEquals(100, undoAll(doc))
        assertEquals("a".repeat(20), doc.getRoot().getAs<JsonText>("t").toString())
    }

    @Test
    fun `drops the oldest entries first when the depth is small`() = runTest {
        val doc = typed(5, Document.Options(maxUndoDepth = 2))

        assertEquals(2, undoAll(doc))
        assertEquals("aaa", doc.getRoot().getAs<JsonText>("t").toString())
    }

    @Test
    fun `bounds the redo stack by the same depth, not the default one`() = runTest {
        // The depth is deliberately above MaxUndoRedoStackDepth: undoing all 60 changes
        // pushes 60 redo entries, so a redo stack still bounded by the hard-coded default
        // would have dropped the 10 oldest of them.
        val doc = typed(60, Document.Options(maxUndoDepth = 60))

        assertEquals(60, undoAll(doc))
        assertEquals("", doc.getRoot().getAs<JsonText>("t").toString())

        var redone = 0
        while (doc.history.canRedo()) {
            doc.history.redoAsync().await()
            redone++
        }

        assertEquals(60, redone)
        assertEquals("a".repeat(60), doc.getRoot().getAs<JsonText>("t").toString())
    }

    @Test
    fun `rejects a maxUndoDepth of 0`() {
        val exception = assertThrows(YorkieException::class.java) {
            Document.Options(maxUndoDepth = 0)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("maxUndoDepth must be a positive integer: 0"))
    }

    @Test
    fun `rejects a maxUndoDepth of -1`() {
        val exception = assertThrows(YorkieException::class.java) {
            Document.Options(maxUndoDepth = -1)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertTrue(exception.errorMessage.contains("maxUndoDepth must be a positive integer: -1"))
    }

    @Test
    fun `rejects a maxUndoDepth of the minimum Int value`() {
        val exception = assertThrows(YorkieException::class.java) {
            Document.Options(maxUndoDepth = Int.MIN_VALUE)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertTrue(
            exception.errorMessage.contains(
                "maxUndoDepth must be a positive integer: ${Int.MIN_VALUE}",
            ),
        )
    }

    @Test
    fun `copy onto an invalid maxUndoDepth is rejected the same way`() {
        // Validation lives in Options.init, so it also guards the synthesized copy(),
        // which re-invokes the primary constructor — not just the first-call path.
        val exception = assertThrows(YorkieException::class.java) {
            Document.Options().copy(maxUndoDepth = 0)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
    }

    // Binary compatibility: adding maxUndoDepth as a third constructor parameter must not
    // remove the two/one/zero-arg constructors a Java caller (or Kotlin code compiled
    // against an older version of this library) already links against -- @JvmOverloads on
    // the primary constructor is what keeps them. getConstructor throws
    // NoSuchMethodException if the overload is gone, so reaching the assertNotNull below
    // at all is the assertion.
    @Test
    fun `the two-arg constructor Java callers already link against still resolves`() {
        val ctor = Document.Options::class.java.getConstructor(
            Boolean::class.java,
            Boolean::class.java,
        )
        assertNotNull(ctor)
        val options = ctor.newInstance(true, true)
        assertEquals(true, options.disableGC)
        assertEquals(true, options.disablePresence)
        assertEquals(MaxUndoRedoStackDepth, options.maxUndoDepth)
    }
}
