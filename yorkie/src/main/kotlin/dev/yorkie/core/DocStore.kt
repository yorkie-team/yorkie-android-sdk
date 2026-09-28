package dev.yorkie.core

import java.util.concurrent.ConcurrentHashMap

/**
 * A byte-oriented, async storage seam for a [dev.yorkie.document.Document]'s persisted envelope
 * (see [dev.yorkie.document.Document.toBytes]/[dev.yorkie.document.Document.Companion.fromBytes]).
 * Ported from yorkie-js-sdk `doc-store.ts` (`2291bf67`/#1338); byte-oriented and suspending so a
 * durable backend (file, Room, DataStore) fits behind it. The SDK ships no persistent implementation
 * of its own — [MemoryDocStore] is process-local and dies with the process; a durable store is an
 * app concern. Keyed by an opaque store key the [Client] derives from the API key, client key, and
 * document key (see `Client.storeKey`) — this interface itself is unaware of that scheme.
 */
public interface DocStore {
    /**
     * Loads the bytes previously [save]d under [docKey], or null if nothing is stored.
     */
    public suspend fun load(docKey: String): ByteArray?

    /**
     * Persists [bytes] under [docKey], overwriting any previous value.
     */
    public suspend fun save(docKey: String, bytes: ByteArray)

    /**
     * Removes any bytes stored under [docKey]. A no-op when nothing is stored.
     */
    public suspend fun remove(docKey: String)
}

/**
 * Default in-memory [DocStore], backed by a [ConcurrentHashMap]. Ported from yorkie-js-sdk
 * `MemoryDocStore` (`doc-store.ts`); matches iOS's memory-only default.
 *
 * [load] and [save] both take a defensive copy of the byte array at the store boundary. JS relies
 * on `Uint8Array.slice()` for this; Kotlin's `ByteArray` is a mutable, aliasable reference type (iOS
 * skipped the equivalent copy only because Swift's `Data` is a value type), so without the copy a
 * caller mutating an array after `save` — or mutating a `load` result — would corrupt the stored
 * envelope in place.
 */
public class MemoryDocStore : DocStore {
    private val store = ConcurrentHashMap<String, ByteArray>()

    override suspend fun load(docKey: String): ByteArray? = store[docKey]?.copyOf()

    override suspend fun save(docKey: String, bytes: ByteArray) {
        store[docKey] = bytes.copyOf()
    }

    override suspend fun remove(docKey: String) {
        store.remove(docKey)
    }
}
