package dev.yorkie.document

import android.util.Base64
import dev.yorkie.document.Document.Event.PresenceChanged
import dev.yorkie.document.Document.Event.RemoteChange
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.ChangeApplyError
import dev.yorkie.util.Logger
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * RTCOLLABPLATFORM-805 (CodeRabbit on PR #401): a remote pack that fails partway must not
 * re-apply its earlier changes on redelivery. Each applied remote change advances the
 * checkpoint's serverSeq, so the server (which pulls strictly after the client's serverSeq)
 * redelivers from the failing change. [serverDeliver] plays that server.
 *
 * Android differs from JS, iOS and Go here on purpose: they forward the checkpoint only after
 * the whole pack and avoid the issue by not throwing on remote ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackRedeliveryTest {

    private val actorB = "0000000000000000000000bb"
    private val documents = mutableListOf<Document>()

    // restoring a ChangeID with a non-empty VersionVector routes through android.util.Base64
    @Before
    fun setUp() {
        Logger.init(RecordingLogger())
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        documents.forEach { it.close() }
        unmockkStatic(Base64::class)
        Logger.init(RecordingLogger())
    }

    private suspend fun newDocument(): Pair<Document, TimeTicket> {
        val doc = Document("d").also { documents.add(it) }
        lateinit var counterCreatedAt: TimeTicket
        doc.updateAsync { root, _ ->
            counterCreatedAt = root.setNewCounter("c", 0).target.createdAt
        }.await()
        return doc to counterCreatedAt
    }

    private fun increase(
        target: TimeTicket,
        delta: Int,
        serverSeq: Long,
        actor: String = actorB,
        presence: PresenceChange? = null,
    ): Change {
        val lamport = 10 + serverSeq
        return Change(
            ChangeID(
                serverSeq.toUInt(),
                lamport,
                actor,
                VersionVector(mapOf(actor to lamport)),
                serverSeq,
            ),
            listOf(
                IncreaseOperation(
                    value = CrdtPrimitive(delta, TimeTicket(lamport, 0u, actor)),
                    parentCreatedAt = target,
                    executedAt = TimeTicket(lamport, 1u, actor),
                ),
            ),
            presenceChange = presence,
        )
    }

    /** A change on a target that does not exist: throws for every source. */
    private fun blocked(serverSeq: Long) =
        increase(TimeTicket(777, 0u, actorB), delta = 1, serverSeq = serverSeq)

    /** What the server answers: every stored change strictly after the client's serverSeq. */
    private suspend fun serverDeliver(
        doc: Document,
        stored: List<Change>,
        packEnd: Long = stored.maxOf { it.id.serverSeq },
    ) {
        val after = doc.checkPoint.serverSeq
        doc.applyChangePack(
            ChangePack(
                doc.getKey(),
                CheckPoint(packEnd, 0u),
                stored.filter { it.id.serverSeq > after },
                null,
                false,
                VersionVector(),
            ),
        )
    }

    private suspend fun counter(doc: Document) = doc.getRoot().getAs<JsonCounter>("c").value

    @Test
    fun `S1 a held pack does not re-apply its earlier changes on redelivery`() = runTest {
        val (doc, c) = newDocument()
        val stored = listOf(increase(c, 1, serverSeq = 1), blocked(serverSeq = 2))

        repeat(3) { assertFailsWith<ChangeApplyError> { serverDeliver(doc, stored, packEnd = 5) } }

        assertEquals(1, counter(doc)) // counted once, not once per redelivery
        // the checkpoint is at the last applied change, below the pack's end
        assertEquals(1L, doc.checkPoint.serverSeq)
    }

    @Test
    fun `S2 redelivery after the prefix starts at the blocker and never uses its serverSeq`() =
        runTest {
            val (doc, c) = newDocument()
            val stored = listOf(increase(c, 1, serverSeq = 1), blocked(serverSeq = 2))
            assertFailsWith<ChangeApplyError> { serverDeliver(doc, stored) }

            assertFailsWith<ChangeApplyError> { serverDeliver(doc, stored) }

            assertEquals(1, counter(doc))
            assertEquals(1L, doc.checkPoint.serverSeq) // not the failing change's 2
        }

    @Test
    fun `S3 a blocked first change leaves the checkpoint where it was`() = runTest {
        val (doc, c) = newDocument()
        val before = doc.checkPoint

        assertFailsWith<ChangeApplyError> {
            serverDeliver(doc, listOf(blocked(serverSeq = 1), increase(c, 1, serverSeq = 2)))
        }

        assertEquals(before, doc.checkPoint)
        assertEquals(0, counter(doc))
    }

    @Test
    fun `S4 emits the remote change event of an applied prefix once across redeliveries`() =
        runTest {
            val (doc, c) = newDocument()
            val events = mutableListOf<Document.Event>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                doc.events.collect { events += it }
            }
            val stored = listOf(increase(c, 1, serverSeq = 1), blocked(serverSeq = 2))

            repeat(3) { assertFailsWith<ChangeApplyError> { serverDeliver(doc, stored) } }

            assertEquals(1, events.count { it is RemoteChange })
        }

    @Test
    fun `S5 applies a presence change of an applied prefix once`() = runTest {
        val (doc, c) = newDocument()
        doc.addOnlineClient(actorB)
        val events = mutableListOf<Document.Event>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            doc.events.collect { events += it }
        }
        val stored = listOf(
            increase(c, 1, serverSeq = 1, presence = PresenceChange.Put(mapOf("name" to "b"))),
            blocked(serverSeq = 2),
        )

        repeat(3) { assertFailsWith<ChangeApplyError> { serverDeliver(doc, stored) } }

        assertEquals(mapOf("name" to "b"), doc.allPresences.value[actorB])
        assertEquals(1, events.count { it is PresenceChanged })
    }

    @Test
    fun `S6 a fully applied pack still ends at the pack checkpoint`() = runTest {
        val (doc, c) = newDocument()

        serverDeliver(doc, listOf(increase(c, 1, serverSeq = 1), increase(c, 1, serverSeq = 2)), 5)

        assertEquals(2, counter(doc))
        assertEquals(5L, doc.checkPoint.serverSeq)
        // the pack's own checkpoint carries clientSeq; per-change forwarding never touched it
        assertEquals(0u, doc.checkPoint.clientSeq)
    }

    @Test
    fun `S7 a change without a serverSeq does not move the checkpoint`() = runTest {
        val (doc, c) = newDocument()
        val before = doc.checkPoint

        assertFailsWith<ChangeApplyError> {
            doc.applyChangePack(
                ChangePack(
                    doc.getKey(),
                    CheckPoint(9, 0u),
                    listOf(increase(c, 1, serverSeq = 0), blocked(serverSeq = 0)),
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }

        assertEquals(before, doc.checkPoint)
        assertEquals(1, counter(doc))
    }

    @Test
    fun `S8 replaying stored local changes does not move the checkpoint`() = runTest {
        val (doc, c) = newDocument()
        val before = doc.checkPoint

        doc.restoreAppendedChanges(
            listOf(increase(c, 1, serverSeq = 7, actor = doc.changeID.actor)),
        )

        assertEquals(before, doc.checkPoint)
        assertEquals(1, counter(doc))
    }

    @Test
    fun `S8b a reanchor after a failed pack resets the checkpoint with the root`() = runTest {
        val (doc, c) = newDocument()
        assertFailsWith<ChangeApplyError> {
            serverDeliver(doc, listOf(increase(c, 1, serverSeq = 1), blocked(serverSeq = 2)))
        }
        assertEquals(1L, doc.checkPoint.serverSeq)

        doc.resetForReanchor()

        assertEquals(CheckPoint.InitialCheckPoint, doc.checkPoint)
        assertEquals("{}", doc.toJson())
    }

    @Test
    fun `S10 a persisted envelope after a failed pack restores root and checkpoint consistently`() =
        runTest {
            val (doc, c) = newDocument()
            assertFailsWith<ChangeApplyError> {
                serverDeliver(doc, listOf(increase(c, 1, serverSeq = 1), blocked(serverSeq = 2)))
            }

            // the base persist a client does (toBytes) and the restore on the next launch
            val restored = Document.fromBytes("d", doc.toBytes()).also { documents.add(it) }

            assertEquals(1L, restored.checkPoint.serverSeq)
            assertEquals(1, counter(restored))
            // the server resumes after the persisted checkpoint: only the later change arrives
            serverDeliver(
                restored,
                listOf(increase(c, 1, serverSeq = 1), increase(c, 10, serverSeq = 3)),
            )
            assertEquals(11, counter(restored)) // not 12: the prefix is not applied again
        }

    @Test
    fun `S13 the clocks and the checkpoint both reflect the applied prefix`() = runTest {
        val (doc, c) = newDocument()

        assertFailsWith<ChangeApplyError> {
            serverDeliver(doc, listOf(increase(c, 1, serverSeq = 1), blocked(serverSeq = 2)))
        }

        assertNotNull(doc.changeID.versionVector.get(actorB))
        assertTrue(doc.changeID.versionVector.get(actorB)!! >= 11L)
        assertEquals(1L, doc.checkPoint.serverSeq)
    }

    @Test
    fun `S9 reconciles a pending undo entry once for an applied prefix`() = runTest {
        // A creates "abc"; B receives it, then types "X" locally (an undo entry for [3, 4)).
        val a = Document("d").also { documents.add(it) }
        a.setActor("0000000000000000000000a1")
        a.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "abc") }.await()
        val b = Document("d").also { documents.add(it) }
        b.setActor("0000000000000000000000b2")
        suspend fun stored(doc: Document, serverSeq: Long): Change {
            val change = doc.createChangePack().changes.last()
            return change.copy(id = change.id.copy(serverSeq = serverSeq))
        }
        serverDeliver(b, listOf(stored(a, 1)))
        b.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(3, 3, "X") }.await()

        // A types "ZZ" in front: the prefix of the pack, followed by a blocked change.
        a.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(0, 0, "ZZ") }.await()
        val packChanges = listOf(stored(a, 2), blocked(serverSeq = 3))
        repeat(2) { assertFailsWith<ChangeApplyError> { serverDeliver(b, packChanges) } }

        assertEquals("ZZabcX", b.getRoot().getAs<JsonText>("t").toString())
        // the undo entry moved from [3, 4) to [5, 6) exactly once: undoing removes the "X"
        b.history.undoAsync().await()
        assertEquals("ZZabc", b.getRoot().getAs<JsonText>("t").toString())
    }
}
