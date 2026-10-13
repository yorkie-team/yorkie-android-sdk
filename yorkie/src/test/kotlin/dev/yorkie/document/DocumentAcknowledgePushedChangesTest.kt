package dev.yorkie.document

import dev.yorkie.core.ResourceStatus
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
