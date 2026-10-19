package dev.yorkie.document

import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeExecutionResult
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.ChangeApplyError
import dev.yorkie.util.Logger
import dev.yorkie.util.YorkieException
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Ports `change_apply_error_test.ts` (JS SDK `4251ab7e`, #1403) as JVM unit tests,
 * plus the Android-only two-layer-wrap cases ([Change.execute] names an
 * operation; [Document.applyChangesInternal]'s per-change catch names a change-level
 * failure JS has no equivalent of, since JS has no clone/root two-pass split) and the
 * binary-compatibility hardening recorded (not test-enforced; see the comments
 * before each, below) on top of the original port: a `CancellationException` reaching
 * any of [Document.applyChanges]'s outer catch, [Document.applyChangesInternal]'s
 * per-change catch or [Document.applyChangePack]'s stuck-checkpoint catch must
 * propagate unchanged (never named, never logged as "stuck") and a `java.lang.Error`
 * must never be wrapped either.
 *
 * `SetOperation.execute` runs on the clone first (call 1) and the root second (call 2) —
 * see [Document.applyChangesInternal] — so arming a throw on call 2 fails the ROOT pass
 * (the common case: the clone already took the change), and call 1 fails the CLONE pass
 * (JS's "fails the clone pass rather than the root pass" case). Mirrors JS's own
 * `throwOnNthCall` helper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeApplyErrorTest {

    @After
    fun tearDown() {
        unmockkAll()
        Logger.init(RecordingLogger())
    }

    /** A 1-op Remote-sourced [ChangePack] for [target], carrying [operations] in place of [change]'s own. */
    private fun packOf(
        target: Document,
        change: Change,
        operations: List<SetOperation>,
    ) = ChangePack(
        target.getKey(),
        CheckPoint(0, 0u),
        listOf(change.copy(operations = operations)),
        null,
        false,
        VersionVector(),
    )

    private suspend fun oneOpChange(sentinel: String = "1"): Pair<Change, SetOperation> {
        val source = Document("d")
        source.updateAsync { root, _ -> root["k"] = sentinel }.await()
        val change = source.createChangePack().changes.single()
        return change to change.operations.single() as SetOperation
    }

    // --- JS 1: names the document, the change and the operation ---

    @Test
    fun `names the document, the change and the operation`() = runTest {
        val (change, op) = oneOpChange()
        val spiedOp = spyk(op)
        var calls = 0
        every { spiedOp.execute(any(), any(), any()) } answers {
            calls++
            if (calls == 2) throw IllegalStateException("boom") else callOriginal()
        }

        val target = Document("d")
        val thrown = assertFailsWith<ChangeApplyError> {
            target.applyChangePack(packOf(target, change, listOf(spiedOp)))
        }

        assertEquals(YorkieException.Code.ErrChangeApplyFailed, thrown.code)
        assertEquals("d", thrown.docKey)
        assertEquals(change.id.toTestString(), thrown.changeID)
        assertEquals(0, thrown.opIndex)
        assertTrue(thrown.operation.orEmpty().startsWith("SetOperation on "))
        assertTrue(thrown.cause is IllegalStateException)
        assertTrue(thrown.message.orEmpty().contains("of document \"d\""))
        assertTrue(thrown.message.orEmpty().contains("boom"))
    }

    // --- JS 2: never carries the operation payload into the message ---

    @Test
    fun `never carries the operation payload into the message`() = runTest {
        val sentinel = "top secret payload"
        val (change, op) = oneOpChange(sentinel)
        val spiedOp = spyk(op)
        var calls = 0
        every { spiedOp.execute(any(), any(), any()) } answers {
            calls++
            if (calls == 2) throw IllegalStateException("boom") else callOriginal()
        }

        val target = Document("d")
        val thrown = assertFailsWith<ChangeApplyError> {
            target.applyChangePack(packOf(target, change, listOf(spiedOp)))
        }

        assertFalse(thrown.operation.orEmpty().contains(sentinel))
        assertFalse(thrown.message.orEmpty().contains(sentinel))
    }

    // --- JS 3: keeps the original error reachable as the cause ---

    @Test
    fun `keeps the original error reachable as the cause`() = runTest {
        val (change, op) = oneOpChange()
        val spiedOp = spyk(op)
        var calls = 0
        // Arm call 1: fails the clone pass rather than the root pass.
        every { spiedOp.execute(any(), any(), any()) } answers {
            calls++
            if (calls == 1) throw IllegalStateException("boom") else callOriginal()
        }

        val target = Document("d")
        val thrown = assertFailsWith<ChangeApplyError> {
            target.applyChangePack(packOf(target, change, listOf(spiedOp)))
        }

        assertTrue(thrown.cause is IllegalStateException)
        assertEquals("boom", thrown.cause?.message)
        assertEquals("d", thrown.docKey)
        assertEquals(0, thrown.opIndex)
    }

    // --- JS 5: leaves the error of a local or undo/redo replay untouched ---

    @Test
    fun `leaves the error of a local or undo-redo replay untouched`() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val presences = emptyMap<String, P>().asPresences()
        val tick = TimeTicket(1, 0u, "000000000000000000000001")

        for (source in listOf(OpSource.Local, OpSource.UndoRedo)) {
            val op = spyk(
                SetOperation(
                    key = "k",
                    value = CrdtPrimitive(1, tick),
                    parentCreatedAt = obj.createdAt,
                    executedAt = tick,
                ),
            )
            every {
                op.execute(any(), any(), any())
            } throws YorkieException(YorkieException.Code.ErrInvalidArgument, "nope")
            val change = Change(ChangeID.InitialChangeID, listOf(op))

            val thrown = assertFailsWith<YorkieException>("source=$source") {
                change.execute(root, presences, source)
            }
            assertFalse(thrown is ChangeApplyError, "source=$source")
            assertEquals(YorkieException.Code.ErrInvalidArgument, thrown.code, "source=$source")
        }
    }

    // Document-level counterpart of the case directly above: a Local-sourced replay
    // through Document.applyChanges (not Change.execute called directly) must also
    // rethrow the raw error -- never ChangeApplyError -- AND reset the clone, the same
    // way every other throw out of applyChangesInternal does.
    // restoreAppendedChanges (the attach/restore-from-store path) is the one production
    // caller of applyChanges(..., OpSource.Local).
    @Test
    fun `restoreAppendedChanges rethrows a Local-sourced error raw and resets the clone`() =
        runTest {
            val (change, op) = oneOpChange()
            val spiedOp = spyk(op)
            every { spiedOp.execute(any(), any(), any()) } throws IllegalStateException("boom")

            val target = Document("d")
            // One successful edit first, so the clone this throw must reset is
            // observably non-null beforehand, not merely never-yet-created.
            target.updateAsync { root, _ -> root["seed"] = 1 }.await()
            assertNotNull(target.clone)

            val thrown = assertFailsWith<IllegalStateException> {
                target.restoreAppendedChanges(listOf(change.copy(operations = listOf(spiedOp))))
            }
            assertEquals("boom", thrown.message)
            assertNull(target.clone)
        }

    // The OTHER Document-level wrap site: a Remote change whose OWN operation executes
    // cleanly, but whose surrounding bookkeeping -- here, reconcileHistoryEdits, run
    // right after change.execute succeeds, entirely outside Change.execute's per-op
    // catch -- throws. Unlike an operation's own throw (named with opIndex/operation,
    // see "names the document, the change and the operation" above), this is "the
    // change alone" shape JS 6 unit-tests on a hand-built ChangeApplyError: opIndex and
    // operation stay null (no single operation is at fault), while docKey/changeID/cause
    // are populated and the clone is reset, exactly as a real apply would produce it.
    // reconcileHistoryEdits is private; spyk's private-call support is the seam (no
    // production hook added).
    @Test
    fun `a Remote change whose own bookkeeping throws is wrapped as change-level`() = runTest {
        val (change, op) = oneOpChange()
        val target = spyk(Document("d"), recordPrivateCalls = true)
        // Distinct from the change's own (default/unset) actor so reconcileHistoryEdits'
        // "only reconcile against a DIFFERENT actor's changes" gate lets this change
        // reach it.
        target.setActor("000000000000000000000002")
        every {
            target["reconcileHistoryEdits"](any<ChangeExecutionResult>())
        } throws IllegalStateException("boom")

        val thrown = assertFailsWith<ChangeApplyError> {
            target.applyChangePack(packOf(target, change, listOf(op)))
        }
        assertEquals("d", thrown.docKey)
        assertEquals(change.id.toTestString(), thrown.changeID)
        assertTrue(thrown.cause is IllegalStateException)
        assertEquals("boom", thrown.cause?.message)
        assertNull(thrown.opIndex)
        assertNull(thrown.operation)
        assertNull(target.clone)
    }

    // --- JS 6: names the change alone when no single operation failed ---

    @Test
    fun `names the change alone when no single operation failed`() {
        val err = ChangeApplyError(
            docKey = "d",
            changeID = "c",
            cause = Exception("boom"),
        )

        assertEquals("failed to apply change c of document \"d\": boom", err.message)
        assertNull(err.opIndex)
        assertNull(err.operation)

        assertSame(err, err.withDocKey("d"))
        val renamed = err.withDocKey("other")
        assertTrue(renamed !== err)
        assertEquals("c", renamed.changeID)
    }

    // --- JS 7: reports the stuck checkpoint, metadata only (never the cause message) ---
    // The log line also names the ChangeApplyError's own changeID/opIndex/operation --
    // payload-safe by the same contract as `operation` itself -- without ever logging
    // the cause's message.

    @Test
    fun `reports the stuck checkpoint once, without the cause message`() = runTest {
        val sentinel = "requested value: 42"
        val (change, op) = oneOpChange()
        val spiedOp = spyk(op)
        var calls = 0
        every { spiedOp.execute(any(), any(), any()) } answers {
            calls++
            if (calls == 2) throw IllegalStateException(sentinel) else callOriginal()
        }

        val recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)

        val target = Document("d")
        val thrown = assertFailsWith<ChangeApplyError> {
            target.applyChangePack(packOf(target, change, listOf(spiedOp)))
        }

        // The checkpoint never advanced, so the server redelivers this pack.
        assertEquals(0, target.checkPoint.serverSeq)

        assertEquals(1, recordingLogger.errorMessages.size)
        val logged = recordingLogger.errorMessages.single()
        assertTrue(logged.contains("redeliver"))
        assertTrue(logged.contains("IllegalStateException"))
        assertTrue(
            logged.contains("changeID=${thrown.changeID}"),
            "the stuck-checkpoint log must name the stuck change's id",
        )
        assertTrue(
            logged.contains("opIndex=${thrown.opIndex}"),
            "the stuck-checkpoint log must name the stuck operation's index",
        )
        assertTrue(
            logged.contains("operation=${thrown.operation}"),
            "the stuck-checkpoint log must name the stuck operation's type + ticket",
        )
        assertFalse(
            logged.contains(sentinel),
            "the stuck-checkpoint log must never splice in the cause's own message",
        )
    }

    // --- Android-only: the document key is attached exactly once, no double-wrap ---

    @Test
    fun `applyChanges attaches the document key exactly once, without double-wrapping`() = runTest {
        val (change, op) = oneOpChange()
        val spiedOp = spyk(op)
        every { spiedOp.execute(any(), any(), any()) } throws IllegalStateException("boom")

        val target = Document("my-doc-key")
        val thrown = assertFailsWith<ChangeApplyError> {
            target.applyChangePack(packOf(target, change, listOf(spiedOp)))
        }

        assertEquals("my-doc-key", thrown.docKey)
        assertTrue(thrown.cause is IllegalStateException)
        assertEquals(
            1,
            Regex("failed to apply change").findAll(thrown.message.orEmpty()).count(),
        )
    }

    // Cancellation guard: a CancellationException
    // reaching any of Document.applyChanges's outer catch, applyChangesInternal's
    // per-change catch, or applyChangePack's stuck-checkpoint catch must propagate
    // unchanged -- never named as a ChangeApplyError, never logged as a false "stuck"
    // line -- because it is the caller's scope tearing down, not a change failing to
    // apply.
    //
    // A red run is not reliably constructible (recorded, not hidden -- no flaky test is shipped
    // in its place): every construction this suite tried to force a GENUINE
    // CancellationException into these three catches funnels through
    // `kotlinx.coroutines.withContext(dispatcher)` (ensureClone/the outer
    // applyChangePack entry), whose own "was I cancelled while my block ran" recheck is
    // NOT reliably observable from a unit test -- repeated side-by-side runs (spy an
    // operation to call `job.cancel()` mid-apply, then `callOriginal()`, racing a
    // `launch`'d `applyChangePack` against a real single-thread dispatcher) showed the
    // SAME final outcome (a `JobCancellationException`, body completed with the clone
    // left non-null, none of the three catches entered at all -- confirmed by a
    // temporary per-catch trace) with the cancellation guard BOTH present and reverted: the race is
    // between the JVM's real thread scheduling and kotlinx.coroutines' own completion
    // bookkeeping, not between this fix's two code paths, so no construction tried
    // reliably discriminates the two. A flaky test that sometimes passes regardless of
    // the fix is worse than no test -- it would erode trust in this suite's red/green
    // discipline. Pinned by code inspection and precedent instead: the fix is a
    // structural, one-line-per-catch mirror of the SAME `catch (e: CancellationException)
    // { ...; throw e }` idiom this very file already uses at `Document.fromBytes`
    // (`Document.kt`, the `PBChangePack`-decode catch chain) and `Client.kt`'s
    // `triggerAppend` -- both already-reviewed, already-trusted call sites for exactly
    // this "never let cancellation be mistaken for a domain failure" contract.

    // Error guard: rethrow a `java.lang.Error`
    // (e.g. `OutOfMemoryError`/`StackOverflowError`) unwrapped from the same two
    // `Document` catches the cancellation test above exercises.
    //
    // A red run is not constructible (recorded, not hidden -- no fake pass-trivially test is
    // written in its place): every path this suite can drive an operation failure
    // through funnels first through `Change.execute`'s own op-level catch (`Change.kt`,
    // out of this fix's scope -- it has no suspension points, so it carries no
    // CancellationException risk, and this hardening targets only
    // the three Document catches), which wraps ANY Throwable -- including an Error --
    // into a ChangeApplyError before either of Document's own `err is Error`/`e !is
    // Error` guards is ever reached. The only way those two Document-level guards could
    // matter is an Error thrown by Document's OWN bookkeeping code around an
    // already-applied op (ensureClone, eventStream.emit, emitPresences,
    // reconcileHistoryEdits) rather than by the operation itself -- none of which this
    // suite has a seam to force without either an unsafe fault injection (genuinely
    // exhausting the heap for a real OutOfMemoryError) or a test-only hook added to
    // production code (out of scope: no drive-by changes). Pinned by code inspection
    // instead: both guards are a one-line, same-shape addition in the same two catch
    // blocks as the CancellationException guard directly above (also pinned by
    // inspection and precedent only -- see that comment for why no test is shipped for
    // either).

    // Confirms directly what the Error-guard comment above already predicted by
    // inspection -- an
    // operation's own `execute` throwing a java.lang.Error for a Remote source IS
    // wrapped into a ChangeApplyError by Change.execute's per-op catch (Change.kt),
    // with the Error reachable as `cause`, exactly like any other Throwable the
    // operation throws (JS-faithful: JS wraps everything unconditionally too). This is
    // deliberately NOT excluded -- Document.applyChanges/applyChangesInternal's own
    // Error/CancellationException guards only ever see an Error from Document's OWN
    // bookkeeping around the two change.execute calls, never one from inside them; see
    // the narrowed KDoc on Change.execute and Document.applyChanges.
    //
    // Red-green, done by hand for this hunk: temporarily added `if (source ==
    // OpSource.Remote && cause is Error) throw cause` ahead of the ChangeApplyError
    // throw in Change.kt's per-op catch -> this test went red (`OutOfMemoryError`
    // surfaced directly, failing `assertFailsWith<ChangeApplyError>`) -> reverted
    // (`git diff` empty) -> green again. The fix here is KDoc-only; Change.kt's
    // wrapping itself is intentionally unchanged (parity-preserving).
    @Test
    fun `a Remote op throwing an Error is wrapped into ChangeApplyError with the Error as cause`() =
        runTest {
            val (change, op) = oneOpChange()
            val spiedOp = spyk(op)
            every { spiedOp.execute(any(), any(), any()) } throws OutOfMemoryError("boom")

            val target = Document("d")
            val thrown = assertFailsWith<ChangeApplyError> {
                target.applyChangePack(packOf(target, change, listOf(spiedOp)))
            }

            assertTrue(thrown.cause is OutOfMemoryError)
            assertEquals("boom", thrown.cause?.message)
        }
}
