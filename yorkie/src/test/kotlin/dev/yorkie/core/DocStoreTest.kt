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
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `doc_store_test.ts` (`2291bf67`/#1338, RTCOLLABPLATFORM-771). Pins
 * [MemoryDocStore]'s round-trip, defensive-copy, overwrite, and remove contract, plus the
 * persistence loop through [Document.toBytes]/[Document.Companion.fromBytes].
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
    fun `T1 round-trips saved bytes`() = runTest {
        val store = MemoryDocStore()
        val payload = byteArrayOf(1, 2, 3, 4)

        store.save(docKey, payload)

        assertEquals(payload.toList(), store.load(docKey)?.toList())
    }

    @Test
    fun `T2 load returns the stored bytes unaffected by a caller mutation after save`() = runTest {
        val store = MemoryDocStore()
        val payload = byteArrayOf(1, 2, 3)

        store.save(docKey, payload)
        payload[0] = 99

        assertEquals(listOf<Byte>(1, 2, 3), store.load(docKey)?.toList())
    }

    @Test
    fun `T3 mutating a load result does not corrupt the store`() = runTest {
        val store = MemoryDocStore()
        store.save(docKey, byteArrayOf(1, 2, 3))

        val loaded = store.load(docKey)
        loaded?.set(0, 99)

        assertEquals(listOf<Byte>(1, 2, 3), store.load(docKey)?.toList())
    }

    @Test
    fun `T4 save overwrites a previous value`() = runTest {
        val store = MemoryDocStore()
        store.save(docKey, byteArrayOf(1))
        store.save(docKey, byteArrayOf(2, 2))

        assertEquals(listOf<Byte>(2, 2), store.load(docKey)?.toList())
    }

    @Test
    fun `T5 remove clears a stored key`() = runTest {
        val store = MemoryDocStore()
        store.save(docKey, byteArrayOf(1))

        store.remove(docKey)

        assertNull(store.load(docKey))
    }

    @Test
    fun `T6 remove of an absent key is a no-op`() = runTest {
        val store = MemoryDocStore()

        store.remove(docKey)

        assertNull(store.load(docKey))
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

            store.save(docKey, document.toBytes())
            val restored = Document.fromBytes(docKey, checkNotNull(store.load(docKey)))

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

        store.save(docKey, document.toBytes())
        val restored = Document.fromBytes(docKey, checkNotNull(store.load(docKey)))

        assertEquals(5L, restored.checkPoint.serverSeq)
        assertEquals(5L, restored.createChangePack().checkPoint.serverSeq)
    }
}
