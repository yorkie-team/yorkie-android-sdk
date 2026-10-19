package dev.yorkie.core

import android.util.Base64
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.document.Document
import dev.yorkie.util.YorkieException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the write-rejected park/clear path (JS SDK `client.ts`, `29a81fe0`/#1462) against
 * [MockYorkieService]. JS `unit/client/write_rejected_test.ts`'s 6 cases port here by name;
 * [dev.yorkie.util.ConnectRpcErrorUtilTest] covers the pure `handleConnectException`-ordering
 * half of the same commit.
 */
class WriteRejectedTest {

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

    private fun newClient(service: MockYorkieService): Client {
        val client = Client(
            host = "0.0.0.0",
            options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
        )
        client.service = service
        return client
    }

    /**
     * M7 (team-review, RTCOLLABPLATFORM-789): polls on a real deadline for a POSITIVE
     * "this eventually happens" assertion instead of a single fixed `delay` guess. Same
     * technique as `DocumentTest.awaitCondition`/`WatchIdleTest.waitUntil`.
     */
    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "condition not met within ${timeoutMs}ms"
            }
            delay(20)
        }
    }

    private suspend fun newAttachedClient(
        docKey: String,
        service: MockYorkieService,
    ): Pair<Client, Document> {
        val client = newClient(service)
        client.activateAsync().await()
        val document = Document(docKey)
        client.attachDocument(
            document,
            syncMode = Client.SyncMode.Realtime,
            disablePresence = true,
        ).await()
        return client to document
    }

    // --- JS write_rejected_test `it.for([ErrDocumentSizeExceedsLimit, ErrChangeTooLarge])` ---

    @Test
    fun `reports ErrDocumentSizeExceedsLimit next to SyncFailed and parks the document`() =
        runTest {
            assertReportsAndParks(YorkieException.Code.ErrDocumentSizeExceedsLimit)
        }

    @Test
    fun `reports ErrChangeTooLarge next to SyncFailed and parks the document`() = runTest {
        assertReportsAndParks(YorkieException.Code.ErrChangeTooLarge)
    }

    private suspend fun assertReportsAndParks(code: YorkieException.Code) = runBlocking {
        val service = MockYorkieService()
        val (client, document) = newAttachedClient("write-rejected-${code.name}", service)
        service.writeRejectedPushPullKeys[document.getKey()] = code

        val rejected = mutableListOf<Document.Event.WriteRejected>()
        val failed = mutableListOf<Document.Event.SyncStatusChanged.SyncFailed>()
        val collector = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.collect { event ->
                when (event) {
                    is Document.Event.WriteRejected -> rejected += event
                    is Document.Event.SyncStatusChanged.SyncFailed -> failed += event
                    else -> Unit
                }
            }
        }
        document.updateAsync { root, _ -> root["a"] = 1 }.await()

        waitUntil { rejected.isNotEmpty() }

        assertEquals(1, rejected.size, "one denial episode must publish exactly once")
        assertEquals(code, rejected.single().code)
        assertEquals(
            Document.Event.AuthError.AuthErrorMethod.PushPull,
            rejected.single().method,
        )
        assertTrue(
            failed.isNotEmpty(),
            "WriteRejected is published NEXT TO SyncFailed, not instead of it",
        )
        val callsAfterPark = service.pushPullChangesCallCounts.getValue(document.getKey())
        delay(200)
        assertEquals(
            callsAfterPark,
            service.pushPullChangesCallCounts.getValue(document.getKey()),
            "a parked document must not be re-sent every syncLoopDuration tick",
        )

        collector.cancel()
        client.deactivateAsync().await()
        client.close()
    }

    // --- JS case: a typed subscription sees the code -----------------------------------

    @Test
    fun `delivers the code to a typed write-rejected subscription`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            val (client, document) = newAttachedClient("write-rejected-typed-sub", service)
            service.writeRejectedPushPullKeys[document.getKey()] =
                YorkieException.Code.ErrChangeTooLarge

            val collector = async(start = CoroutineStart.UNDISPATCHED) {
                document.events.filterIsInstance<Document.Event.WriteRejected>().first()
            }
            document.updateAsync { root, _ -> root["a"] = 1 }.await()

            val event = collector.await()
            assertEquals(YorkieException.Code.ErrChangeTooLarge, event.code)

            client.deactivateAsync().await()
            client.close()
        }
    }

    // --- JS case: only the rejected document is parked; others keep syncing -----------

    @Test
    fun `parks only the rejected document, and takes it back once a push is accepted`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            val client = newClient(service)
            client.activateAsync().await()

            val rejectedDoc = Document("write-rejected-isolation-rejected")
            client.attachDocument(
                rejectedDoc,
                syncMode = Client.SyncMode.Realtime,
                disablePresence = true,
            ).await()
            service.writeRejectedPushPullKeys[rejectedDoc.getKey()] =
                YorkieException.Code.ErrChangeTooLarge
            rejectedDoc.updateAsync { root, _ -> root["a"] = 1 }.await()

            // Let the rejected document get parked.
            waitUntil { (service.pushPullChangesCallCounts[rejectedDoc.getKey()] ?: 0) >= 1 }
            val rejectedCallsAtPark =
                service.pushPullChangesCallCounts.getValue(rejectedDoc.getKey())

            // An unrelated, healthy document attached on the SAME client.
            val healthyDoc = Document("write-rejected-isolation-healthy")
            client.attachDocument(
                healthyDoc,
                syncMode = Client.SyncMode.Realtime,
                disablePresence = true,
            ).await()
            healthyDoc.updateAsync { root, _ -> root["b"] = 2 }.await()

            waitUntil { (service.pushPullChangesCallCounts[healthyDoc.getKey()] ?: 0) > 0 }
            assertTrue(
                service.pushPullChangesCallCounts.getValue(healthyDoc.getKey()) > 0,
                "the sync loop must keep servicing OTHER attachments",
            )
            assertEquals(
                rejectedCallsAtPark,
                service.pushPullChangesCallCounts.getValue(rejectedDoc.getKey()),
                "the parked document must still not be re-sent",
            )

            // The webhook/limit relents: the next push-pull succeeds and un-parks it.
            service.writeRejectedPushPullKeys.remove(rejectedDoc.getKey())
            client.syncAsync(rejectedDoc).await()
            waitUntil {
                service.pushPullChangesCallCounts.getValue(rejectedDoc.getKey()) >
                    rejectedCallsAtPark
            }
            assertTrue(
                service.pushPullChangesCallCounts.getValue(rejectedDoc.getKey()) >
                    rejectedCallsAtPark,
                "an explicit syncAsync(doc) must resend and un-park the document",
            )

            client.deactivateAsync().await()
            client.close()
        }
    }

    // --- JS case: resyncAsync discards the oversized changes and syncs again ----------

    @Test
    fun `resyncs with the oversized changes discarded and syncs again`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            val (client, document) = newAttachedClient("write-rejected-resync", service)
            service.writeRejectedPushPullKeys[document.getKey()] =
                YorkieException.Code.ErrDocumentSizeExceedsLimit
            document.updateAsync { root, _ -> root["oversized"] = 1 }.await()

            waitUntil { (service.pushPullChangesCallCounts[document.getKey()] ?: 0) >= 1 }
            assertTrue(document.hasLocalChanges(), "the refused change is still queued")

            // The oversized change is what triggered the rejection -- discarding it (as a
            // real resync would, since the server will never accept THAT change) means the
            // next push-pull is no longer oversized. `ackOnlyPushPullKeys` makes the mock
            // echo the request's own checkpoint back (the plain default response never
            // advances the checkpoint at all, which this test is not otherwise exercising).
            service.writeRejectedPushPullKeys.remove(document.getKey())
            service.ackOnlyPushPullKeys += document.getKey()
            val dropped = client.resyncAsync(document, discardLocalChanges = true).await()

            assertTrue(dropped.isSuccess)
            assertEquals(1, dropped.getOrThrow().size)
            assertTrue(document.isMintable())

            document.updateAsync { root, _ -> root["fresh"] = 2 }.await()
            waitUntil { !document.hasLocalChanges() }
            assertEquals(
                false,
                document.hasLocalChanges(),
                "a push after the resync must actually go through, not stay parked",
            )

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    // --- M7 (team-review, RTCOLLABPLATFORM-789): Attachment.needSync's write-rejected
    // short-circuit covers BOTH the Polling fixed-interval path and the watch-silence pull
    // fallback, before either ever runs -- neither had a dedicated test. -----------------

    @Test
    fun `a parked document in Polling mode is not re-sent each poll interval`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(
                    key = TEST_KEY,
                    apiKey = TEST_KEY,
                    documentPollInterval = 100.milliseconds,
                ),
            )
            client.service = service
            client.activateAsync().await()
            val document = Document("write-rejected-polling-park")
            client.attachDocument(document, syncMode = Client.SyncMode.Polling).await()
            service.writeRejectedPushPullKeys[document.getKey()] =
                YorkieException.Code.ErrChangeTooLarge
            document.updateAsync { root, _ -> root["a"] = 1 }.await()

            waitUntil { (service.pushPullChangesCallCounts[document.getKey()] ?: 0) >= 1 }
            val callsAtPark = service.pushPullChangesCallCounts.getValue(document.getKey())

            // Polling would otherwise push/pull every documentPollInterval tick regardless
            // of local changes -- several ticks' worth of quiet window, deterministic.
            delay(500)
            assertEquals(
                callsAtPark,
                service.pushPullChangesCallCounts.getValue(document.getKey()),
                "a parked document must not be re-sent on Polling's fixed interval either",
            )

            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `a parked document does not engage the watch-silence pull fallback`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            service.documentWatchFramePusher = { } // init frame only, then silence
            val (client, document) = newAttachedClient("write-rejected-fallback-park", service)
            service.writeRejectedPushPullKeys[document.getKey()] =
                YorkieException.Code.ErrChangeTooLarge
            document.updateAsync { root, _ -> root["a"] = 1 }.await()

            waitUntil { (service.pushPullChangesCallCounts[document.getKey()] ?: 0) >= 1 }
            val callsAtPark = service.pushPullChangesCallCounts.getValue(document.getKey())

            // The silent watch stream would otherwise engage the pull fallback and push-pull
            // again (needSync's own watchFallbackDelay path) well within this window.
            delay(1_000)
            assertEquals(
                callsAtPark,
                service.pushPullChangesCallCounts.getValue(document.getKey()),
                "a parked document must not fall back to pulling on watch silence either",
            )

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }
}
