package dev.yorkie.core

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Direct pins for [AuthTokenRefresher.tokenSuspend] -- the SAME gate as
 * [AuthTokenRefresher.token] (see its own class KDoc), but for a caller already inside a
 * suspend context ([dev.yorkie.core.Client.deactivateAsync]'s retry-before-teardown probe).
 * Unlike [AuthTokenRefresherTest] (pure-unit, no coroutines, by design -- its own class KDoc),
 * these need `runTest`/suspend to park a call genuinely in-flight.
 */
class AuthTokenRefresherSuspendTest {

    private var now = 0L
    private var jitter = 0.0 // 0.0 -> the minimum jitter multiplier (1 - Jitter = 0.8)

    private fun refresher() = AuthTokenRefresher(clock = { now }, random = { jitter })

    @Test
    fun `tokenSuspend - a loop request inside the window reuses the cached token`() = runTest {
        val refresher = refresher()
        var injectorCalls = 0

        assertEquals(
            "token-1",
            refresher.tokenSuspend(isLoopRequest = true) {
                injectorCalls++
                "token-1"
            },
        )
        assertEquals(1, injectorCalls)

        refresher.tokenSuspend(isLoopRequest = true) {
            injectorCalls++
            throw IllegalStateException("arm the window")
        }
        assertEquals(2, injectorCalls, "outside any window the injector is still called")

        // Now inside the backoff window: a loop request must NOT call the injector again.
        val held = refresher.tokenSuspend(isLoopRequest = true) {
            injectorCalls++
            "should-not-be-returned"
        }
        assertEquals(2, injectorCalls, "a loop request inside the window skips the injector")
        assertEquals("token-1", held, "the loop reuses the last successful token")
    }

    @Test
    fun `tokenSuspend - an earlier reservation settling late does not overwrite a newer success`() =
        runTest {
            val refresher = refresher()
            val aReserved = CompletableDeferred<Unit>()
            val aMayProceed = CompletableDeferred<Unit>()
            val deferredA = async {
                refresher.tokenSuspend(isLoopRequest = true) {
                    aReserved.complete(Unit)
                    aMayProceed.await() // held until B has already succeeded
                    "token-A-stale"
                }
            }
            aReserved.await()

            // B reserves AFTER A (its generation is newer) and succeeds immediately.
            assertEquals(
                "token-B-fresh",
                refresher.tokenSuspend(isLoopRequest = true) { "token-B-fresh" },
            )

            aMayProceed.complete(Unit)
            deferredA.await()

            // A's now-stale success must not have overwritten B's newer token -- force a
            // window via one failure, then read the cache through a loop request inside it.
            refresher.tokenSuspend(isLoopRequest = true) {
                throw IllegalStateException("force a window")
            }
            assertEquals(
                "token-B-fresh",
                refresher.tokenSuspend(isLoopRequest = true) { "unused-should-not-be-called" },
            )
        }

    @Test
    fun `tokenSuspend - a CancellationException is rethrown and does not arm the backoff`() =
        runTest {
            val refresher = refresher()

            assertFailsWith<CancellationException> {
                refresher.tokenSuspend(isLoopRequest = true) {
                    throw CancellationException("cancelled")
                }
            }

            // No backoff armed by the cancellation: `loopRetryDelayMs` reads 0, not some
            // tier of `authRefreshDelay`.
            assertEquals(
                0L,
                refresher.loopRetryDelayMs(0L, is401 = true),
                "a cancelled call must not arm the backoff",
            )

            // A fresh loop request right after is NOT held behind a phantom window either --
            // it calls the injector, same as if the cancelled call had never happened.
            var injectorCalls = 0
            val token = refresher.tokenSuspend(isLoopRequest = true) {
                injectorCalls++
                "token-after-cancel"
            }
            assertEquals(1, injectorCalls)
            assertEquals("token-after-cancel", token)
        }
}
