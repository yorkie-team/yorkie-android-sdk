package dev.yorkie.core

import java.util.concurrent.ConcurrentHashMap

/**
 * One persisted local change, tagged with the [clientSeq] it carries. Ported from
 * yorkie-js-sdk `doc-store.ts` (`aaa5cb15`/#1354). The sequence is what lets a store drop
 * changes a sync has already acknowledged, and what lets a restore detect a hole rather than
 * replaying a discontinuous run.
 *
 * A plain class, not a `data class` — a public api-compat holder whose equality is not part
 * of the contract (api-compat rule).
 */
public class StoredChange(public val clientSeq: UInt, public val bytes: ByteArray)

/**
 * Everything a backend holds for one document: a base snapshot, the changes appended since
 * it, and a small mutable header. Ported from yorkie-js-sdk `doc-store.ts` (`aaa5cb15`/#1354).
 *
 * A plain class, not a `data class`, for the same api-compat reason as [StoredChange].
 *
 * @param snapshot a [dev.yorkie.document.Document.toBytes] envelope.
 * @param meta checkpoint and changeID as of the last sync. Absent until the first one. Held
 * apart from [snapshot] because a sync has to advance it constantly while the snapshot stays
 * put.
 * @param changes changes appended since [snapshot], ascending by [StoredChange.clientSeq].
 */
public class StoredDoc(
    public val snapshot: ByteArray,
    public val meta: ByteArray? = null,
    public val changes: List<StoredChange> = emptyList(),
)

/**
 * A byte-oriented, async storage seam for a [dev.yorkie.document.Document]'s persisted state.
 * Ported from yorkie-js-sdk `doc-store.ts` (`aaa5cb15`/#1354, RTCOLLABPLATFORM-779), replacing
 * the single-blob shape from `2291bf67`/#1338.
 *
 * It is deliberately a snapshot plus an append-only change log rather than one opaque blob.
 * Re-serializing the whole document on every edit costs time proportional to the document —
 * hundreds of milliseconds on a large one — and a document is at its largest while it is
 * being edited, which is exactly when the writes happen. Appending costs the size of one
 * change, which does not grow with the document at all.
 *
 * The interface stays byte-oriented and suspending so a durable backend (file, Room,
 * DataStore) fits behind it without the client knowing which storage it talks to, and so a
 * backend is free to compress or encrypt what it is handed. The SDK ships no persistent
 * implementation of its own — [MemoryDocStore] is process-local and dies with the process; a
 * durable store is an app concern. Keyed by an opaque store key the [Client] derives from the
 * API key, client key, and document key (see `Client.storeKey`) — this interface itself is
 * unaware of that scheme.
 *
 * This shape breaks the previous one (`save` is gone) with no deprecation shim: `DocStore`
 * exists only on the unmerged 771/772/779 stack (unreleased API), so there is nothing
 * released to preserve compatibility with — mirrors iOS's own unshimmed break at `4213eecc67`.
 */
public interface DocStore {
    /**
     * Returns the persisted state for [docKey], or null when nothing has been stored for it.
     * [StoredDoc.changes] is ordered by ascending [StoredChange.clientSeq].
     */
    public suspend fun load(docKey: String): StoredDoc?

    /**
     * Replaces the snapshot and atomically drops every appended change **and any stored
     * meta**. This is compaction: the new snapshot already contains those changes, so keeping
     * them would replay them twice, and it embeds a newer header than meta holds — a snapshot
     * envelope embeds its own checkpoint and changeID, newer than whatever meta held; applying
     * the old header over the new snapshot would regress `serverSeq` and — worse —
     * `lamport`, whose regression makes the next edit mint tickets that collide with
     * identities already in the restored root.
     */
    public suspend fun saveSnapshot(docKey: String, bytes: ByteArray)

    /**
     * Appends one local change. This is the hot path — frequent and small — so an
     * implementation must not rewrite the whole entry to satisfy it.
     *
     * It is an **upsert keyed by [StoredChange.clientSeq]**: re-appending a change already
     * stored replaces it rather than duplicating it, so a retried write is safe. It is a
     * silent no-op on a key with no entry — a row written anyway is an orphan [load] cannot
     * see.
     */
    public suspend fun appendChange(docKey: String, change: StoredChange)

    /**
     * Records the post-sync header. It leaves the snapshot alone — an online client syncs
     * constantly, and re-snapshotting per sync would reintroduce the cost this interface
     * exists to avoid — and it leaves the **log** alone too.
     *
     * That second part is load-bearing. The log does two jobs: it holds un-pushed changes so
     * they survive a reload, and it is the delta between the snapshot and the document's
     * current content. Deleting acknowledged entries serves the first job and destroys the
     * second, because nothing brings the snapshot forward on a push-ack — the content would
     * then exist in neither place while the persisted `serverSeq` claims the server has it.
     * Only compaction trims the log, and it does so by folding the entries into a new
     * snapshot first.
     *
     * It is a no-op when nothing is stored for [docKey].
     */
    public suspend fun saveMeta(docKey: String, bytes: ByteArray)

    /**
     * Deletes everything persisted for [docKey]. A no-op when nothing is stored.
     */
    public suspend fun remove(docKey: String)
}

/**
 * Default in-memory [DocStore], backed by a [ConcurrentHashMap]. Ported from yorkie-js-sdk
 * `MemoryDocStore` (`doc-store.ts`); matches iOS's memory-only default.
 *
 * Every [ByteArray] is copied on the way in and on the way out, so neither a caller mutating
 * what it wrote nor one mutating what it read can corrupt the stored entry. JS relies on
 * `Uint8Array.slice()` for this; Kotlin's `ByteArray` is a mutable, aliasable reference type
 * (iOS skipped the equivalent copy only because Swift's `Data` is a value type), so without
 * the copy a caller mutating an array after a write — or mutating a `load` result — would
 * corrupt the stored envelope in place.
 *
 * The mutating no-op-on-absent operations ([appendChange], [saveMeta]) use
 * [ConcurrentHashMap.computeIfPresent], which recomputes a key's value atomically under the
 * map's own per-key locking — this is what keeps two coroutines calling [appendChange]
 * concurrently from losing an entry, without an explicit external lock.
 */
public class MemoryDocStore : DocStore {
    private val store = ConcurrentHashMap<String, StoredDoc>()

    override suspend fun load(docKey: String): StoredDoc? {
        val entry = store[docKey] ?: return null
        return StoredDoc(
            snapshot = entry.snapshot.copyOf(),
            meta = entry.meta?.copyOf(),
            changes = entry.changes.map { StoredChange(it.clientSeq, it.bytes.copyOf()) },
        )
    }

    override suspend fun saveSnapshot(docKey: String, bytes: ByteArray) {
        store[docKey] = StoredDoc(snapshot = bytes.copyOf(), meta = null, changes = emptyList())
    }

    override suspend fun appendChange(docKey: String, change: StoredChange) {
        store.computeIfPresent(docKey) { _, entry ->
            val stored = StoredChange(change.clientSeq, change.bytes.copyOf())
            val existingIndex = entry.changes.indexOfFirst { it.clientSeq == change.clientSeq }
            val nextChanges = if (existingIndex >= 0) {
                entry.changes.toMutableList().apply { this[existingIndex] = stored }
            } else {
                (entry.changes + stored).sortedBy { it.clientSeq }
            }
            StoredDoc(entry.snapshot, entry.meta, nextChanges)
        }
    }

    override suspend fun saveMeta(docKey: String, bytes: ByteArray) {
        store.computeIfPresent(docKey) { _, entry ->
            StoredDoc(entry.snapshot, bytes.copyOf(), entry.changes)
        }
    }

    override suspend fun remove(docKey: String) {
        store.remove(docKey)
    }
}
