package dev.yorkie.document

import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.ChangeApplyError
import dev.yorkie.util.YorkieException
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Ports `clone_reset_test.ts` (JS SDK `ec7558e9`, #1394) as JVM unit tests
 * (spec 033 AC4).
 *
 * `Change.execute` applies operations one at a time and does not roll back,
 * so a change or undo/redo that throws partway through leaves [Document.clone]
 * holding a prefix of it the root never took. [Document.applyChanges] and
 * [Document.executeUndoRedo] (both private) now drop the clone in a catch
 * that rethrows, mirroring [Document.updateAsync]'s existing behavior on an
 * updater throw.
 *
 * **Disclosure (spec 034 Commit 2, JS `4251ab7e`, #1403):** "drops the clone when a
 * remote change fails partway" originally asserted the raw [IllegalStateException]
 * escaped [Document.applyChangePack] unnamed; it now asserts a [ChangeApplyError]
 * wrapping that same exception as [Throwable.cause] -- the clone-reset assertion this
 * test exists for is unchanged, only the exception type naming the failure is. The
 * undo/redo case below is unaffected: `OpSource.UndoRedo` is not named (D2), so its
 * raw [IllegalStateException] still escapes unchanged.
 *
 * `clone` is `internal`, so it is read directly here rather
 * than through a cast-based accessor the way JS's `internals()` helper does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentCloneResetTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `drops the clone when a remote change fails partway`() = runTest {
        val source = Document("d")
        source.updateAsync { root, _ -> root["k"] = 1 }.await()
        val change = source.createChangePack().changes.single()

        // `SetOperation.execute` runs on the clone first and the root
        // second (see Document.applyChangesInternal), so throwing on the
        // 2nd call fails it after the clone already took it.
        val spiedOp = spyk(change.operations.single() as SetOperation)
        var calls = 0
        every { spiedOp.execute(any(), any(), any()) } answers {
            calls++
            if (calls == 2) error("boom") else callOriginal()
        }
        val throwingChange = change.copy(operations = listOf(spiedOp))

        val target = Document("d")
        val thrown = assertFailsWith<ChangeApplyError> {
            target.applyChangePack(
                ChangePack(
                    target.getKey(),
                    CheckPoint(0, 0u),
                    listOf(throwingChange),
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }
        assertEquals("boom", thrown.cause?.message)

        assertNull(target.clone)
    }

    /**
     * JS `change_apply_error_test.ts` "leaves the error of a local update untouched"
     * (`4251ab7e`, #1403): `OpSource.Local` is never named (D2) -- the caller of
     * `updateAsync` asked for this failure and matches on the [YorkieException.code]
     * the operation threw, so the raw error (code and all) must reach it unchanged --
     * but the clone must still drop (same as the remote case above): the root took only
     * a prefix of the change while the clone took all of it.
     */
    @Test
    fun `leaves the error of a local update untouched, but still drops the clone`() = runTest {
        mockkConstructor(SetOperation::class)
        every {
            anyConstructed<SetOperation>().execute(any(), any(), any())
        } throws YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")

        val doc = Document("d")
        val thrown = assertFailsWith<YorkieException> {
            doc.updateAsync { root, _ -> root["k"] = 1 }.await()
        }

        assertFalse(thrown is ChangeApplyError)
        assertEquals(YorkieException.Code.ErrInvalidArgument, thrown.code)
        assertEquals("nope", thrown.errorMessage)
        assertNull(doc.clone)
    }

    @Test
    fun `drops the clone when an undo fails partway`() = runTest {
        // The reverse SetOperation that undo replays is built internally by
        // the second update below (SetOperation.execute's own
        // previousValue branch) and never exposed to this test, so the
        // only way to make its execute() throw is to intercept the
        // constructor the way JS's prototype-wide spy does -- mockkConstructor
        // only instruments instances built WHILE it is active, so it must be
        // installed before that reverse op is constructed, not just before
        // the undo that replays it. Every SetOperation built from here on
        // (the two setup updates' forward ops too) funnels through the same
        // counter, with `callOriginal()` as the default, until `armed` is
        // set right before the undo itself. `executeUndoRedoInternal` runs
        // `change.execute` twice -- clone validation, then the real root
        // application -- so arming for call 2 fails it after the clone
        // took it, same as the remote-change case above.
        mockkConstructor(SetOperation::class)
        var armed = false
        var calls = 0
        every { anyConstructed<SetOperation>().execute(any(), any(), any()) } answers {
            if (armed) calls++
            if (armed && calls == 2) error("boom") else callOriginal()
        }

        val doc = Document("d")
        doc.updateAsync { root, _ -> root["k"] = 1 }.await()
        doc.updateAsync { root, _ -> root["k"] = 2 }.await()
        assertNotNull(doc.clone)

        armed = true
        assertFailsWith<IllegalStateException> {
            doc.history.undoAsync().await().getOrThrow()
        }

        assertNull(doc.clone)
    }

    @Test
    fun `keeps the clone when undo is refused during an update`() = runTest {
        val doc = Document("d")
        doc.updateAsync { root, _ -> root["k"] = 1 }.await()
        assertNotNull(doc.clone)

        // `isUpdating` is read by executeUndoRedo's refusal check but is
        // never set true anywhere in Document.kt (confirmed: no assignment
        // exists in the file, pre-dating this commit and unrelated to
        // #1394) -- so nesting an undoAsync() call inside an in-flight
        // updateAsync() call, the way JS's test does, does not reach the
        // refusal branch on Android today. Reflection (the established
        // idiom in this test suite, see GcContainmentTest.rootOf) drives
        // the precondition directly, so this test exercises what this
        // commit actually changed: that the refusal's early return -- kept
        // OUTSIDE the new try/catch -- still returns failure without
        // touching the clone.
        setIsUpdating(doc, true)
        try {
            val result = doc.history.undoAsync().await()
            assertTrue(result.isFailure)
            assertEquals(
                "Undo is not allowed during an update",
                result.exceptionOrNull()?.message,
            )
        } finally {
            setIsUpdating(doc, false)
        }

        assertNotNull(doc.clone)
    }

    private fun setIsUpdating(document: Document, value: Boolean) {
        val field = Document::class.java.getDeclaredField("isUpdating")
        field.isAccessible = true
        field.setBoolean(document, value)
    }
}
