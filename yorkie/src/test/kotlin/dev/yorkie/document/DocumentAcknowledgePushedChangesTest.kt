package dev.yorkie.document

import dev.yorkie.core.ResourceStatus
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports JS SDK `document.ts` `acknowledgePushedChanges` (`bed81143`, #1401) as
 * a JVM unit test.
 *
 * [Document.acknowledgePushedChanges] is used for a response [ChangePack]
 * dropped without applying its remote state (push-only/sync-off): the
 * confirmed local changes and the [ChangePack]'s compaction epoch and
 * removal flag are taken, but the server seq stays put and the changes
 * themselves are never applied, so the skipped remote state is pulled again
 * once realtime sync resumes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentAcknowledgePushedChangesTest {

    @Test
    fun `removes pushed local changes and forwards only the client seq`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ -> root["k1"] = 1 }.await()
        document.updateAsync { root, _ -> root["k2"] = 2 }.await()
        assertTrue(document.hasLocalChanges())
        assertEquals(CheckPoint.InitialCheckPoint, document.checkPoint)

        // serverSeq 99 on the dropped pack must NOT reach document.checkPoint:
        // only the client seq ack is forwarded, so an explicit-sync pull
        // still re-pulls the skipped remote state once realtime resumes.
        document.acknowledgePushedChanges(
            ChangePack(
                document.getKey(),
                CheckPoint(99, 2u),
                emptyList(),
                null,
                false,
                VersionVector(),
                epoch = 7,
            ),
        )

        assertEquals(CheckPoint(0, 2u), document.checkPoint)
        assertFalse(document.hasLocalChanges())
        assertEquals(7L, document.epoch)
        assertEquals(ResourceStatus.Detached, document.getStatus())
    }

    @Test
    fun `forwards the client seq ack forward-only`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ -> root["k1"] = 1 }.await()
        document.updateAsync { root, _ -> root["k2"] = 2 }.await()

        document.acknowledgePushedChanges(
            ChangePack(
                document.getKey(),
                CheckPoint(0, 2u),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        assertEquals(CheckPoint(0, 2u), document.checkPoint)

        // A stale/out-of-order ack (lower client seq) must not regress the
        // checkpoint: CheckPoint.forward is a per-field max.
        document.acknowledgePushedChanges(
            ChangePack(
                document.getKey(),
                CheckPoint(0, 1u),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        assertEquals(CheckPoint(0, 2u), document.checkPoint)
    }

    @Test
    fun `applies the removal flag`() = runTest {
        val document = Document("d")
        assertEquals(ResourceStatus.Detached, document.getStatus())

        document.acknowledgePushedChanges(
            ChangePack(
                document.getKey(),
                CheckPoint(0, 0u),
                emptyList(),
                null,
                true,
                VersionVector(),
            ),
        )

        assertEquals(ResourceStatus.Removed, document.getStatus())
    }

    // --- presence-patch (spec 040) ---------------------------------------

    @Test
    fun `JS15 heals on a push ack whose remote state is dropped`() = runTest {
        val document = Document("d")
        document.updateAsync { _, p -> p.put(mapOf("name" to "a")) }.await()
        document.applyChangePack(
            ChangePack(
                document.getKey(),
                CheckPoint(0, 1u),
                emptyList(),
                null,
                false,
                VersionVector(),
                capabilities = listOf(ChangePack.PRESENCE_PATCH_CAPABILITY),
            ),
        )
        document.updateAsync { _, p -> p.put(mapOf("cursor" to "1,1")) }.await()
        assertIs<PresenceChange.Patch>(
            document.createChangePack().changes.single().presenceChange,
        )

        // PushOnly/SyncOff take only the push ack from the reply. The capability is
        // metadata about the server that took the push, not the remote state being
        // skipped, so the heal must happen on THIS path too (not just applyChangePack's).
        val pending = document.createChangePack().changes
        document.acknowledgePushedChanges(
            ChangePack(
                document.getKey(),
                CheckPoint(0, pending.last().id.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )

        val healed = document.createChangePack().changes.single().presenceChange
        assertIs<PresenceChange.Put>(healed)
        assertEquals(mapOf("name" to "a", "cursor" to "1,1"), healed.presence)
    }
}
