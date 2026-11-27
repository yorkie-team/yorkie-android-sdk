package dev.yorkie.document

import dev.yorkie.api.PBPresenceChangeType
import dev.yorkie.api.toPresenceChange
import dev.yorkie.api.v1.presenceChange
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonDedupCounter
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.ChangeApplyError
import dev.yorkie.util.Logger
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Ports JS `remote_skip_test.ts` (`dcb74d27`, #1502, 7 cases) plus the iOS non-primitive
 * case (`8fa07218fd`): a stored remote change this SDK cannot apply -- a dedup increase
 * with no actor, a refused delta, or a non-primitive value, or an UNSPECIFIED/DELETE/
 * out-of-enum presence change -- must be skipped on the pull path (Go's
 * `skipUnlessLocal`/`FromPresenceChange`) instead of throwing and wedging the pack. This
 * presence-decode shape is pinned a second time, end to end through [Change.execute],
 * here; [dev.yorkie.api.PresenceConverterTest] covers the decoder in isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSkipTest {

    // The replica that "wrote" these remote changes -- distinct from the dedup actor
    // IncreaseOperation.actor carries, exactly as JS's remoteIncrease reuses one
    // change id across varying dedup actors.
    private val remoteWriter = "0000000000000000000000aa"

    private lateinit var recordingLogger: RecordingLogger

    @Before
    fun setUp() {
        recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)
    }

    @After
    fun tearDown() {
        // Logger is a process-wide singleton: leave a fresh, non-capturing instance behind.
        Logger.init(RecordingLogger())
    }

    private val skipLog = "[Increase] skipped a remote increase that cannot apply"

    private suspend fun targetWithCounter(): Pair<Document, TimeTicket> {
        val target = Document("d")
        lateinit var counterCreatedAt: TimeTicket
        target.updateAsync { root, _ ->
            counterCreatedAt = root.setNewDedupCounter("uv").target.createdAt
        }.await()
        return target to counterCreatedAt
    }

    private fun increaseChange(
        counterCreatedAt: TimeTicket,
        value: CrdtElement,
        dedupActor: String,
        lamport: Long,
    ): Change {
        return Change(
            ChangeID(lamport.toUInt(), lamport, remoteWriter, VersionVector()),
            listOf(
                IncreaseOperation(
                    value = value,
                    parentCreatedAt = counterCreatedAt,
                    executedAt = TimeTicket(lamport, 1u, remoteWriter),
                    actor = dedupActor,
                ),
            ),
        )
    }

    private fun unitValue(lamport: Long) = CrdtPrimitive(1, TimeTicket(lamport, 0u, remoteWriter))

    private suspend fun applyRemote(
        target: Document,
        vararg changes: Change,
        serverSeq: Long,
    ) {
        target.applyChangePack(
            ChangePack(
                target.getKey(),
                CheckPoint(serverSeq, 0u),
                changes.toList(),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    private suspend fun dedupValue(target: Document) = target.getRoot().getAs<JsonDedupCounter>(
        "uv",
    ).value

    @Test
    fun `applies a dedup increase that carries an actor and a unit delta`() = runTest {
        val (target, counterCreatedAt) = targetWithCounter()

        applyRemote(
            target,
            increaseChange(counterCreatedAt, unitValue(1), "actor-a", lamport = 1),
            serverSeq = 1,
        )
        assertEquals(1, dedupValue(target))

        // The same actor again is deduplicated.
        applyRemote(
            target,
            increaseChange(counterCreatedAt, unitValue(2), "actor-a", lamport = 2),
            serverSeq = 2,
        )
        assertEquals(1, dedupValue(target))

        // A second, distinct actor counts.
        applyRemote(
            target,
            increaseChange(counterCreatedAt, unitValue(3), "actor-b", lamport = 3),
            serverSeq = 3,
        )
        assertEquals(2, dedupValue(target))
    }

    @Test
    fun `skips a dedup increase that carries no actor`() = runTest {
        val (target, counterCreatedAt) = targetWithCounter()

        // A later change in the SAME pack must still land -- the point of skipping is
        // that one unapplicable change does not wedge the rest of the pack.
        val bad = increaseChange(counterCreatedAt, unitValue(1), dedupActor = "", lamport = 1)
        val good = Change(
            ChangeID(2u, 2, remoteWriter, VersionVector()),
            listOf(
                SetOperation(
                    key = "k",
                    value = CrdtPrimitive("v", TimeTicket(2, 0u, remoteWriter)),
                    parentCreatedAt = TimeTicket.InitialTimeTicket,
                    executedAt = TimeTicket(2, 1u, remoteWriter),
                ),
            ),
        )

        applyRemote(target, bad, good, serverSeq = 2)

        assertEquals(0, dedupValue(target))
        assertEquals("v", target.getRoot().getAs<JsonPrimitive>("k").value)
        assertEquals(2L, target.checkPoint.serverSeq)
    }

    @Test
    fun `skips a dedup increase whose delta the counter refuses`() = runTest {
        val (target, counterCreatedAt) = targetWithCounter()

        val bad = increaseChange(
            counterCreatedAt,
            CrdtPrimitive(2, TimeTicket(1, 0u, remoteWriter)),
            "actor-a",
            lamport = 1,
        )
        applyRemote(target, bad, serverSeq = 1) // must not throw

        assertEquals(0, dedupValue(target))
    }

    @Test
    fun `skips a remote dedup increase whose value is not a primitive`() = runTest {
        // iOS 8fa07218fd: a value that is not a primitive at all (as a non-SDK writer
        // could push it) must be skipped the same way, not fail the cast first.
        val (target, counterCreatedAt) = targetWithCounter()

        val nonPrimitive = CrdtObject(TimeTicket(1, 0u, remoteWriter), memberNodes = ElementRht())
        val bad = increaseChange(counterCreatedAt, nonPrimitive, "actor-a", lamport = 1)
        applyRemote(target, bad, serverSeq = 1) // must not throw

        assertEquals(0, dedupValue(target))
    }

    @Test
    fun `still reports a local dedup increase that cannot be applied`() {
        // Direct operation-level execute, as JS/iOS do, since the public JSON API
        // (JsonDedupCounter.add) already rejects these inputs before they could ever
        // reach IncreaseOperation through update()/undo/redo.
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val counterCreatedAt = TimeTicket(1, 0u, remoteWriter)
        SetOperation(
            key = "uv",
            value = CrdtCounter.createDedup(counterCreatedAt),
            parentCreatedAt = obj.createdAt,
            executedAt = counterCreatedAt,
        ).execute(root, OpSource.Local, null)

        for (source in listOf(OpSource.Local, OpSource.UndoRedo)) {
            val noActor = IncreaseOperation(
                value = CrdtPrimitive(1, TimeTicket(2, 0u, remoteWriter)),
                parentCreatedAt = counterCreatedAt,
                executedAt = TimeTicket(2, 1u, remoteWriter),
                actor = "",
            )
            val thrownNoActor = assertFailsWith<YorkieException> {
                noActor.execute(root, source, null)
            }
            assertEquals("dedup counter requires actor", thrownNoActor.errorMessage)

            val badDelta = IncreaseOperation(
                value = CrdtPrimitive(2, TimeTicket(3, 0u, remoteWriter)),
                parentCreatedAt = counterCreatedAt,
                executedAt = TimeTicket(3, 1u, remoteWriter),
                actor = "actor-a",
            )
            val thrownBadDelta = assertFailsWith<IllegalArgumentException> {
                badDelta.execute(root, source, null)
            }
            assertEquals("dedup counter only supports increment by 1", thrownBadDelta.message)
        }
    }

    @Test
    fun `decodes UNSPECIFIED and DELETE as an empty presence, then still applies the change`() =
        runTest {
            for (type in listOf(
                PBPresenceChangeType.CHANGE_TYPE_UNSPECIFIED,
                PBPresenceChangeType.CHANGE_TYPE_DELETE,
            )) {
                val pb = presenceChange { this.type = type }
                val decoded = pb.toPresenceChange()
                assertEquals(PresenceChange.Put(emptyMap()), decoded)

                // Go's Change.Execute stores the decoded empty presence for the actor, and
                // the change's own operation still applies -- it must never throw and wedge
                // the rest of the pack.
                val target = Document("d")
                val remoteActor = "0000000000000000000000bb"
                val change = Change(
                    ChangeID(1u, 1, remoteActor, VersionVector()),
                    listOf(
                        SetOperation(
                            key = "k",
                            value = CrdtPrimitive("v", TimeTicket(1, 0u, remoteActor)),
                            parentCreatedAt = TimeTicket.InitialTimeTicket,
                            executedAt = TimeTicket(1, 1u, remoteActor),
                        ),
                    ),
                    presenceChange = decoded,
                )

                applyRemote(target, change, serverSeq = 1)

                assertEquals("v", target.getRoot().getAs<JsonPrimitive>("k").value)
                assertEquals(emptyMap<String, String>(), target.allPresences.value[remoteActor])
            }
        }

    // ---- plain counter (js#1525 / Go skipUnlessLocal) ----

    private suspend fun targetWithPlainCounter(): Pair<Document, TimeTicket> {
        val target = Document("d")
        lateinit var counterCreatedAt: TimeTicket
        target.updateAsync { root, _ ->
            counterCreatedAt = root.setNewCounter("c", 0).target.createdAt
            root["k"] = "v"
        }.await()
        return target to counterCreatedAt
    }

    private fun plainIncreaseChange(
        counterCreatedAt: TimeTicket,
        value: CrdtElement,
        lamport: Long,
    ) = increaseChange(counterCreatedAt, value, dedupActor = "", lamport = lamport)

    private fun setKChange(lamport: Long) = Change(
        ChangeID(lamport.toUInt(), lamport, remoteWriter, VersionVector()),
        listOf(
            SetOperation(
                key = "k",
                value = CrdtPrimitive("later", TimeTicket(lamport, 0u, remoteWriter)),
                parentCreatedAt = TimeTicket.InitialTimeTicket,
                executedAt = TimeTicket(lamport, 1u, remoteWriter),
            ),
        ),
    )

    private suspend fun plainValue(target: Document) =
        target.getRoot().getAs<JsonCounter>("c").value

    @Test
    fun `applies a remote plain increase that carries a Double delta`() = runTest {
        val (target, counterCreatedAt) = targetWithPlainCounter()
        val delta = CrdtPrimitive(1.9, TimeTicket(1, 0u, remoteWriter))

        applyRemote(
            target,
            plainIncreaseChange(counterCreatedAt, delta, lamport = 1),
            setKChange(lamport = 2),
            serverSeq = 2,
        )

        assertEquals(1, plainValue(target))
        assertEquals("later", target.getRoot().getAs<JsonPrimitive>("k").value)
        assertEquals(2L, target.checkPoint.serverSeq)
    }

    @Test
    fun `skips a plain increase whose delta the counter refuses`() = runTest {
        val (target, counterCreatedAt) = targetWithPlainCounter()
        val refused = CrdtPrimitive("1", TimeTicket(1, 0u, remoteWriter))

        applyRemote(
            target,
            plainIncreaseChange(counterCreatedAt, refused, lamport = 1),
            setKChange(lamport = 2),
            serverSeq = 2,
        ) // must not throw

        assertEquals(0, plainValue(target))
        assertEquals("later", target.getRoot().getAs<JsonPrimitive>("k").value)
        assertEquals(2L, target.checkPoint.serverSeq)
    }

    @Test
    fun `skips an increase whose value is not a primitive`() = runTest {
        val (target, counterCreatedAt) = targetWithPlainCounter()
        val nonPrimitive = CrdtObject(TimeTicket(1, 0u, remoteWriter), memberNodes = ElementRht())

        applyRemote(
            target,
            plainIncreaseChange(counterCreatedAt, nonPrimitive, lamport = 1),
            setKChange(lamport = 2),
            serverSeq = 2,
        )

        assertEquals(0, plainValue(target))
        assertEquals(2L, target.checkPoint.serverSeq)
    }

    @Test
    fun `skips a remote dedup increase whose delta is a Double`() = runTest {
        val (target, counterCreatedAt) = targetWithCounter()
        val delta = CrdtPrimitive(1.0, TimeTicket(1, 0u, remoteWriter))

        applyRemote(target, increaseChange(counterCreatedAt, delta, "actor-a", 1), serverSeq = 1)

        assertEquals(0, dedupValue(target))
    }

    private fun localRootWithCounters(): Triple<CrdtRoot, TimeTicket, TimeTicket> {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val counterCreatedAt = TimeTicket(1, 0u, remoteWriter)
        val dedupCreatedAt = TimeTicket(2, 0u, remoteWriter)
        SetOperation("c", CrdtCounter(0, counterCreatedAt), obj.createdAt, counterCreatedAt)
            .execute(root, OpSource.Local, null)
        SetOperation("uv", CrdtCounter.createDedup(dedupCreatedAt), obj.createdAt, dedupCreatedAt)
            .execute(root, OpSource.Local, null)
        return Triple(root, counterCreatedAt, dedupCreatedAt)
    }

    private fun op(
        parent: TimeTicket,
        value: CrdtElement,
        actor: String = "",
    ) = IncreaseOperation(value, parent, TimeTicket(9, 1u, remoteWriter), actor)

    @Test
    fun `still reports a local plain increase that cannot be applied`() {
        val (root, counterCreatedAt, _) = localRootWithCounters()
        val refused = op(counterCreatedAt, CrdtPrimitive("1", TimeTicket(9, 0u, remoteWriter)))
        val notPrimitive = op(
            counterCreatedAt,
            CrdtObject(TimeTicket(9, 0u, remoteWriter), memberNodes = ElementRht()),
        )

        for (source in listOf(OpSource.Local, OpSource.UndoRedo, OpSource.LocalNoHistory)) {
            assertFailsWith<IllegalArgumentException> { refused.execute(root, source, null) }
            val thrown = assertFailsWith<YorkieException> {
                notPrimitive.execute(root, source, null)
            }
            assertEquals(YorkieException.Code.ErrInvalidArgument, thrown.code)
            assertEquals("fail to execute, only Primitive can be increased", thrown.errorMessage)
        }
        assertTrue(recordingLogger.errorMessages.none { skipLog in it })
    }

    @Test
    fun `fails an increase on an element that is not a counter for every source`() = runTest {
        // Go and JS throw for every source (yorkie-js-sdk v0.7.29 increase_operation.ts);
        // before RTCOLLABPLATFORM-805 Android logged and returned executed=false.
        val (root, _, _) = localRootWithCounters()
        val delta = CrdtPrimitive(1, TimeTicket(9, 0u, remoteWriter))
        val onObject = op(TimeTicket.InitialTimeTicket, delta)
        val missing = TimeTicket(77, 0u, remoteWriter)
        val onMissing = op(missing, delta)

        for (source in OpSource.entries) {
            val notCounter = assertFailsWith<YorkieException> {
                onObject.execute(root, source, null)
            }
            assertEquals(YorkieException.Code.ErrInvalidArgument, notCounter.code)
            assertEquals(
                "fail to execute, only Counter can execute increase",
                notCounter.errorMessage,
            )
            val gone = assertFailsWith<YorkieException> { onMissing.execute(root, source, null) }
            assertEquals(YorkieException.Code.ErrInvalidArgument, gone.code)
            assertEquals("fail to find ${missing.toTestString()}", gone.errorMessage)
        }

        // On the pull path the failure is the usual ChangeApplyError, as in Go.
        val (target, _) = targetWithPlainCounter()
        val bad = plainIncreaseChange(
            TimeTicket.InitialTimeTicket,
            CrdtPrimitive(1, TimeTicket(1, 0u, remoteWriter)),
            lamport = 1,
        )
        assertFailsWith<ChangeApplyError> { applyRemote(target, bad, serverSeq = 1) }
    }

    @Test
    fun `logs a value-free warning when it skips a remote increase`() = runTest {
        val leaky = "leaky-delta-text"
        val (target, counterCreatedAt) = targetWithPlainCounter()
        val (dedupTarget, dedupCreatedAt) = targetWithCounter()
        fun prim(v: Any) = CrdtPrimitive(v, TimeTicket(1, 0u, remoteWriter))
        val nonPrimitive = CrdtObject(TimeTicket(1, 0u, remoteWriter), memberNodes = ElementRht())

        // A pulled change is applied to the clone and to the root, so one skipped op can
        // log more than once: assert each skip site logs, not an exact count.
        var seen = 0
        fun assertLoggedMore() {
            val now = recordingLogger.errorMessages.count { skipLog in it }
            assertTrue(now > seen)
            seen = now
        }

        applyRemote(target, plainIncreaseChange(counterCreatedAt, prim(leaky), 1), serverSeq = 1)
        assertLoggedMore()
        applyRemote(
            target,
            plainIncreaseChange(counterCreatedAt, nonPrimitive, 2),
            serverSeq = 2,
        )
        assertLoggedMore()
        applyRemote(
            dedupTarget,
            increaseChange(dedupCreatedAt, prim(1), dedupActor = "", lamport = 1),
            serverSeq = 1,
        )
        assertLoggedMore()
        applyRemote(
            dedupTarget,
            increaseChange(dedupCreatedAt, prim(2), "actor-a", lamport = 2),
            serverSeq = 2,
        )
        assertLoggedMore()

        // The log names the failure kind but never the delta or value content.
        assertTrue(recordingLogger.errorMessages.none { leaky in it })
    }
}
