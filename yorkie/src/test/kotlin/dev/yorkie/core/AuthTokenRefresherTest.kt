package dev.yorkie.core

import com.connectrpc.ConnectException
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Pure-unit pins for [AuthTokenRefresher] against a fake monotonic clock and a deterministic
 * jitter source -- no coroutines, no Android types. Case numbers reference
 * yorkie-js-sdk `test/unit/client/auth_refresh_test.ts` @ `0306c4b5` (#1513), ported onto
 * Android's per-request gate. Cases 5/8/9/10/11/13/14 are loop/Client-level and ported
 * instead in [AuthRefreshTest].
 */
class AuthTokenRefresherTest {

    private var now = 0L
    private var jitter = 0.0 // 0.0 -> the minimum jitter multiplier (1 - Jitter = 0.8)

    private fun refresher() = AuthTokenRefresher(clock = { now }, random = { jitter })

    // ========== JS case 1: doubling 1/2/4s, reset on success ==========

    @Test
    fun `case1 - consecutive failures back off 1s then 2s then 4s, jitter at the minimum`() {
        val refresher = refresher()

        refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
        // 1st failure: base 1000 * 0.8 = 800ms.
        assertEquals(
            800L,
            refresher.loopRetryDelayMs(0L, is401 = true),
            "1st failure backs off 1s * 0.8 jitter",
        )

        now = 800L
        refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
        assertEquals(
            1_600L,
            refresher.loopRetryDelayMs(0L, is401 = true),
            "2nd failure backs off 2s * 0.8 jitter",
        )

        now = 800L + 1_600L
        refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
        assertEquals(
            3_200L,
            refresher.loopRetryDelayMs(0L, is401 = true),
            "3rd failure backs off 4s * 0.8 jitter",
        )

        // A success resets the backoff: a subsequent failure starts from 1s again.
        now += 10_000L
        refresher.token(isLoopRequest = true) { "fresh-token" }
        assertEquals(0L, refresher.loopRetryDelayMs(0L, is401 = true), "reset after success")

        refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
        assertEquals(
            800L,
            refresher.loopRetryDelayMs(0L, is401 = true),
            "backoff restarts at 1s after a success",
        )
    }

    // ========== JS case 2: 30s cap ==========

    @Test
    fun `case2 - backoff never exceeds the 30s cap however many failures accrue`() {
        val refresher = refresher()
        jitter = 0.999_999 // maximum jitter multiplier (1 + Jitter ~= 1.2)

        // `loopRetryDelayMs` floors at 5s (a SEPARATE cap, for a different purpose) -- read
        // the raw backoff via `untilRetryMs()` instead to pin `authRefreshDelay`'s own 30s
        // cap directly. Jump by a fixed 35s (more than the 30s cap) each time, captured
        // BEFORE the jump: the jump itself pushes `now` past the just-armed `retryAt`, which
        // would otherwise read back as already-elapsed rather than the backoff just set.
        var lastDelay = 0L
        repeat(10) {
            refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
            lastDelay = refresher.untilRetryMs()
            now += 35_000L
        }
        assertEquals(
            30_000L,
            lastDelay,
            "many consecutive failures cap at 30s even with the maximum jitter",
        )
    }

    // ========== JS case 3: concurrent 401s count once ==========

    @Test
    fun `case3 - two concurrent loop failures reserving before either settles count once`() {
        val refresher = refresher()
        // `token()` reserves under the lock, then releases it BEFORE calling the injector --
        // a `CyclicBarrier` inside both injector calls proves both reservations already
        // happened (generation 1 and 2) before either call is allowed to fail.
        val barrier = CyclicBarrier(2)
        val threads = List(2) { idx ->
            Thread {
                refresher.token(isLoopRequest = true) {
                    barrier.await(2, TimeUnit.SECONDS)
                    throw IllegalStateException("call-$idx")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2_000) }

        // Two failures counted as ONE backs off 1s (800ms w/ min jitter); two counted
        // separately would already be at the 2nd-failure 2s (1600ms) tier.
        assertEquals(
            800L,
            refresher.loopRetryDelayMs(0L, is401 = true),
            "two failures that raced the SAME reservation window count once",
        )
    }

    // ========== JS case 4: a later 401 is not held by a never-settling call ==========

    @Test
    fun `case4 - a later one-shot call is not blocked by an earlier call that never returns`() {
        val refresher = refresher()
        val hangingReserved = CountDownLatch(1)
        val hangingThread = Thread {
            refresher.token(isLoopRequest = false) {
                hangingReserved.countDown()
                // Models an injector call that never settles -- bounded, not
                // `CountDownLatch(1).await()` with no timeout, so this daemon thread
                // self-terminates instead of leaking for the rest of the test JVM's life
                // (shared across every test class the Gradle worker runs in this process).
                Thread.sleep(5_000)
                "unreachable"
            }
        }
        hangingThread.isDaemon = true
        hangingThread.start()
        assertTrue(hangingReserved.await(2, TimeUnit.SECONDS))

        // The later call must reserve and fail on its own -- never blocked by the thread
        // still parked inside the first call's injector.
        val error = assertFailsWith<AuthTokenInjectorException> {
            refresher.token(isLoopRequest = false) { throw IllegalStateException("later call") }
        }
        assertEquals("later call", error.cause?.message)
    }

    // ========== JS case 6: a stale settle (earlier call, later success) is ignored ==========

    @Test
    fun `case6 - an earlier reservation settling late does not overwrite a newer success`() {
        val refresher = refresher()
        val aReserved = CountDownLatch(1)
        val aMayProceed = CountDownLatch(1)
        val threadA = Thread {
            refresher.token(isLoopRequest = true) {
                aReserved.countDown()
                aMayProceed.await() // held until B has already succeeded
                "token-A-stale"
            }
        }
        threadA.isDaemon = true
        threadA.start()
        assertTrue(aReserved.await(2, TimeUnit.SECONDS))

        // B reserves AFTER A (its generation is newer) and succeeds immediately.
        assertEquals("token-B-fresh", refresher.token(isLoopRequest = true) { "token-B-fresh" })

        aMayProceed.countDown()
        threadA.join(2_000)

        // A's now-stale success must not have overwritten B's newer token -- force a
        // window via one failure, then read the cache through a loop request inside it.
        refresher.token(isLoopRequest = true) { throw IllegalStateException("force a window") }
        assertEquals(
            "token-B-fresh",
            refresher.token(isLoopRequest = true) { "unused-should-not-be-called" },
        )
    }

    // ========== JS case 7: token set while a later call is pending ==========

    @Test
    fun `case7 - a successful call's token is cached immediately while another is pending`() {
        val refresher = refresher()
        val bReserved = CountDownLatch(1)
        val threadB = Thread {
            refresher.token(isLoopRequest = true) {
                bReserved.countDown()
                // Bounded, not an un-timed-out latch -- see case4's comment above.
                Thread.sleep(5_000)
                "token-B"
            }
        }
        threadB.isDaemon = true
        threadB.start()
        assertTrue(bReserved.await(2, TimeUnit.SECONDS))

        assertEquals("token-A", refresher.token(isLoopRequest = true) { "token-A" })

        // A's success must be visible right away, not held back by B's still-pending call --
        // force a window via one failure, then read the cache through a loop request inside it.
        refresher.token(isLoopRequest = true) { throw IllegalStateException("force a window") }
        assertEquals(
            "token-A",
            refresher.token(isLoopRequest = true) { "unused-should-not-be-called" },
        )
    }

    // ========== JS case 12: activation drops a pending call's result ==========

    // The previous version of this test called `token()` and `onActivated()` strictly in
    // sequence on one thread -- nothing was ever actually in-flight when activation ran, so
    // it could not tell "activation correctly ignores a pending call's late result" apart
    // from "no pending call ever existed to ignore".
    // Reconstructed as genuine concurrency, the same `Thread` + `CountDownLatch` idiom as
    // case6/case7 above: a call reserves its generation and PARKS inside the injector,
    // `onActivated()` runs while it is still parked, then the call is released to settle.

    @Test
    fun `case12 - onActivated makes a genuinely in-flight call's late SUCCESS stale`() {
        val refresher = refresher()
        val reserved = CountDownLatch(1)
        val mayProceed = CountDownLatch(1)
        val staleThread = Thread {
            refresher.token(isLoopRequest = true) {
                reserved.countDown()
                mayProceed.await() // held until onActivated() has already run
                "late-token-should-not-be-cached"
            }
        }
        staleThread.isDaemon = true
        staleThread.start()
        assertTrue(reserved.await(2, TimeUnit.SECONDS), "the call must genuinely be in-flight")

        refresher.onActivated()

        mayProceed.countDown()
        staleThread.join(2_000)

        // The late success must not have been cached: force a window via one failure, then
        // read the cache through a loop request inside it -- null (no token ever succeeded
        // since activation), never the stale thread's value.
        refresher.token(isLoopRequest = true) { throw IllegalStateException("force a window") }
        assertNull(
            refresher.token(isLoopRequest = true) { "unused-should-not-be-called" },
            "activation must drop the in-flight call's late success, not cache it",
        )
    }

    @Test
    fun `case12 - onActivated makes a genuinely in-flight call's late FAILURE not arm backoff`() {
        val refresher = refresher()
        val reserved = CountDownLatch(1)
        val mayProceed = CountDownLatch(1)
        val staleThread = Thread {
            refresher.token(isLoopRequest = true) {
                reserved.countDown()
                mayProceed.await() // held until onActivated() has already run
                throw IllegalStateException("late failure should not arm backoff")
            }
        }
        staleThread.isDaemon = true
        staleThread.start()
        assertTrue(reserved.await(2, TimeUnit.SECONDS), "the call must genuinely be in-flight")

        refresher.onActivated()

        mayProceed.countDown()
        staleThread.join(2_000)

        // The late failure must not have armed the backoff activation just cleared: a FRESH
        // failure after activation must start the backoff at the first tier again, not a
        // tier implying the stale failure (or two failures) already counted.
        assertEquals(
            0L,
            refresher.loopRetryDelayMs(0L, is401 = true),
            "activation must drop the in-flight call's late failure, not arm the backoff",
        )
        refresher.token(isLoopRequest = true) { throw IllegalStateException("post-activate") }
        assertEquals(800L, refresher.loopRetryDelayMs(0L, is401 = true))
    }

    // ========== loop vs one-shot: the core gate ==========

    @Test
    fun `a loop request inside the window reuses the cached token without calling the injector`() {
        val refresher = refresher()
        var injectorCalls = 0

        assertEquals(
            "token-1",
            refresher.token(isLoopRequest = true) {
                injectorCalls++
                "token-1"
            },
        )
        assertEquals(1, injectorCalls)

        refresher.token(isLoopRequest = true) {
            injectorCalls++
            throw IllegalStateException()
        }
        assertEquals(2, injectorCalls, "outside any window the injector is still called")

        // Now inside the backoff window: a loop request must NOT call the injector again.
        val held = refresher.token(isLoopRequest = true) {
            injectorCalls++
            "should-not-be-returned"
        }
        assertEquals(2, injectorCalls, "a loop request inside the window skips the injector")
        assertEquals("token-1", held, "the loop reuses the last successful token")
    }

    @Test
    fun `a loop request before any token ever succeeded returns null, never throws`() {
        val refresher = refresher()
        val token = refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
        assertNull(token, "no header is sent rather than surfacing the injector's throw")
    }

    @Test
    fun `a one-shot request throws AuthTokenInjectorException wrapping the injector's error`() {
        val refresher = refresher()
        val original = IllegalStateException("token endpoint down")
        val thrown = assertFailsWith<AuthTokenInjectorException> {
            refresher.token(isLoopRequest = false) { throw original }
        }
        assertEquals(original, thrown.cause)
    }

    @Test
    fun `a one-shot request calls the injector even inside another call's backoff window`() {
        val refresher = refresher()
        refresher.token(isLoopRequest = true) { throw IllegalStateException("first") }
        assertTrue(refresher.loopRetryDelayMs(0L, is401 = true) > 0L)

        var oneShotCalled = false
        assertFailsWith<AuthTokenInjectorException> {
            refresher.token(isLoopRequest = false) {
                oneShotCalled = true
                throw IllegalStateException("one-shot")
            }
        }
        assertTrue(oneShotCalled, "a one-shot call ignores the backoff window entirely")
    }

    // ========== loopRetryDelayMs (JS case 5's pure half: `loopRetryDelay`) ==========

    @Test
    fun `loopRetryDelayMs leaves a non-401 delay untouched`() {
        val refresher = refresher()
        refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
        assertEquals(1_234L, refresher.loopRetryDelayMs(1_234L, is401 = false))
    }

    @Test
    fun `loopRetryDelayMs floors a 401 delay at min(retryAt - now, 5s)`() {
        val refresher = refresher()
        jitter = 0.999_999
        refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
        // 1st failure with near-max jitter: 1000 * ~1.1999996, truncated to whole ms.
        assertEquals(1_199L, refresher.loopRetryDelayMs(0L, is401 = true))
        // A larger base delay is never shortened by the floor.
        assertEquals(5_000L, refresher.loopRetryDelayMs(5_000L, is401 = true))
    }

    @Test
    fun `loopRetryDelayMs caps the 401 floor at 5 seconds regardless of a longer backoff`() {
        val refresher = refresher()
        jitter = 0.999_999
        // A 35s jump clears even the largest possible single-tier backoff (capped at 30s),
        // so every repeat is a genuine NEW failure; both values are captured BEFORE that
        // jump -- the jump itself pushes `now` past the just-armed `retryAt`, which would
        // otherwise read back as already-elapsed rather than the backoff just set.
        var lastUntilRetry = 0L
        var lastLoopDelay = 0L
        repeat(6) {
            refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
            lastUntilRetry = refresher.untilRetryMs()
            lastLoopDelay = refresher.loopRetryDelayMs(0L, is401 = true)
            now += 35_000L
        }
        // The raw backoff genuinely exceeds the 5s floor by this point -- otherwise the
        // assertion below would hold trivially regardless of whether the floor works at all.
        assertTrue(lastUntilRetry > 5_000L, "test setup: backoff must exceed 5s here")
        assertEquals(
            5_000L,
            lastLoopDelay,
            "the loop-wait floor never exceeds AuthTokenRefresher.LoopRetryMaxWaitMs",
        )
    }

    // ========== probe (v): jitter at both extremes never exceeds 30s ==========

    @Test
    fun `probe v - jitter at either extreme never pushes the delay past 30s`() {
        for (extreme in listOf(0.0, 0.999_999)) {
            var clockNow = 0L
            val refresher = AuthTokenRefresher(clock = { clockNow }, random = { extreme })
            // A flat 35s jump clears even the largest possible single-tier backoff (capped
            // at 30s), so every repeat is a genuine NEW failure, not a cached short-circuit.
            // `untilRetryMs()` reads the RAW backoff -- `loopRetryDelayMs` floors at a
            // separate 5s cap and so cannot observe this 30s cap on its own (see case 2).
            var lastDelay = 0L
            repeat(12) {
                refresher.token(isLoopRequest = true) { throw IllegalStateException("boom") }
                lastDelay = refresher.untilRetryMs()
                clockNow += 35_000L
            }
            assertTrue(
                lastDelay <= 30_000L,
                "jitter=$extreme must never push the backoff past the 30s cap",
            )
        }
    }

    // ========== unwrapInjectorError ==========

    @Test
    fun `unwrapInjectorError returns the injector's own cause from a direct wrap`() {
        val original = IllegalStateException("token endpoint down")
        val wrapped = AuthTokenInjectorException(original)
        assertEquals(original, wrapped.unwrapInjectorError())
    }

    @Test
    fun `unwrapInjectorError walks a connect-kotlin style wrapper to find the injector cause`() {
        val original = IllegalStateException("token endpoint down")
        val injectorException = AuthTokenInjectorException(original)
        // Models connect-kotlin's ConnectException(Code.UNKNOWN, exception = injectorException).
        val transportWrapper = RuntimeException("UNKNOWN", injectorException)
        assertEquals(original, transportWrapper.unwrapInjectorError())
    }

    @Test
    fun `unwrapInjectorError returns the exception unchanged when it is not an injector throw`() {
        val genuine = IllegalStateException("server said 401")
        assertEquals(genuine, genuine.unwrapInjectorError())
    }

    @Test(timeout = 5_000)
    fun `unwrapInjectorError terminates on a cyclic cause chain instead of looping forever`() {
        // A real Throwable's `cause` can only be set once (via its constructor or
        // `initCause`), so a genuine cycle needs a mock: `a.cause` returns `b`, `b.cause`
        // returns `a` right back.
        val a: Throwable = mockk(relaxed = true)
        val b: Throwable = mockk(relaxed = true)
        every { a.cause } returns b
        every { b.cause } returns a
        assertEquals(a, a.unwrapInjectorError(), "no AuthTokenInjectorException in the cycle")
    }

    @Test(timeout = 5_000)
    fun `unwrapInjectorError terminates on a never-null cause chain, like a relaxed mock`() {
        // The production shape this regresses: MockYorkieService's 401 branches build their
        // ConnectException as `mockk<ConnectException>(relaxed = true)` with `cause` left
        // unstubbed -- a relaxed mock then fabricates a FRESH non-null child mock on every
        // `.cause` read, so the chain never reaches a null terminator on its own.
        val neverEndingChain = mockk<ConnectException>(relaxed = true)
        assertEquals(
            neverEndingChain,
            neverEndingChain.unwrapInjectorError(),
            "no AuthTokenInjectorException anywhere in the (unbounded) chain",
        )
    }
}
