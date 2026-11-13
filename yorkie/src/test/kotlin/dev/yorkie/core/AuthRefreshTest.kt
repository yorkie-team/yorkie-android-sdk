package dev.yorkie.core

import android.util.Base64
import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchResponse
import dev.yorkie.api.v1.docEvent
import dev.yorkie.api.v1.docWatchEvent
import dev.yorkie.api.v1.watchEvent
import dev.yorkie.api.v1.watchResponse
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.document.Document
import dev.yorkie.presence.Channel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Loop-level pins for JS `0306c4b5`/#1513 through [MockYorkieService] -- the mock bypasses
 * [authInterceptor] entirely (see [AuthInterceptorSeamTest] for the
 * interceptor-to-[AuthTokenRefresher] seam), so these tests drive the 401 directly as the
 * SERVER's own answer and assert on `Client`'s observable reaction: the per-attachment hold,
 * the restored `changeEventReceived`, the deactivate contract, the single-sync-loop
 * lifecycle, and the one-shot unwrap at `syncAsync`/`broadcast`. Real wall-clock delays, not
 * `runTest` virtual time: the sync loop
 * runs on a real, non-virtualizable dispatcher (see `AttachmentTest`'s design-decision doc),
 * and [AuthTokenRefresher]'s own clock is NOT test-injectable through the public `Client`
 * surface (it is constructed internally, real `System.nanoTime()`), so the first backoff
 * tier (~0.8-1.2s) is a genuine wait here.
 */
class AuthRefreshTest {

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

    private fun newClient(
        service: MockYorkieService,
        documentPollInterval: kotlin.time.Duration = 3_000.milliseconds,
        channelHeartbeatInterval: kotlin.time.Duration = 5_000.milliseconds,
    ): Client {
        val client = Client(
            host = "0.0.0.0",
            options = Client.Options(
                key = TEST_KEY,
                apiKey = TEST_KEY,
                documentPollInterval = documentPollInterval,
                channelHeartbeatInterval = channelHeartbeatInterval,
            ),
        )
        client.service = service
        return client
    }

    /** See `WriteRejectedTest`/`WatchIdleTest`: a real-deadline poll, not a fixed `delay` guess. */
    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "condition not met within ${timeoutMs}ms"
            }
            delay(20)
        }
    }

    // ========== JS case 5 + probe iv: a 401 holds only the affected attachment ==========

    @Test
    fun `a 401 holds only the affected document while another keeps polling`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            val client = newClient(service, documentPollInterval = 100.milliseconds)
            client.activateAsync().await()

            val held = Document("auth-refresh-hold-held")
            val healthy = Document("auth-refresh-hold-healthy")
            client.attachDocument(held, syncMode = Client.SyncMode.Polling).await()
            client.attachDocument(healthy, syncMode = Client.SyncMode.Polling).await()

            // Primes the refresher's OWN backoff state as if the injector had already
            // failed: MockYorkieService bypasses the interceptor entirely (see
            // AuthInterceptorSeamTest for that wiring). This exercises the hold's OTHER
            // floor -- `loopRetryDelayMs` takes the LARGER of the loop's own base delay
            // and whatever is left of the refresher's own backoff window, so a prior
            // injector failure can hold an attachment well past the base delay alone (the
            // no-injector / succeeding-injector tests below cover the base-delay-only
            // floor, with no priming).
            client.authTokenRefresher.token(isLoopRequest = true) {
                throw IllegalStateException("priming the backoff")
            }

            service.unauthenticatedPushPullKeys += held.getKey()

            waitUntil { (service.pushPullChangesCallCounts[held.getKey()] ?: 0) >= 1 }
            val heldCallsAtFailure = service.pushPullChangesCallCounts.getValue(held.getKey())

            // The healthy document keeps polling at its own 100ms cadence regardless.
            waitUntil { (service.pushPullChangesCallCounts[healthy.getKey()] ?: 0) >= 3 }

            // The held document must not have been retried during its backoff window --
            // Polling's fixed 100ms interval would otherwise have retried it many times over
            // in the time it took the healthy document to reach 3 calls.
            assertEquals(
                heldCallsAtFailure,
                service.pushPullChangesCallCounts.getValue(held.getKey()),
                "a 401'd attachment must be held, not re-sent on Polling's fixed interval",
            )

            // Once the token "recovers" and the hold elapses (first backoff tier, ~0.8-1.2s),
            // the held document resumes on its own.
            service.unauthenticatedPushPullKeys.remove(held.getKey())
            waitUntil(timeoutMs = 3_000) {
                service.pushPullChangesCallCounts.getValue(held.getKey()) > heldCallsAtFailure
            }

            client.detachDocument(held).await()
            client.detachDocument(healthy).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    // ========== the loop-401 hold floors at a non-zero base, not 0 ==========

    /**
     * One change event is enough to seed `changeEventReceived = true`; it is RESTORED on
     * every FAILED sync (see the restore-on-failure test above), so `needRealTimeSync`
     * stays true for the rest of the test with no further events needed. Realtime's retry
     * condition is driven by `changeEventReceived`/local changes, NOT throttled by
     * `documentPollInterval` the way Polling is (#1243's pre-RPC heartbeat reset) -- so a
     * persistent 401 here is held ONLY by the loop-401 floor, the exact gap these two tests
     * pin. Polling mode does not reproduce the bug: its own, unrelated poll-interval
     * throttle already masks it.
     */
    private fun oneChangeEventPusher(
        documentKey: String,
    ): suspend (SendChannel<WatchResponse>) -> Unit = { channel ->
        channel.trySend(
            watchResponse {
                event = watchEvent {
                    this.docEvent = docWatchEvent {
                        this.documentId = documentKey
                        event = docEvent {
                            type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED
                            publisher = "other-actor"
                        }
                    }
                }
            },
        )
    }

    @Test
    fun `a persistent server 401 with no fetchAuthToken is held, not hammered every tick`() =
        runTest {
            runBlocking {
                val service = MockYorkieService()
                val document = Document("auth-refresh-loop-floor-no-injector")
                service.documentWatchFramePusher = oneChangeEventPusher(document.getKey())
                // `newClient` sets no `fetchAuthToken` -- the refresher's own backoff
                // (`retryAt`) is never touched by an injector rejection, so it stays at its
                // untouched default of zero for the whole test.
                val client = newClient(service)
                client.activateAsync().await()
                client.attachDocument(
                    document,
                    syncMode = Client.SyncMode.Realtime,
                    disablePresence = true,
                ).await()

                service.unauthenticatedPushPullKeys += document.getKey()

                // Anchors on the first observed attempt (watch-connect timing varies), then
                // bounds how many MORE land in a 1.5s window: without a non-zero floor the
                // sync loop's own ~50ms tick re-sends continuously (~30 more); the floor
                // holds the attachment for ~1s at a time, so at most 1 more lands here.
                waitUntil(timeoutMs = 3_000) {
                    (service.pushPullChangesCallCounts[document.getKey()] ?: 0) >= 1
                }
                val callsAtFirstFailure = service.pushPullChangesCallCounts.getValue(
                    document.getKey(),
                )

                delay(1_500)
                val calls = service.pushPullChangesCallCounts.getValue(document.getKey())
                assertTrue(
                    calls - callsAtFirstFailure <= 1,
                    "a persistent 401 with no fetchAuthToken must still be held at the loop " +
                        "floor, not re-sent on every ~50ms sync-loop tick; got $calls total " +
                        "(started at $callsAtFirstFailure)",
                )

                client.detachDocument(document).await()
                client.deactivateAsync().await()
                client.close()
            }
        }

    @Test
    fun `a persistent server 401 with a succeeding injector is held, not hammered every tick`() =
        runTest {
            runBlocking {
                val service = MockYorkieService()
                var injectorCalls = 0
                val document = Document("auth-refresh-loop-floor-succeeding-injector")
                service.documentWatchFramePusher = oneChangeEventPusher(document.getKey())
                val client = Client(
                    host = "0.0.0.0",
                    options = Client.Options(
                        key = TEST_KEY,
                        apiKey = TEST_KEY,
                        fetchAuthToken = {
                            injectorCalls++
                            "fresh-token"
                        },
                    ),
                )
                client.service = service
                client.activateAsync().await()
                client.attachDocument(
                    document,
                    syncMode = Client.SyncMode.Realtime,
                    disablePresence = true,
                ).await()

                // MockYorkieService bypasses the interceptor (see the class doc), so this
                // stands in for the interceptor's own call: the injector SUCCEEDS, which
                // resets the refresher's backoff (`retryAt`) back to zero -- the same state
                // a never-primed refresher starts in. The persistent 401 below must still be
                // held at the loop floor even though `retryAt` was reset by a success, not
                // just left unset.
                client.authTokenRefresher.token(isLoopRequest = true) {
                    injectorCalls++
                    "fresh-token"
                }
                assertEquals(1, injectorCalls)

                service.unauthenticatedPushPullKeys += document.getKey()

                waitUntil(timeoutMs = 3_000) {
                    (service.pushPullChangesCallCounts[document.getKey()] ?: 0) >= 1
                }
                val callsAtFirstFailure = service.pushPullChangesCallCounts.getValue(
                    document.getKey(),
                )

                delay(1_500)
                val calls = service.pushPullChangesCallCounts.getValue(document.getKey())
                assertTrue(
                    calls - callsAtFirstFailure <= 1,
                    "a persistent 401 with a succeeding injector must still be held at the " +
                        "loop floor, not re-sent on every ~50ms sync-loop tick; got $calls " +
                        "total (started at $callsAtFirstFailure)",
                )
                assertEquals(
                    1,
                    injectorCalls,
                    "the held attachment must not call the injector again during the hold",
                )

                client.detachDocument(document).await()
                client.deactivateAsync().await()
                client.close()
            }
        }

    @Test
    fun `a persistent channel 401 with no fetchAuthToken is held, not hammered every tick`() =
        runTest {
            runBlocking {
                val service = MockYorkieService()
                val client = newClient(service)
                client.activateAsync().await()

                val channel = Channel("auth-refresh-loop-floor-channel-no-injector")
                // Set BEFORE attach: for a channel, the loop's lazy first-call RefreshChannel
                // IS the attach RPC -- this guarantees even that very first attempt 401s, no
                // race with an accidental early success resetting the heartbeat timer.
                service.unauthenticatedRefreshChannelKeys += channel.getKey()
                client.attachChannel(channel, isRealtime = true).await()

                delay(1_500)
                val calls = service.refreshChannelCallCounts[channel.getKey()] ?: 0
                assertTrue(
                    // A lower bound too -- `calls <= 2` alone also
                    // passes if the hold got stuck and NOTHING was ever (re)sent, which is a
                    // different, worse bug (a channel that never retries at all) than the one
                    // this test is pinning (a channel re-sent on every tick).
                    calls in 1..2,
                    "a persistent channel 401 with no fetchAuthToken must still be held at " +
                        "the loop floor, not re-sent on every ~50ms tick, and not stuck " +
                        "forever either; got $calls calls",
                )

                // A3: the hold must also RESUME once the 401 condition clears -- a floor that
                // silently became permanent would still pass the bound above.
                service.unauthenticatedRefreshChannelKeys.remove(channel.getKey())
                waitUntil(timeoutMs = 3_000) {
                    (service.refreshChannelCallCounts[channel.getKey()] ?: 0) > calls
                }

                client.detachChannel(channel).await()
                client.deactivateAsync().await()
                client.close()
            }
        }

    // ========== JS case 8: Watch reconnects after a 401 instead of giving up ==========

    @Test
    fun `document Watch reconnects after a 401 instead of stopping the watch loop`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            var failedOnce = false
            service.documentWatchFramePusher = { channel ->
                if (!failedOnce) {
                    failedOnce = true
                    channel.close(unauthenticatedConnectException())
                }
                // else: leave the stream open with no further frames -- the reconnect itself
                // is this test's only assertion.
            }
            val client = newClient(service)
            client.activateAsync().await()
            val document = Document("auth-refresh-watch-401")
            client.attachDocument(
                document,
                syncMode = Client.SyncMode.Realtime,
                disablePresence = true,
            ).await()

            waitUntil(timeoutMs = 3_000) { service.documentWatchConnectCount >= 2 }
            assertTrue(client.isActive, "a 401'd Watch stream reconnects, it does not deactivate")

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    // ========== a failed sync restores changeEventReceived, the change is not lost ====

    @Test
    fun `a failed sync restores changeEventReceived so the remote change is not lost`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            val document = Document("auth-refresh-restore-change-event")
            service.documentWatchFramePusher = { channel ->
                channel.trySend(
                    watchResponse {
                        event = watchEvent {
                            this.docEvent = docWatchEvent {
                                this.documentId = document.getKey()
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED
                                    publisher = "other-actor"
                                }
                            }
                        }
                    },
                )
                // Silence afterward: this test only needs the ONE change-event signal.
            }
            val client = newClient(service)
            client.activateAsync().await()

            // Set BEFORE attach, as the channel no-injector-hold test
            // does -- `attachDocument` itself calls the separate `AttachDocument` RPC, never
            // `pushPullChanges`, but the sync loop's first tick can otherwise race the test's
            // own thread writing this key: an early tick that runs (and succeeds) before this
            // line would still let `pushPullChangesCallCounts` reach 2 without ever exercising
            // the 401-then-restore path at all, a false green that would not catch a regression
            // of the restore this test exists to pin. Registering first guarantees the very
            // first `pushPullChanges` call for this document -- whichever tick triggers it --
            // is the one that 401s.
            service.unauthenticatedPushPullOnceKeys += document.getKey()
            client.attachDocument(
                document,
                syncMode = Client.SyncMode.Realtime,
                disablePresence = true,
            ).await()

            // The watch event sets changeEventReceived; the sync loop's next tick resets it
            // and tries to pull -- fails ONCE with the 401 registered above.
            //
            // Without this restore, the change event is forgotten after the failed attempt (no local
            // changes and no further watch events to re-arm it) and the count below would
            // never reach 2, so this waits out the whole cycle: the failed attempt, the
            // ensuing 401 hold (~0.8-1.2s), and the successful retry that follows it.
            waitUntil(timeoutMs = 3_000) {
                (service.pushPullChangesCallCounts[document.getKey()] ?: 0) >= 2
            }

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    // ========== JS case 11: deactivate on a 401 whose refresh ALSO fails ==========

    @Test
    fun `deactivate on a 401 deactivates locally when the injector itself also fails`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            var injectorCalls = 0
            val injectorForceArgs = mutableListOf<Boolean>()
            val injectorError = IllegalStateException("token endpoint down")
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(
                    key = TEST_KEY,
                    apiKey = TEST_KEY,
                    fetchAuthToken = { force ->
                        injectorCalls++
                        injectorForceArgs += force
                        throw injectorError
                    },
                ),
            )
            client.service = service
            client.activateAsync().await()

            coEvery {
                service.deactivateClient(any(), any())
            } returns ResponseMessage.Failure(
                cause = unauthenticatedConnectException(),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            val result = client.deactivateAsync().await()

            assertTrue(result.isFailure)
            assertEquals(injectorError, result.exceptionOrNull())
            assertFalse(
                client.isActive,
                "an unrefreshable 401 on deactivate tears the client down locally",
            )
            assertEquals(1, injectorCalls)
            assertEquals(
                listOf(true),
                injectorForceArgs,
                "the deactivate probe always forces a refresh, even mid-backoff",
            )

            client.close()
        }
    }

    // ========== deactivate success branch: a 401 whose refresh SUCCEEDS ==========

    @Test
    fun `deactivate on a 401 leaves the client active when the injector succeeds`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            val injectorForceArgs = mutableListOf<Boolean>()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(
                    key = TEST_KEY,
                    apiKey = TEST_KEY,
                    fetchAuthToken = { force ->
                        injectorForceArgs += force
                        "fresh-token"
                    },
                ),
            )
            client.service = service
            client.activateAsync().await()

            coEvery {
                service.deactivateClient(any(), any())
            } returns ResponseMessage.Failure(
                cause = unauthenticatedConnectException(),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            val result = client.deactivateAsync().await()

            assertTrue(result.isFailure, "the original 401 is still returned to the caller")
            assertTrue(
                client.isActive,
                "a refreshable 401 on deactivate must leave the client active",
            )
            assertEquals(
                listOf(true),
                injectorForceArgs,
                "the deactivate probe always forces a refresh, even mid-backoff",
            )

            client.close()
        }
    }

    // ========== a never-settling deactivate refresh must not freeze the client ==========

    @Test
    fun `a never-settling injector on deactivate does not freeze the client dispatcher`() =
        runTest {
            runBlocking {
                val service = spyk(MockYorkieService())
                val injectorGate = CompletableDeferred<String>()
                val client = Client(
                    host = "0.0.0.0",
                    options = Client.Options(
                        key = TEST_KEY,
                        apiKey = TEST_KEY,
                        // Never settles until the test releases it below -- models an
                        // injector call that hangs.
                        fetchAuthToken = { injectorGate.await() },
                    ),
                )
                client.service = service
                client.activateAsync().await()

                coEvery {
                    service.deactivateClient(any(), any())
                } returns ResponseMessage.Failure(
                    cause = unauthenticatedConnectException(),
                    headers = emptyMap(),
                    trailers = emptyMap(),
                )

                // Starts the deactivate task on the client's single-thread dispatcher; it
                // parks at the injector call and never completes (the gate is never
                // released). Deliberately not awaited here.
                val deactivateDeferred = client.deactivateAsync()

                // If the pending deactivate blocked that single dispatcher thread (the
                // regression this pins), this second, unrelated operation queued on the
                // SAME dispatcher would never get a turn and `withTimeout` would fire.
                val stillActive = withTimeout(2_000) {
                    client.syncAsync().await()
                    client.isActive
                }
                assertTrue(stillActive, "the dispatcher must keep serving other work")

                // The still-pending deactivate is a genuine suspension, not an
                // uncancellable blocking call.
                deactivateDeferred.cancel()
                assertTrue(deactivateDeferred.isCancelled)

                injectorGate.complete("unused") // let the parked coroutine finish, not leak
                client.close()
            }
        }

    // ========== JS case 13: deactivate + activate leaves exactly one sync loop ==========

    @Test
    fun `deactivating then reactivating leaves exactly one running sync loop`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            val client = newClient(service)
            client.activateAsync().await()
            client.deactivateAsync().await()
            client.activateAsync().await()

            assertTrue(client.isActive)

            // Polling's fixed `nowProvider() - lastHeartbeatTime >=
            // documentPollInterval` gate is itself the bottleneck -- a duplicate loop racing
            // the SAME Attachment's `lastHeartbeatTime` would see the gate already satisfied
            // by the other loop's just-completed tick and skip its own, so the aggregate
            // call rate never actually doubles and the old `calls in 2..7` range could never
            // fail from a real duplicate loop. Realtime has no such time floor --
            // `needRealTimeSync()` is a pure `hasLocalChanges()` read, unconsumed until the
            // push's response is applied -- so two loops racing the same pending local
            // change would BOTH see it pending and BOTH push it.
            val document = Document("auth-refresh-one-loop")
            // Acks every push with its own checkpoint echoed back (as ClientPersistenceTest's
            // push-ack cases do) so a pushed local change actually clears -- the service's
            // un-keyed default response does not echo the checkpoint at all.
            service.ackOnlyPushPullKeys += document.getKey()
            client.attachDocument(
                document,
                syncMode = Client.SyncMode.Realtime,
                disablePresence = true,
            ).await()

            // Let the attach-time watch connect settle (it unconditionally flips
            // `changeEventReceived`, Client.kt, triggering one pull of its own, unrelated to
            // the local change below) before measuring a baseline.
            waitUntil(timeoutMs = 2_000) { service.documentWatchConnectCount >= 1 }
            waitUntil(timeoutMs = 2_000) { !document.hasLocalChanges() }
            delay(300)
            val before = service.pushPullChangesCallCounts[document.getKey()] ?: 0

            document.updateAsync { root, _ -> root["k"] = 1 }.await()

            // A single sync loop pushes this ONE local change exactly once; a duplicate loop
            // would independently see the same pending change (still present until the
            // first push's response is applied) and push it again.
            waitUntil(timeoutMs = 2_000) { !document.hasLocalChanges() }
            delay(300)
            val after = service.pushPullChangesCallCounts.getValue(document.getKey())
            assertEquals(
                1,
                after - before,
                "exactly one sync loop must push this one local change ($before -> $after)",
            )

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    // ========== a channel 401 is held the same way a document's is ==========

    @Test
    fun `a channel 401 holds only that channel's refresh while another channel keeps ticking`() =
        runTest {
            runBlocking {
                val service = MockYorkieService()
                val client = newClient(service, channelHeartbeatInterval = 100.milliseconds)
                client.activateAsync().await()

                val held = Channel("auth-refresh-channel-held")
                val healthy = Channel("auth-refresh-channel-healthy")
                client.attachChannel(held, isRealtime = true).await()
                client.attachChannel(healthy, isRealtime = true).await()

                // See the document hold test above: primes the refresher's own backoff
                // state, since MockYorkieService bypasses the interceptor that would
                // otherwise populate it in production.
                client.authTokenRefresher.token(isLoopRequest = true) {
                    throw IllegalStateException("priming the backoff")
                }

                service.unauthenticatedRefreshChannelKeys += held.getKey()

                waitUntil { (service.refreshChannelCallCounts[held.getKey()] ?: 0) >= 1 }
                val heldCallsAtFailure =
                    service.refreshChannelCallCounts.getValue(held.getKey())

                waitUntil { (service.refreshChannelCallCounts[healthy.getKey()] ?: 0) >= 3 }
                assertEquals(
                    heldCallsAtFailure,
                    service.refreshChannelCallCounts.getValue(held.getKey()),
                    "a 401'd channel must be held, not refreshed on every heartbeat tick",
                )

                service.unauthenticatedRefreshChannelKeys.remove(held.getKey())
                waitUntil(timeoutMs = 3_000) {
                    service.refreshChannelCallCounts.getValue(held.getKey()) > heldCallsAtFailure
                }

                client.detachChannel(held).await()
                client.detachChannel(healthy).await()
                client.deactivateAsync().await()
                client.close()
            }
        }

    // ========== a channel Watch also reconnects after a 401 ==========

    @Test
    fun `channel Watch reconnects after a 401 instead of stopping the watch loop`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            var failedOnce = false
            service.channelWatchFramePusher = { channel ->
                if (!failedOnce) {
                    failedOnce = true
                    channel.close(unauthenticatedConnectException())
                }
            }
            val client = newClient(service)
            client.activateAsync().await()
            val channel = Channel("auth-refresh-channel-watch-401")
            client.attachChannel(channel, isRealtime = true).await()

            waitUntil(timeoutMs = 3_000) { service.channelWatchConnectCount >= 2 }
            assertTrue(client.isActive)

            client.detachChannel(channel).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    // ========== JS case 10: syncAsync is one-shot, surfaces the injector's error ====

    @Test
    fun `syncAsync surfaces the injector's own error, unwrapped from the connect-kotlin wrapper`() =
        runTest {
            runBlocking {
                val service = spyk(MockYorkieService())
                val client = newClient(service)
                client.activateAsync().await()
                val document = Document("auth-refresh-syncasync-unwrap")
                client.attachDocument(
                    document,
                    syncMode = Client.SyncMode.Manual,
                    disablePresence = true,
                ).await()

                val injectorError = IllegalStateException("token endpoint down")
                coEvery {
                    service.pushPullChanges(any(), any())
                } returns ResponseMessage.Failure(
                    cause = ConnectException(
                        Code.UNKNOWN,
                        exception = AuthTokenInjectorException(injectorError),
                    ),
                    headers = emptyMap(),
                    trailers = emptyMap(),
                )

                val result = client.syncAsync(document).await()

                assertTrue(result.isFailure)
                assertEquals(injectorError, result.exceptionOrNull())

                client.detachDocument(document).await()
                client.deactivateAsync().await()
                client.close()
            }
        }

    // ========== JS case 14: the first broadcast try is one-shot, unretried ==========

    @Test
    fun `the first broadcast try surfaces the injector's error immediately, without retrying`() =
        runTest {
            runBlocking {
                val service = spyk(MockYorkieService())
                val client = newClient(service)
                client.activateAsync().await()
                val document = Document("auth-refresh-broadcast-unwrap")
                client.attachDocument(
                    document,
                    syncMode = Client.SyncMode.Manual,
                    disablePresence = true,
                ).await()

                val injectorError = IllegalStateException("token endpoint down")
                coEvery {
                    service.broadcast(any(), any())
                } returns ResponseMessage.Failure(
                    cause = ConnectException(
                        Code.UNKNOWN,
                        exception = AuthTokenInjectorException(injectorError),
                    ),
                    headers = emptyMap(),
                    trailers = emptyMap(),
                )

                // `broadcast`'s existing convention (unchanged here) is to THROW on a
                // definitive failure rather than return `Result.failure` -- see the retry
                // exhaustion path a few lines above this one-shot check in `Client.kt`.
                val error = assertFailsWith<IllegalStateException> {
                    client.broadcast(document.getKey(), "topic", "payload").await()
                }
                // `Deferred.await()` rethrowing across a coroutine boundary runs kotlinx
                // .coroutines' stack-trace-recovery, which COPIES the exception (same class,
                // same message) rather than rethrowing the original instance -- compare by
                // message, not reference, for a `Deferred`-propagated throw (unlike the
                // `Result.failure(...)`-returning sites' tests elsewhere in this file, where
                // no exception ever crosses a coroutine boundary and reference equality holds).
                assertEquals(injectorError.message, error.message)
                coVerify(exactly = 1) { service.broadcast(any(), any()) }

                client.detachDocument(document).await()
                client.deactivateAsync().await()
                client.close()
            }
        }

    // ========== one-shot unwrap at attach/detach/remove/peekChannel ====================
    // Same idiom as syncAsync's/the first broadcast try's/deactivateAsync's own pins above --
    // the request's OWN auth-header injector call rejects, connect-kotlin wraps it as a bare
    // `ConnectException(UNKNOWN, AuthTokenInjectorException(...))`, and each of these one-shot
    // call sites must surface the injector's own error via `unwrapInjectorError()`, not the
    // connect-kotlin wrapper.

    @Test
    fun `attachDocument surfaces the injector's own error, unwrapped`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            val client = newClient(service)
            client.activateAsync().await()

            val injectorError = IllegalStateException("token endpoint down")
            coEvery {
                service.attachDocument(any(), any())
            } returns ResponseMessage.Failure(
                cause = ConnectException(
                    Code.UNKNOWN,
                    exception = AuthTokenInjectorException(injectorError),
                ),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            val document = Document("auth-refresh-attach-unwrap")
            val result = client.attachDocument(document, disablePresence = true).await()

            assertTrue(result.isFailure)
            assertEquals(injectorError, result.exceptionOrNull())

            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `detachDocument surfaces the injector's own error, unwrapped`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            val client = newClient(service)
            client.activateAsync().await()
            val document = Document("auth-refresh-detach-unwrap")
            client.attachDocument(
                document,
                syncMode = Client.SyncMode.Manual,
                disablePresence = true,
            ).await()

            val injectorError = IllegalStateException("token endpoint down")
            coEvery {
                service.detachDocument(any(), any())
            } returns ResponseMessage.Failure(
                cause = ConnectException(
                    Code.UNKNOWN,
                    exception = AuthTokenInjectorException(injectorError),
                ),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            val result = client.detachDocument(document).await()

            assertTrue(result.isFailure)
            assertEquals(injectorError, result.exceptionOrNull())

            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `removeDocument surfaces the injector's own error, unwrapped`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            val client = newClient(service)
            client.activateAsync().await()
            val document = Document("auth-refresh-remove-unwrap")
            client.attachDocument(
                document,
                syncMode = Client.SyncMode.Manual,
                disablePresence = true,
            ).await()

            val injectorError = IllegalStateException("token endpoint down")
            coEvery {
                service.removeDocument(any(), any())
            } returns ResponseMessage.Failure(
                cause = ConnectException(
                    Code.UNKNOWN,
                    exception = AuthTokenInjectorException(injectorError),
                ),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            val result = client.removeDocument(document).await()

            assertTrue(result.isFailure)
            assertEquals(injectorError, result.exceptionOrNull())

            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `peekChannel surfaces the injector's own error, unwrapped`() = runTest {
        runBlocking {
            // peekChannel needs no prior activation (ClientTest's own "peekChannel succeeds
            // without prior activation") -- the unwrap must still apply on a bare client.
            val service = spyk(MockYorkieService())
            val client = newClient(service)

            val injectorError = IllegalStateException("token endpoint down")
            coEvery {
                service.peekChannel(any(), any())
            } returns ResponseMessage.Failure(
                cause = ConnectException(
                    Code.UNKNOWN,
                    exception = AuthTokenInjectorException(injectorError),
                ),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            val result = client.peekChannel("auth-refresh-peek-unwrap").await()

            assertTrue(result.isFailure)
            assertEquals(injectorError, result.exceptionOrNull())

            client.close()
        }
    }

    // ========== ErrClientNotFound/ErrClientNotActivated always deactivate, loop or ====
    // ========== one-shot (JS `handleConnectError` never reads `inRetryLoop` for these ==
    // ========== two codes) ============================================================

    @Test
    fun `a loop PushPull returning ErrClientNotFound ends the client Deactivated`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            val client = newClient(service, documentPollInterval = 100.milliseconds)
            client.activateAsync().await()
            val document = Document("auth-refresh-not-found-loop")
            client.attachDocument(document, syncMode = Client.SyncMode.Polling).await()

            coEvery {
                service.pushPullChanges(any(), any())
            } returns ResponseMessage.Failure(
                cause = clientNotFoundConnectException(),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            waitUntil(timeoutMs = 3_000) { !client.isActive }

            client.close()
        }
    }

    @Test
    fun `a one-shot syncAsync returning ErrUnauthenticated still deactivates the client`() =
        runTest {
            runBlocking {
                val service = spyk(MockYorkieService())
                val client = newClient(service)
                client.activateAsync().await()
                val document = Document("auth-refresh-oneshot-401-deactivates")
                client.attachDocument(document, syncMode = Client.SyncMode.Manual).await()

                coEvery {
                    service.pushPullChanges(any(), any())
                } returns ResponseMessage.Failure(
                    cause = unauthenticatedConnectException(),
                    headers = emptyMap(),
                    trailers = emptyMap(),
                )

                client.syncAsync(document).await()

                assertFalse(
                    client.isActive,
                    "a ONE-SHOT 401 (not from the sync loop) must still tear the client down",
                )

                client.close()
            }
        }

    // ========== a failed sync must not overwrite a `true` the Watch stream set WHILE ==
    // ========== that RPC was in flight ================================================

    @Test
    fun `a change event arriving mid-RPC on a failed sync is not lost`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            val document = Document("auth-refresh-mid-rpc-change-event")
            // Blocks every frame AFTER the server's own init frame until the test releases
            // it -- a Realtime attach's own (re)connect unconditionally sets
            // `changeEventReceived = true` (Client.kt's `createDocumentWatchJob`), so call 1
            // below is that connect-driven sync; this pusher is what lets the SECOND
            // attempt start with `changeEventReceived` genuinely false again.
            val releaseWatchFrame = CompletableDeferred<Unit>()
            service.documentWatchFramePusher = { channel ->
                releaseWatchFrame.await()
                channel.trySend(
                    watchResponse {
                        event = watchEvent {
                            this.docEvent = docWatchEvent {
                                this.documentId = document.getKey()
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED
                                    publisher = "other-actor"
                                }
                            }
                        }
                    },
                )
            }
            val callCount = AtomicInteger(0)
            val secondCallStarted = CompletableDeferred<Unit>()
            coEvery {
                service.pushPullChanges(any(), any())
            } coAnswers {
                when (callCount.incrementAndGet()) {
                    // Call 1: the connect-driven sync (`changeEventReceived` already true
                    // from the watch connect itself) -- let it succeed normally so the flag
                    // is cleanly consumed back to false.
                    1 -> callOriginal()
                    // Call 2: triggered only by watch-silence fallback (`watchFallbackDelay`
                    // below), with NO local changes and `changeEventReceived` false --
                    // exactly the case the dropped-`false`-write bug needed (a saved `true`
                    // would mask it). Signals the test, then fails with a plain transient
                    // error (not a 401: this must not entangle with the auth-hold path).
                    2 -> {
                        secondCallStarted.complete(Unit)
                        // Real wall-clock margin for the watch frame (released by the test
                        // right after this signal) to be processed before this attempt
                        // fails.
                        delay(150)
                        ResponseMessage.Failure(
                            cause = mockk(relaxed = true) {
                                every { code } returns Code.UNAVAILABLE
                            },
                            headers = emptyMap(),
                            trailers = emptyMap(),
                        )
                    }
                    else -> callOriginal()
                }
            }

            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(
                    key = TEST_KEY,
                    apiKey = TEST_KEY,
                    documentPollInterval = 200.milliseconds,
                    watchFallbackDelay = 1_000.milliseconds,
                ),
            )
            client.service = service
            client.activateAsync().await()
            client.attachDocument(
                document,
                syncMode = Client.SyncMode.Realtime,
                disablePresence = true,
            ).await()

            waitUntil(timeoutMs = 3_000) { callCount.get() >= 1 } // the connect-driven sync

            secondCallStarted.await() // the fallback-triggered sync has started
            releaseWatchFrame.complete(Unit) // the race: frame lands WHILE it is in flight

            // Without the fix, call 2's failure overwrites the mid-flight `true` back to
            // `false`, and with no further local changes, the next sync is not due until
            // ANOTHER full `watchFallbackDelay` of silence from the race frame -- well past
            // this window. With the fix, `changeEventReceived` survives and the very next
            // sync-loop tick (~50ms) retries.
            waitUntil(timeoutMs = 500) { callCount.get() >= 3 }

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    // ========== the DeactivateClient request's own injector call rejecting must =======
    // ========== still tear the client down locally ====================================

    @Test
    fun `deactivateAsync deactivates locally when its own request injector rejects`() = runTest {
        runBlocking {
            val service = spyk(MockYorkieService())
            val client = newClient(service)
            client.activateAsync().await()

            val injectorError = IllegalStateException("token endpoint down")
            coEvery {
                service.deactivateClient(any(), any())
            } returns ResponseMessage.Failure(
                cause = ConnectException(
                    Code.UNKNOWN,
                    exception = AuthTokenInjectorException(injectorError),
                ),
                headers = emptyMap(),
                trailers = emptyMap(),
            )

            val result = client.deactivateAsync().await()

            assertTrue(result.isFailure)
            assertEquals(injectorError, result.exceptionOrNull())
            assertFalse(
                client.isActive,
                "the DeactivateClient request's own injector rejecting must still " +
                    "deactivate the client locally, not leave it Activated with nothing " +
                    "running",
            )

            client.close()
        }
    }
}
