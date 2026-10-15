package dev.yorkie.document

import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins [Document]'s mint-suspend surface (`isMintable`, `discardLocalChanges`,
 * `restoreLocalChanges`, `ensureMintable`'s two call sites) that `Client.resyncAsync`
 * (JS SDK `client.ts`/`document.ts`, `61ca06c9`/#1463, D3) relies on. JS `resync_test.ts`
 * cases 5 (`refuses an edit minted inside the resync window`) and 6 (`keeps detach and
 * the undo predicate usable inside the window`) port here as Document-only tests (no
 * Client/mock needed to pin the Document-side contract); the Client-level flow
 * (discard → detach → re-attach, auth-error, schema carry-over) is `core.ResyncTest`.
 */
class DocumentResyncWindowTest {
    private lateinit var target: Document

    @Before
    fun setUp() {
        target = Document("resync-window-doc")
    }

    @After
    fun tearDown() {
        target.close()
    }

    @Test
    fun `discardLocalChanges suspends minting even when the queue is empty`() = runTest {
        // Round-4 fix for CJ-MED-1: an empty discard used to leave minting open, so an
        // updater already suspended PAST its entry check (never queued anything yet)
        // was never refused when it later resumed mid-resync. See the two tests below
        // for the straddling-updater shapes this guards.
        val discarded = target.discardLocalChanges()

        assertTrue(discarded.isEmpty())
        assertFalse(
            target.isMintable(),
            "an empty queue can still have an updater suspended past entry -- minting " +
                "must be suspended regardless of what was queued",
        )
    }

    @Test
    fun `discardLocalChanges suspends minting without rewinding clientSeq`() = runTest {
        target.updateAsync { root, _ -> root["a"] = 1 }.await()
        target.updateAsync { root, _ -> root["b"] = 2 }.await()
        val clientSeqBeforeDiscard = target.changeID.clientSeq

        val discarded = target.discardLocalChanges()

        assertEquals(2, discarded.size)
        assertFalse(target.isMintable())
        assertEquals(
            clientSeqBeforeDiscard,
            target.changeID.clientSeq,
            "discardLocalChanges must not rewind clientSeq -- the server relationship is " +
                "abandoned entirely by resetForReanchor, not patched up here",
        )
        assertTrue(target.pendingChanges().isEmpty())
    }

    @Test
    fun `restoreLocalChanges reverses discardLocalChanges`() = runTest {
        target.updateAsync { root, _ -> root["a"] = 1 }.await()
        val discarded = target.discardLocalChanges()
        assertFalse(target.isMintable())

        target.restoreLocalChanges(discarded)

        assertTrue(target.isMintable())
        assertEquals(discarded, target.pendingChanges())
    }

    @Test
    fun `resetForReanchor also lifts a mint suspension`() = runTest {
        target.updateAsync { root, _ -> root["a"] = 1 }.await()
        target.discardLocalChanges()
        assertFalse(target.isMintable())

        target.resetForReanchor()

        assertTrue(target.isMintable())
    }

    // --- JS resync_test case 5: "refuses an edit minted inside the resync window" ----

    @Test
    fun `update refuses with ErrRefused while minting is suspended`() = runTest {
        target.updateAsync { root, _ -> root["a"] = 1 }.await()
        target.discardLocalChanges()

        val exception = assertFailsWith<YorkieException> {
            target.updateAsync { root, _ -> root["b"] = 2 }.await()
        }

        assertEquals(YorkieException.Code.ErrRefused, exception.code)
        // The updater must never even have run -- refused at entry, before ensureClone().
        assertNull(target.getRoot().getOrNull("b"))
    }

    @Test
    fun `undo refuses with ErrRefused while minting is suspended`() = runTest {
        target.updateAsync { root, _ -> root["a"] = 1 }.await()
        target.discardLocalChanges()

        val exception = assertFailsWith<YorkieException> {
            target.history.undoAsync().await()
        }

        assertEquals(YorkieException.Code.ErrRefused, exception.code)
    }

    @Test
    fun `redo refuses with ErrRefused while minting is suspended`() = runTest {
        target.updateAsync { root, _ -> root["a"] = 1 }.await()
        target.history.undoAsync().await()
        target.discardLocalChanges()

        val exception = assertFailsWith<YorkieException> {
            target.history.redoAsync().await()
        }

        assertEquals(YorkieException.Code.ErrRefused, exception.code)
    }

    // --- JS resync_test case 6: "keeps detach and the undo predicate usable inside
    // the window" (canUndo/canRedo false, not throwing) --------------------------------

    @Test
    fun `canUndo and canRedo are false while minting is suspended`() = runTest {
        target.updateAsync { root, _ -> root["a"] = 1 }.await()
        assertTrue(target.history.canUndo(), "control: undoable before any resync starts")

        target.discardLocalChanges()

        assertFalse(target.history.canUndo())
        assertFalse(target.history.canRedo())

        target.restoreLocalChanges(emptyList())
        assertTrue(
            target.history.canUndo(),
            "restoreLocalChanges must make the predicate usable again, matching JS case 6",
        )
    }

    // --- Android-only: overlapping-invocation guard (lesson generator/024) -----------

    @Test
    fun `an updater suspended past entry still refuses before minting its late change`() = runTest {
        // Seed a non-empty queue so discardLocalChanges() actually suspends minting.
        target.updateAsync { root, _ -> root["seed"] = 1 }.await()

        val enteredUpdater = CompletableDeferred<Unit>()
        val releaseUpdater = CompletableDeferred<Unit>()
        val parked = target.updateAsync { root, _ ->
            // Already past ensureMintable's ENTRY check (minting was still open when
            // this updater started) -- now suspend, exactly the window a concurrent
            // resyncAsync's discardLocalChanges() (S3) can land in.
            enteredUpdater.complete(Unit)
            releaseUpdater.await()
            root["late"] = 1
        }
        enteredUpdater.await()

        val discarded = target.discardLocalChanges()
        assertEquals(1, discarded.size, "only the seed change was queued when this ran")
        assertFalse(target.isMintable())

        releaseUpdater.complete(Unit)
        // A late refusal throws uncaught out of updateAsync's coroutine (same as the
        // entry-check throw) -- it fails the Deferred itself, so `.await()` rethrows
        // rather than returning a Result.failure; runCatching captures that here.
        val result = runCatching { parked.await() }

        assertTrue(
            result.isFailure,
            "the SECOND ensureMintable check, right before the append, must still catch " +
                "a pass that was already past the first one",
        )
        val exception = result.exceptionOrNull()
        assertTrue(
            exception is YorkieException && exception.code == YorkieException.Code.ErrRefused,
        )
        // The late change was never appended -- only the seed change is pending (and that
        // one was already discarded above, so the queue is empty here too). The mintable
        // check now runs BEFORE the root pass (H1 fix), so a refused change never mutates
        // the real root at all -- this is no longer an orphan mutation to decline to
        // assert on.
        assertNull(target.getRoot().getOrNull("late"))
        assertTrue(target.pendingChanges().isEmpty())
    }

    // --- Round-4 fix for CJ-MED-1: generation guard, not just the flag -----------------

    @Test
    fun `an updater suspended across an empty-queue discard is refused once the resync ends`() =
        runTest {
            // No seed change -- discardLocalChanges() below has nothing to discard, the
            // exact shape that used to leave mintingSuspended false (p1a2/p1a3).
            val enteredUpdater = CompletableDeferred<Unit>()
            val releaseUpdater = CompletableDeferred<Unit>()
            val late = target.updateAsync { root, _ ->
                enteredUpdater.complete(Unit)
                releaseUpdater.await()
                root["late"] = 1
            }
            enteredUpdater.await()

            val discarded = target.discardLocalChanges()
            assertTrue(discarded.isEmpty(), "nothing was queued before this updater suspended")
            assertFalse(target.isMintable())

            // Simulates the rest of a successful resync: resetForReanchor() lifts the
            // suspension (as the real resyncAsync does right before re-attaching).
            target.resetForReanchor()
            assertTrue(target.isMintable(), "control: the re-anchor lifted the suspension")

            releaseUpdater.complete(Unit)
            val result = runCatching { late.await() }

            assertTrue(
                result.isFailure,
                "an updater that straddled the WHOLE resync must still be refused, even " +
                    "though isMintable() is true again by the time it resumes",
            )
            val exception = result.exceptionOrNull()
            assertTrue(
                exception is YorkieException && exception.code == YorkieException.Code.ErrRefused,
            )
            // The mintable check now runs BEFORE the root pass (H1 fix): a real
            // resyncAsync's resetForReanchor() already ran before this updater resumed
            // here, so this asserts the stray write never lands on the freshly
            // re-anchored root either.
            assertNull(target.getRoot().getOrNull("late"))
            assertTrue(target.pendingChanges().isEmpty())
        }

    @Test
    fun `an updater resuming after resetForReanchor lifted the suspension is still refused`() =
        runTest {
            // Non-empty queue this time -- the shape (p1a4) where mintingSuspended being
            // cleared by resetForReanchor used to be enough to let a stale append through.
            target.updateAsync { root, _ -> root["seed"] = 1 }.await()

            val enteredUpdater = CompletableDeferred<Unit>()
            val releaseUpdater = CompletableDeferred<Unit>()
            val late = target.updateAsync { root, _ ->
                enteredUpdater.complete(Unit)
                releaseUpdater.await()
                root["late"] = 1
            }
            enteredUpdater.await()

            target.discardLocalChanges()
            assertFalse(target.isMintable())
            target.resetForReanchor()
            assertTrue(target.isMintable())

            releaseUpdater.complete(Unit)
            val result = runCatching { late.await() }

            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull()
            assertTrue(
                exception is YorkieException && exception.code == YorkieException.Code.ErrRefused,
                "the generation check must catch this even though mintingSuspended was " +
                    "already lifted by resetForReanchor before this updater resumed",
            )
            // Not asserting on getRoot() -- same orphan-mutation caveat as above.
            assertTrue(target.pendingChanges().isEmpty())
        }

    // --- H1 fix: the refusal catch burns the lamport, same as the root-pass catch ------

    @Test
    fun `a refused late mint burns the lamport so a resumed document never reuses its ticket`() =
        runTest {
            target.updateAsync { root, _ -> root["seed"] = 1 }.await()

            val enteredUpdater = CompletableDeferred<Unit>()
            val releaseUpdater = CompletableDeferred<Unit>()
            var lateCreatedAt: TimeTicket? = null
            val parked = target.updateAsync { root, _ ->
                enteredUpdater.complete(Unit)
                releaseUpdater.await()
                root["late"] = 1
                lateCreatedAt = (root["late"] as JsonPrimitive).target.createdAt
            }
            enteredUpdater.await()

            val discarded = target.discardLocalChanges()
            releaseUpdater.complete(Unit)
            val result = runCatching { parked.await() }
            assertTrue(result.isFailure)

            target.restoreLocalChanges(discarded)
            assertTrue(target.isMintable())

            // The refusal never touched the real root (H1 fix moves the check before the
            // root pass).
            assertNull(target.getRoot().getOrNull("late"))

            // Without burning the lamport on refusal, this next mint would start from the
            // exact same prevId the refused change did and reissue the same createdAt --
            // reproduced pre-fix as a collision between "late" and "next".
            target.updateAsync { root, _ -> root["next"] = 1 }.await()
            val nextCreatedAt = (target.getRoot().getOrNull("next") as JsonPrimitive).target
                .createdAt

            assertTrue(lateCreatedAt != null, "control: the refused mint issued a ticket")
            assertTrue(
                nextCreatedAt != lateCreatedAt,
                "a following updateAsync must mint a createdAt different from the refused " +
                    "one -- the lamport burn in the refusal catch is what prevents the reuse",
            )
        }
}
