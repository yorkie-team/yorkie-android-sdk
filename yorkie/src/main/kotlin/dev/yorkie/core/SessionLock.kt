package dev.yorkie.core

/**
 * A handle to a held [SessionLock], returned by a successful [SessionLock.acquire]. Ported from
 * yorkie-js-sdk `session-lock.ts` (`2291bf67`/#1338).
 *
 * [release] is non-suspending and idempotent so it can run from a non-cancellable teardown path
 * (`Client.detachInternal`, including the keepalive `NonCancellable`/`GlobalScope` variants) and be
 * called more than once (e.g. once from a failed-attach rollback and once from the eventual
 * `detachInternal`) without effect beyond the first call.
 */
public interface SessionLockHandle {
    /**
     * Releases this lease. Idempotent: calling this more than once has no additional effect.
     */
    public fun release()
}

/**
 * A single-active-session guard for a [DocStore]-backed document. Ported from yorkie-js-sdk
 * `session-lock.ts` (`2291bf67`/#1338). Two sessions concurrently resuming the same persisted
 * document under the same stable actor would mint colliding `clientSeq` values and silently lose
 * edits, so [Client] takes a lease named after the document before restoring its persisted envelope.
 *
 * [acquire] must not block: contention is reported by returning null (fail fast) rather than
 * suspending until the lock frees up, so a contended attach fails immediately instead of hanging.
 */
public interface SessionLock {
    /**
     * Attempts to acquire the lock identified by [name]. Returns a [SessionLockHandle] on success,
     * or null if [name] is already held elsewhere.
     */
    public suspend fun acquire(name: String): SessionLockHandle?
}

/**
 * Default [SessionLock]: grants every [acquire] unconditionally. Ported from the non-browser branch
 * of yorkie-js-sdk's `WebLocksSessionLock` (a no-op outside a browser) and matching iOS's own
 * `NoopSessionLock` default.
 *
 * This is the [Client.Options.sessionLock] default because, unlike a browser with multiple tabs,
 * an Android app is one process per [DocStore] by default — the hazard [SessionLock] guards against
 * (two sessions resuming the same document) is reachable only when a persistent [DocStore] is shared
 * across processes (e.g. an app and a widget or background service). Callers with that shape should
 * supply a lock implementation appropriate to their store, e.g. one built on `FileChannel.tryLock`.
 * No `WebLocksSessionLock` port — Android has no browser tab concept to guard.
 */
public object NoopSessionLock : SessionLock {
    override suspend fun acquire(name: String): SessionLockHandle = object : SessionLockHandle {
        override fun release() {}
    }
}
