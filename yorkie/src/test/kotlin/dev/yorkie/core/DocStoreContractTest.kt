package dev.yorkie.core

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `doc_store_contract.ts` (`aaa5cb15`/#1354),
 * an 11-case suite over [MemoryDocStore]. The suite exists because the SDK's two independent
 * implementations once disagreed on whether [DocStore.saveSnapshot] keeps meta and whether
 * [DocStore.saveMeta] trims the log — copies diverge, and this suite is the single place that
 * pins the contract both must agree on.
 */
class DocStoreContractTest {

    private val docKey = "contract-test-key"

    @Test
    fun `1 load on a fresh store is null`() = runTest {
        val store = MemoryDocStore()

        assertNull(store.load("nope"))
    }

    @Test
    fun `2 saveSnapshot then load returns the snapshot with an empty log and no meta`() = runTest {
        val store = MemoryDocStore()

        store.saveSnapshot(docKey, byteArrayOf(1, 2, 3))

        val loaded = checkNotNull(store.load(docKey))
        assertEquals(listOf<Byte>(1, 2, 3), loaded.snapshot.toList())
        assertEquals(emptyList(), loaded.changes)
        assertNull(loaded.meta)
    }

    @Test
    fun `3 a second saveSnapshot overwrites the first`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(1))

        store.saveSnapshot(docKey, byteArrayOf(2, 2))

        assertEquals(listOf<Byte>(2, 2), checkNotNull(store.load(docKey)).snapshot.toList())
    }

    @Test
    fun `4 appended changes come back ordered by clientSeq`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))

        store.appendChange(docKey, StoredChange(2u, byteArrayOf(2)))
        store.appendChange(docKey, StoredChange(1u, byteArrayOf(1)))

        val changes = checkNotNull(store.load(docKey)).changes
        assertEquals(listOf(1u, 2u), changes.map { it.clientSeq })
        assertEquals(listOf<Byte>(1), changes.first().bytes.toList())
    }

    @Test
    fun `5 appendChange upserts keyed by clientSeq`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))

        store.appendChange(docKey, StoredChange(1u, byteArrayOf(1)))
        store.appendChange(docKey, StoredChange(1u, byteArrayOf(9)))

        val changes = checkNotNull(store.load(docKey)).changes
        assertEquals(1, changes.size)
        assertEquals(listOf<Byte>(9), changes.single().bytes.toList())
    }

    @Test
    fun `6 appendChange on a key with no snapshot is a silent no-op`() = runTest {
        val store = MemoryDocStore()

        store.appendChange(docKey, StoredChange(1u, byteArrayOf(1)))

        assertNull(store.load(docKey))
    }

    @Test
    fun `7 saveSnapshot drops the log and the meta`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))
        store.appendChange(docKey, StoredChange(1u, byteArrayOf(1)))
        store.saveMeta(docKey, byteArrayOf(7))

        store.saveSnapshot(docKey, byteArrayOf(9))

        val loaded = checkNotNull(store.load(docKey))
        assertEquals(listOf<Byte>(9), loaded.snapshot.toList())
        assertEquals(emptyList(), loaded.changes)
        assertNull(loaded.meta)
    }

    @Test
    fun `8 saveMeta leaves the snapshot and the log intact`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))
        store.appendChange(docKey, StoredChange(1u, byteArrayOf(1)))
        store.appendChange(docKey, StoredChange(2u, byteArrayOf(2)))
        store.appendChange(docKey, StoredChange(3u, byteArrayOf(3)))

        store.saveMeta(docKey, byteArrayOf(7))

        val loaded = checkNotNull(store.load(docKey))
        assertEquals(listOf(1u, 2u, 3u), loaded.changes.map { it.clientSeq })
        assertEquals(listOf<Byte>(7), checkNotNull(loaded.meta).toList())
        assertEquals(listOf<Byte>(0), loaded.snapshot.toList())
    }

    @Test
    fun `9 saveMeta on an absent entry is a no-op`() = runTest {
        val store = MemoryDocStore()

        store.saveMeta(docKey, byteArrayOf(7))

        assertNull(store.load(docKey))
    }

    @Test
    fun `10 remove clears everything and is a no-op on a missing key`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))
        store.saveMeta(docKey, byteArrayOf(7))
        store.appendChange(docKey, StoredChange(1u, byteArrayOf(1)))

        store.remove(docKey)

        assertNull(store.load(docKey))
        store.remove("missing-key")
    }

    @Test
    fun `11 caller mutation on either side of the boundary does not corrupt the store`() = runTest {
        val store = MemoryDocStore()
        val snapshot = byteArrayOf(1, 2, 3)
        val changeBytes = byteArrayOf(9)

        store.saveSnapshot(docKey, snapshot)
        store.appendChange(docKey, StoredChange(1u, changeBytes))
        snapshot[0] = 99
        changeBytes[0] = 99

        val loaded = checkNotNull(store.load(docKey))
        loaded.snapshot[0] = 99
        loaded.changes.single().bytes[0] = 99

        val reloaded = checkNotNull(store.load(docKey))
        assertEquals(listOf<Byte>(1, 2, 3), reloaded.snapshot.toList())
        assertEquals(listOf<Byte>(9), reloaded.changes.single().bytes.toList())
    }

    // --- adversarial probes -------------------------------------------------

    @Test
    fun `adversarial appendChange accepts clientSeq 0 and UInt MAX_VALUE`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))

        store.appendChange(docKey, StoredChange(0u, byteArrayOf(0)))
        store.appendChange(docKey, StoredChange(UInt.MAX_VALUE, byteArrayOf(1)))

        val changes = checkNotNull(store.load(docKey)).changes
        assertEquals(listOf(0u, UInt.MAX_VALUE), changes.map { it.clientSeq })
    }

    @Test
    fun `adversarial saveMeta then appendChange then load preserves both`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))

        store.saveMeta(docKey, byteArrayOf(5))
        store.appendChange(docKey, StoredChange(1u, byteArrayOf(1)))

        val loaded = checkNotNull(store.load(docKey))
        assertEquals(listOf<Byte>(5), checkNotNull(loaded.meta).toList())
        assertEquals(1, loaded.changes.size)
    }

    @Test
    fun `adversarial concurrent appendChange from two coroutines loses no entry`() = runTest {
        val store = MemoryDocStore()
        store.saveSnapshot(docKey, byteArrayOf(0))

        val first = async { store.appendChange(docKey, StoredChange(1u, byteArrayOf(1))) }
        val second = async { store.appendChange(docKey, StoredChange(2u, byteArrayOf(2))) }
        awaitAll(first, second)

        val changes = checkNotNull(store.load(docKey)).changes
        assertEquals(listOf(1u, 2u), changes.map { it.clientSeq })
    }
}
