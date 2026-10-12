package dev.yorkie.core

import android.util.Base64
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `doc_store_test.ts` (`2291bf67`/#1338),
 * rewired onto the incremental `DocStore` shape (`aaa5cb15`/#1354).
 * The round-trip/defensive-copy/overwrite/remove cases this file used to cover (T1-T6) moved
 * to [DocStoreContractTest], which is stricter (11 cases incl. the append/meta contract) —
 * no coverage loss. T7/T8 remain here: the persistence loop through
 * [Document.toBytes]/[Document.Companion.fromBytes] via the new `saveSnapshot`/`load(...)
 * ?.snapshot` shape.
 */
class DocStoreTest {

    private val docKey = "doc-store-test-key"
    private val actorA = "000000000000000000000001"

    // A restored ChangeID with a non-empty VersionVector routes through
    // android.util.Base64, an unmocked stub under plain JVM unit tests.
    // Harmless for cases whose version vector stays empty.
    @Before
    fun setUp() {
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
        unmockkStatic(Base64::class)
    }

    @Test
    fun `T7 the persistence loop reconstructs root, presence, checkpoint, changeID, and pending`() =
        runTest {
            val store = MemoryDocStore()
            val document = Document(docKey)
            document.setActor(actorA)
            document.updateAsync { root, presence ->
                root.setNewObject("obj")["nested"] = true
                presence.put(mapOf("cursor" to "1"))
            }.await()

            store.saveSnapshot(docKey, document.toBytes())
            val restored = Document.fromBytes(docKey, checkNotNull(store.load(docKey)?.snapshot))

            assertEquals(document.toJson(), restored.toJson())
            assertEquals(document.allPresences.value[actorA], restored.allPresences.value[actorA])
            assertEquals(document.checkPoint, restored.checkPoint)
            assertEquals(document.changeID.actor, restored.changeID.actor)
            assertEquals(document.pendingChanges().size, restored.pendingChanges().size)
        }

    @Test
    fun `T8 a restored non-zero checkpoint flows into the attach pack`() = runTest {
        val store = MemoryDocStore()
        val document = Document(docKey)
        document.setActor(actorA)
        document.updateAsync { root, _ -> root["k1"] = 1 }.await()
        // Server-forwarded checkpoint, as a prior attach/sync would have left it.
        document.applyChangePack(
            ChangePack(docKey, CheckPoint(5, 3u), emptyList(), null, false, INITIAL_VERSION_VECTOR),
        )

        store.saveSnapshot(docKey, document.toBytes())
        val restored = Document.fromBytes(docKey, checkNotNull(store.load(docKey)?.snapshot))

        assertEquals(5L, restored.checkPoint.serverSeq)
        assertEquals(5L, restored.createChangePack().checkPoint.serverSeq)
    }
}
