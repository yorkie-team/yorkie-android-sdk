package dev.yorkie.core

import kotlinx.coroutines.CancellationException

/**
 * Thrown by [AuthTokenRefresher.token] when a ONE-SHOT request's injector call rejects.
 * [cause] is the injector's own exception. connect-kotlin wraps a `requestFunction` throw
 * from a unary interceptor into `ConnectException(Code.UNKNOWN, exception = this)`
 * (measured by reading `com.connectrpc.impl.ProtocolClient.unary`'s `catch (ex: Exception)`
 * block and `com.connectrpc.ConnectException.kt:99`'s `asConnectException`, connect-kotlin
 * 0.7.0 sources) -- [unwrapInjectorError] walks a caught exception's cause chain for this
 * type and returns ITS cause, so a one-shot caller (`syncAsync`, `activate`, `attach`,
 * `detach`, `remove`, `peekChannel`, the first `broadcast` try, `deactivate`'s own refresh)
 * surfaces the injector's own error, not the connect-kotlin wrapper.
 *
 * A stream interceptor throw (document/channel Watch) is never wrapped this way --
 * `ProtocolClient.stream`'s `streamFunc.requestFunction(request)` call sits OUTSIDE any
 * try/catch -- which is exactly why Watch must always be called as a LOOP request
 * ([AuthTokenRefresher.token]'s `isLoopRequest = true`): a loop request never throws.
 */
internal class AuthTokenInjectorException(cause: Throwable) : Exception(cause)

/**
 * Walks [this] exception's cause chain for an [AuthTokenInjectorException] and returns ITS
 * cause -- the injector's own error -- or [this] unchanged if none is found (a genuine
 * server/transport failure, not an injector rejection). Bounded at
 * [MAX_CAUSE_CHAIN_DEPTH] hops -- the real chain is only 2 deep (a transport wrapper, then
 * [AuthTokenInjectorException] itself) -- so neither a cyclic cause chain nor an exception
 * whose `cause` never settles to null (a relaxed mock fabricates a fresh child on every
 * read) can turn this into an unbounded loop.
 */
internal fun Throwable.unwrapInjectorError(): Throwable {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
        if (current is AuthTokenInjectorException) {
            return current.cause ?: current
        }
        current = current.cause
        depth += 1
    }
    return this
}

private const val MAX_CAUSE_CHAIN_DEPTH = 16

/**
 * Ports yorkie-js-sdk `client.ts`'s `authRefreshFailures`/`authRefreshRetryAt`/
 * `authRefreshGeneration`/`authTokenGeneration` and `refreshAuthToken`/`loopRetryDelay`
 * (`0306c4b5`, #1513) onto Android's per-request `Options.fetchAuthToken` interceptor model.
 *
 * Pure JVM: no Android types. [token] calls [callInjector] synchronously -- the interceptor
 * caller wraps its own suspending injector in `runBlocking` itself, since the interceptor
 * runs on connect's own IO dispatcher, not the client's single-thread dispatcher. [tokenSuspend]
 * is the same gate for a caller that is already inside a suspend context on the CLIENT's
 * single-thread dispatcher (deactivate's retry-before-teardown probe): it awaits the injector
 * directly instead of blocking that thread with `runBlocking`. Neither [token] nor
 * [loopRetryDelayMs] ever sleeps or suspends on its own -- only the caller delays -- since a
 * `delay` here would stall whichever dispatcher the caller runs on.
 *
 * State ([failures], [retryAt], [generation], [tokenGeneration], [lastToken]), one lock:
 * - a LOOP request (sync loop, Watch, a broadcast retry) inside the backoff window
 *   (`now < retryAt`) reuses [lastToken] (possibly null) WITHOUT calling the injector;
 * - otherwise it reserves `gen = ++generation` under the lock, releases the lock, then calls
 *   the injector -- a never-settling call must never hold a LATER request (JS cases 3/4);
 * - a success is cached only if `gen > tokenGeneration` (a stale settle never overwrites a
 *   newer token -- JS cases 6/7) and resets the backoff;
 * - a rejection counts once per window (`gen > tokenGeneration && failures` unchanged since
 *   the reservation -- concurrent 401s count once, JS case 3) and sets
 *   `retryAt = now + authRefreshDelay(failures)`;
 * - a LOOP rejection returns the cached token (never throws); a ONE-SHOT rejection throws
 *   [AuthTokenInjectorException].
 */
internal class AuthTokenRefresher(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: () -> Double = Math::random,
) {
    private val lock = Any()

    private var failures = 0
    private var retryAt = 0L
    private var generation = 0
    private var tokenGeneration = 0
    private var lastToken: String? = null

    /**
     * Returns the header value for this request, or null for "send no auth header".
     * [isLoopRequest] selects the gate (see class doc); [callInjector] is the (blocking)
     * call to `Options.fetchAuthToken`.
     */
    fun token(isLoopRequest: Boolean, callInjector: () -> String): String? {
        val reservation = reserve(isLoopRequest) ?: return synchronized(lock) { lastToken }
        val (gen, failuresAtReservation) = reservation
        val result = runCatching(callInjector)
        return result.fold(
            onSuccess = { newToken -> onInjectorSuccess(gen, newToken) },
            onFailure = { error ->
                onInjectorFailure(
                    gen,
                    failuresAtReservation,
                    isLoopRequest,
                    error,
                )
            },
        )
    }

    /**
     * The same gate as [token], for a caller already inside a suspend context (see class
     * doc) -- [callInjector] is awaited directly instead of being wrapped in `runBlocking`.
     */
    suspend fun tokenSuspend(isLoopRequest: Boolean, callInjector: suspend () -> String): String? {
        val reservation = reserve(isLoopRequest) ?: return synchronized(lock) { lastToken }
        val (gen, failuresAtReservation) = reservation
        return try {
            onInjectorSuccess(gen, callInjector())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onInjectorFailure(gen, failuresAtReservation, isLoopRequest, e)
        }
    }

    /**
     * Reserves a generation for a call to the injector, or returns null when
     * [isLoopRequest] is inside the backoff window (the caller then reuses [lastToken]
     * as-is). Released from the lock immediately -- a never-settling call must never hold a
     * LATER request (JS cases 3/4).
     */
    private fun reserve(isLoopRequest: Boolean): Pair<Int, Int>? = synchronized(lock) {
        if (isLoopRequest && clock() < retryAt) {
            null
        } else {
            generation += 1
            generation to failures
        }
    }

    /**
     * A success is cached only if `gen > tokenGeneration` -- a stale settle never overwrites
     * a newer token (JS cases 6/7) -- and resets the backoff.
     */
    private fun onInjectorSuccess(gen: Int, newToken: String): String {
        synchronized(lock) {
            if (gen > tokenGeneration) {
                tokenGeneration = gen
                lastToken = newToken
                failures = 0
                retryAt = 0L
            }
        }
        return newToken
    }

    /**
     * A rejection counts once per window (`gen > tokenGeneration && failures` unchanged
     * since the reservation -- concurrent 401s count once, JS case 3) and sets
     * `retryAt = now + authRefreshDelay(failures)`. A LOOP rejection returns the cached
     * token (never throws); a ONE-SHOT rejection throws [AuthTokenInjectorException].
     */
    private fun onInjectorFailure(
        gen: Int,
        failuresAtReservation: Int,
        isLoopRequest: Boolean,
        error: Throwable,
    ): String? {
        synchronized(lock) {
            if (gen > tokenGeneration && failures == failuresAtReservation) {
                failures += 1
                retryAt = clock() + authRefreshDelay(failures)
            }
        }
        if (isLoopRequest) {
            return synchronized(lock) { lastToken }
        }
        throw AuthTokenInjectorException(error)
    }

    /**
     * A successful activation resets the backoff and makes any still-in-flight refresh call
     * from before activation stale (JS case 12: `authTokenGeneration = ++authRefreshGeneration`
     * means a pending call's `gen` can never again exceed `tokenGeneration`).
     */
    fun onActivated() {
        synchronized(lock) {
            failures = 0
            retryAt = 0L
            generation += 1
            tokenGeneration = generation
        }
    }

    /**
     * JS `loopRetryDelay(err, delay)`: a non-401 loop failure waits exactly [baseDelayMs]; a
     * 401 additionally floors the wait at `min(retryAt - now, LOOP_RETRY_MAX_WAIT_MS)`, so a loop
     * never waits past the next injector retry by more than [LOOP_RETRY_MAX_WAIT_MS].
     */
    fun loopRetryDelayMs(baseDelayMs: Long, is401: Boolean): Long {
        if (!is401) {
            return baseDelayMs
        }
        return maxOf(baseDelayMs, untilRetryMs().coerceIn(0L, LOOP_RETRY_MAX_WAIT_MS))
    }

    /**
     * Test-only: the raw, UNCAPPED time remaining until the shared backoff clears (negative
     * once it has already elapsed). [loopRetryDelayMs] additionally applies
     * [LOOP_RETRY_MAX_WAIT_MS] on top of this for its own distinct purpose, so it cannot by
     * itself pin [authRefreshDelay]'s separate 30s cap (JS case 2) -- this is the seam that
     * can.
     */
    internal fun untilRetryMs(): Long = synchronized(lock) { retryAt } - clock()

    /** `min(1000 * 2^(failures-1), 30000) * (1 +/- 20%)`, capped again at 30000 after jitter. */
    private fun authRefreshDelay(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, 30)
        val base = minOf(INITIAL_BACKOFF_MS * (1L shl doublings), MAX_BACKOFF_MS)
        val jitter = 1.0 + JITTER * (random() * 2 - 1)
        return minOf((base * jitter).toLong(), MAX_BACKOFF_MS)
    }

    internal companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val JITTER = 0.2
        const val LOOP_RETRY_MAX_WAIT_MS = 5_000L
    }
}
