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
import dev.yorkie.document.json.JsonDedupCounter
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
}
