package dev.yorkie.core

import android.util.Base64
import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ResponseMessage
import dev.yorkie.api.PBChangePack
import dev.yorkie.api.toChangePack
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.DeactivateClientRequest
import dev.yorkie.api.v1.DetachDocumentRequest
import dev.yorkie.api.v1.PushPullChangesRequest
import dev.yorkie.api.v1.RemoveDocumentRequest
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.api.v1.deactivateClientResponse
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.Client.SyncMode.RealtimePushOnly
import dev.yorkie.core.MockYorkieService.Companion.ATTACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.AUTH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.DETACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.EPOCH_MISMATCH_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.MOCK_SESSION_ID
import dev.yorkie.core.MockYorkieService.Companion.NORMAL_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.REMOVE_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.SILENT_WATCH_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_USER_ID
import dev.yorkie.core.MockYorkieService.Companion.WATCH_SYNC_ERROR_DOCUMENT_KEY
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.EpochMismatch
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.VersionVector
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR
import dev.yorkie.presence.Channel
import dev.yorkie.presence.ChannelEvent
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotAttached
import dev.yorkie.util.handleConnectException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

class ClientTest {
    private lateinit var target: Client

    private lateinit var service: YorkieServiceClientInterface

    private val refreshTestAuthToken = "RefreshTestAuthToken"
    private val testAuthToken = "TestAuthToken"

    @Before
    fun setUp() {
        service = spyk(MockYorkieService())

        target = Client(
            options = Client.Options(
                key = TEST_KEY,
                apiKey = TEST_KEY,
                metadata = mapOf(
                    "user-id" to TEST_USER_ID,
                ),
                fetchAuthToken = { refresh ->
                    if (refresh) {
                        refreshTestAuthToken
                    } else {
                        testAuthToken
                    }
                },
            ),
            host = "0.0.0.0",
        )
        target.service = service
    }

    @After
    fun tearDown() {
        target.close()
    }

    @Test
    fun `should activate and deactivate`() = runTest {
        assertFalse(target.isActive)
        val activateRequestCaptor = slot<ActivateClientRequest>()
        assertTrue(target.activateAsync().await().isSuccess)
        coVerify {
            service.activateClient(capture(activateRequestCaptor), any())
        }
        assertEquals(TEST_KEY, activateRequestCaptor.captured.clientKey)
        assertEquals(TEST_USER_ID, activateRequestCaptor.captured.metadataMap["user-id"])
        assertTrue(target.isActive)

        val activatedStatus = assertIs<Client.Status.Activated>(target.status.value)
        assertEquals(TEST_ACTOR_ID, activatedStatus.clientId)

        val deactivateRequestCaptor = slot<DeactivateClientRequest>()
        assertTrue(target.deactivateAsync().await().isSuccess)
        coVerify {
            service.deactivateClient(capture(deactivateRequestCaptor), any())
        }
        assertIsTestActorID(deactivateRequestCaptor.captured.clientId)
        assertFalse(target.isActive)
        assertIs<Client.Status.Deactivated>(target.status.value)
    }

    // ========== Y2: Options.watchFallbackDelay (pull fallback on watch silence, #351) ==========

    @Test
    fun `watchFallbackDelay default is 10 seconds`() {
        assertEquals(10_000.milliseconds, Client.Options().watchFallbackDelay)
    }

    @Test
    fun `document attached with default options carries the watchFallbackDelay threshold`() =
        runTest {
            runBlocking {
                val document = Document(NORMAL_DOCUMENT_KEY)
                target.activateAsync().await()
                target.attachDocument(document).await()

                assertEquals(
                    10_000.milliseconds.inWholeMilliseconds,
                    target.attachmentWatchFallbackDelay(NORMAL_DOCUMENT_KEY),
                )

                target.detachDocument(document).await()
                target.deactivateAsync().await()
                document.close()
            }
        }

    @Test
    fun `should keep client active when deactivate fails with connect exception`() = runTest {
        // given an active client whose deactivate RPC fails
        target.activateAsync().await()
        coEvery { service.deactivateClient(any(), any()) } returns ResponseMessage.Failure(
            ConnectException(Code.FAILED_PRECONDITION),
            emptyMap(),
            emptyMap(),
        )

        // when
        val result = target.deactivateAsync().await()

        // then the failure is reported and the client stays active
        assertTrue(result.isFailure)
        assertTrue(target.isActive)

        // and a later deactivate succeeds once the RPC recovers
        coEvery { service.deactivateClient(any(), any()) } returns ResponseMessage.Success(
            deactivateClientResponse { },
            emptyMap(),
            emptyMap(),
        )
        assertTrue(target.deactivateAsync().await().isSuccess)
        assertFalse(target.isActive)
    }

    @Test
    fun `should stop sync loop when client is deactivating`() = runTest {
        runBlocking {
            // given an active client with an attached document and a running sync loop
            val document = Document(NORMAL_DOCUMENT_KEY)
            target.activateAsync().await()
            target.attachDocument(document).await()
            assertTrue(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            // when marking the client as deactivating without cancelling the loop
            target.deactivating = true
            delay(1_000)

            // then the sync loop observes the flag and stops
            assertFalse(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            target.deactivating = false
            target.detachDocument(document).await()
            target.deactivateAsync().await()
            document.close()
        }
    }

    @Test
    fun `should stop sync loop when client status is not activated`() = runTest {
        runBlocking {
            // given an active client with a running sync loop
            target.activateAsync().await()
            assertTrue(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)
            val activated = target.status.value

            // when the status flips to deactivated while the loop is still alive
            target.forceStatus(Client.Status.Deactivated)
            delay(1_000)

            // then the sync loop observes the status and stops
            assertFalse(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            target.forceStatus(activated)
            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should suppress sync result when deactivation starts during sync`() = runTest {
        runBlocking {
            mockkStatic(Base64::class)
            every { Base64.encodeToString(any(), any()) } returns "mockk"

            // given an attached document whose sync RPC raises the deactivating flag
            // mid-flight, as the keepalive deactivate path does from another thread
            val armed = AtomicBoolean(false)
            val document = Document(NORMAL_DOCUMENT_KEY)
            target.activateAsync().await()
            coEvery { service.pushPullChanges(any(), any()) } coAnswers {
                if (armed.get()) {
                    target.deactivating = true
                }
                callOriginal()
            }
            target.attachDocument(document).await()
            assertTrue(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            // when a local change triggers a sync during which the flag is raised
            armed.set(true)
            document.updateAsync { root, _ ->
                root.setNewText("text")
            }.await()
            delay(1_000)

            // then the loop drops the in-flight result and stops
            assertFalse(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            armed.set(false)
            target.deactivating = false
            target.detachDocument(document).await()
            target.deactivateAsync().await()
            document.close()

            unmockkStatic(Base64::class)
        }
    }

    @Test
    fun `should stop iterating attachments when client is deactivating`() = runTest {
        runBlocking {
            // given two attached push-only documents that raise the deactivating flag
            // from within the sync loop's own needSync check once armed, so the loop
            // observes the flag between two attachments of a single iteration
            val armed = AtomicBoolean(false)
            val documents = listOf(
                spyk(Document(NORMAL_DOCUMENT_KEY)),
                spyk(Document(ATTACHMENT_LOOP_DOCUMENT_KEY)),
            ).onEach { document ->
                every { document.hasLocalChanges() } answers {
                    if (armed.get()) {
                        target.deactivating = true
                    }
                    false
                }
            }

            target.activateAsync().await()
            documents.forEach { document ->
                target.attachDocument(document, syncMode = RealtimePushOnly).await()
            }
            assertTrue(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            // when the flag is raised mid-iteration
            armed.set(true)
            delay(1_000)

            // then the loop breaks out of the attachment iteration and stops
            assertFalse(target.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            armed.set(false)
            target.deactivating = false
            documents.forEach { document ->
                target.detachDocument(document).await()
            }
            target.deactivateAsync().await()
            documents.forEach(Document::close)
        }
    }

    @Test
    fun `should sync when document is attached and on manual sync requests`() = runTest {
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.activateAsync().await()

        val attachRequestCaptor = slot<AttachDocumentRequest>()
        target.attachDocument(document, syncMode = Manual).await()
        coVerify {
            service.attachDocument(capture(attachRequestCaptor), any())
        }
        assertIsTestActorID(attachRequestCaptor.captured.clientId)
        assertIsInitialChangePack(
            createInitialChangePack(),
            attachRequestCaptor.captured.changePack,
        )
        assertJsonContentEquals("""{"k1": 4}""", document.toJson())

        val syncRequestCaptor = slot<PushPullChangesRequest>()
        target.syncAsync().await()
        coVerify {
            service.pushPullChanges(capture(syncRequestCaptor), any())
        }
        assertIsTestActorID(syncRequestCaptor.captured.clientId)
        assertIsInitialChangePack(
            createInitialChangePack(),
            syncRequestCaptor.captured.changePack,
        )
        assertJsonContentEquals("""{"k2": 100.0}""", document.toJson())

        val detachRequestCaptor = slot<DetachDocumentRequest>()
        target.detachDocument(document).await()
        coVerify {
            service.detachDocument(capture(detachRequestCaptor), any())
        }
        assertIsTestActorID(detachRequestCaptor.captured.clientId)
        val detachmentChange =
            detachRequestCaptor.captured.changePack.toChangePack().changes.last()
        assertIs<PresenceChange.Clear>(detachmentChange.presenceChange)
        target.deactivateAsync().await()
    }

    @Test
    fun `attachDocument carries disableGc on attach and every push-pull`() = runTest {
        // given
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.activateAsync().await()

        // when
        val attachRequestCaptor = slot<AttachDocumentRequest>()
        target.attachDocument(document, syncMode = Manual, disableGC = true).await()

        // then
        coVerify {
            service.attachDocument(capture(attachRequestCaptor), any())
        }
        assertTrue(attachRequestCaptor.captured.disableGc)

        // when
        val syncRequestCaptor = slot<PushPullChangesRequest>()
        target.syncAsync().await()

        // then
        coVerify {
            service.pushPullChanges(capture(syncRequestCaptor), any())
        }
        assertTrue(syncRequestCaptor.captured.disableGc)

        target.detachDocument(document).await()
        target.deactivateAsync().await()
    }

    @Test
    fun `attachDocument sends disableGc false by default`() = runTest {
        // given
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.activateAsync().await()

        // when
        val attachRequestCaptor = slot<AttachDocumentRequest>()
        target.attachDocument(document, syncMode = Manual).await()

        // then
        coVerify {
            service.attachDocument(capture(attachRequestCaptor), any())
        }
        assertFalse(attachRequestCaptor.captured.disableGc)

        target.detachDocument(document).await()
        target.deactivateAsync().await()
    }

    @Test
    fun `should run watch and sync when document is attached`() = runTest {
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.activateAsync().await()
        target.attachDocument(document).await()

        val syncRequestCaptors = mutableListOf<PushPullChangesRequest>()
        assertIs<SyncStatusChanged.Synced>(
            document.events.filterIsInstance<SyncStatusChanged>().first(),
        )
        coVerify(atLeast = 1) {
            service.pushPullChanges(capture(syncRequestCaptors), any())
        }
        syncRequestCaptors.forEach {
            assertIsTestActorID(it.clientId)
        }
        assertJsonContentEquals("""{"k2": 100.0}""", document.toJson())

        target.detachDocument(document).await()
        target.deactivateAsync().await()
    }

    // ========== Y4: Client liveness wiring (pull fallback engage, #351 phase 1) ==========
    //
    // Loop-level, real-delay integration test reinforcing that markWatchResponseReceived()'s
    // wiring at the two Client.kt call sites actually produces real fallback pulls end-to-end —
    // the disengage-on-revival decision itself (AC2) is already conclusively proven by
    // AttachmentTest's fake-clock unit tests; this test focuses on the engage side, the part
    // only a real Client + real sync loop can exercise. Short custom Options keep it fast; a
    // separate Client (not the class-level `target`) is used so the class-level tests' default
    // (slow) watchFallbackDelay is untouched.

    @Test
    fun `Y4 - pull fallback produces real pushPullChanges calls once watch stream goes silent`() =
        runTest {
            runBlocking {
                val fallbackClient = Client(
                    host = "0.0.0.0",
                    options = Client.Options(
                        key = TEST_KEY,
                        apiKey = TEST_KEY,
                        syncLoopDuration = 50.milliseconds,
                        documentPollInterval = 100.milliseconds,
                        watchFallbackDelay = 200.milliseconds,
                    ),
                )
                fallbackClient.service = service
                val document = Document(SILENT_WATCH_DOCUMENT_KEY)

                fallbackClient.activateAsync().await()
                fallbackClient.attachDocument(document).await() // syncMode defaults to Realtime

                // SILENT_WATCH_DOCUMENT_KEY's watch stream sends only its init frame, then
                // never another — past watchFallbackDelay + documentPollInterval, fallback
                // must start pulling on its own, repeatedly, without any local edit or
                // DOCUMENT_CHANGED event ever arriving.
                delay(600)

                coVerify(atLeast = 2) {
                    service.pushPullChanges(any(), any())
                }

                fallbackClient.detachDocument(document).await()
                fallbackClient.deactivateAsync().await()
                fallbackClient.close()
                document.close()
            }
        }

    @Test
    fun `should emit according event when watch stream fails`() = runTest {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } returns "mockk"

        val document = Document(WATCH_SYNC_ERROR_DOCUMENT_KEY)
        target.activateAsync().await()
        target.attachDocument(document).await()
        val syncEventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<SyncStatusChanged>().first()
        }
        val connectionEventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            document.events.filterIsInstance<StreamConnectionChanged.Disconnected>().first()
        }

        document.updateAsync { root, _ ->
            root["k1"] = 1
        }.await()

        assertIs<SyncStatusChanged.SyncFailed>(syncEventDeferred.await())
        assertIs<StreamConnectionChanged.Disconnected>(connectionEventDeferred.await())

        target.detachDocument(document).await()
        target.deactivateAsync().await()

        unmockkStatic(Base64::class)
    }

    @Test
    fun `should return sync result according to server response`() = runTest {
        val success = Document(NORMAL_DOCUMENT_KEY)
        target.activateAsync().await()
        target.attachDocument(success).await()

        assertTrue(target.syncAsync().await().isSuccess)
        target.detachDocument(success).await()

        val failing = Document(ATTACH_ERROR_DOCUMENT_KEY)
        assertTrue(target.attachDocument(failing).await().isFailure)

        val exception = assertFailsWith(YorkieException::class) {
            target.detachDocument(failing).await()
        }
        assertEquals(ErrDocumentNotAttached, exception.code)
        assertTrue(target.syncAsync().await().isSuccess)

        target.deactivateAsync().await()
    }

    @Test
    fun `should return false on attach failure without exceptions`() = runTest {
        val document = Document(ATTACH_ERROR_DOCUMENT_KEY)
        target.activateAsync().await()

        assertFalse(target.attachDocument(document).await().isSuccess)

        target.deactivateAsync().await()
    }

    @Test
    fun `should return false on detach failure without exceptions`() = runTest {
        val document = Document(DETACH_ERROR_DOCUMENT_KEY)
        target.activateAsync().await()
        target.attachDocument(document).await()

        assertFalse(target.detachDocument(document).await().isSuccess)

        target.deactivateAsync().await()
    }

    @Test
    fun `should handle activating and deactivating multiple times`() = runTest {
        assertTrue(target.activateAsync().await().isSuccess)
        assertTrue(target.activateAsync().await().isSuccess)
        delay(500)
        assertTrue(target.deactivateAsync().await().isSuccess)
        assertTrue(target.deactivateAsync().await().isSuccess)
    }

    @Test
    fun `should remove document`() = runTest {
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.activateAsync().await()
        target.attachDocument(document).await()

        val removeDocumentRequestCaptor = slot<RemoveDocumentRequest>()
        target.removeDocument(document).await()
        coVerify {
            service.removeDocument(capture(removeDocumentRequestCaptor), any())
        }
        assertIsTestActorID(removeDocumentRequestCaptor.captured.clientId)
        assertEquals(
            createInitialChangePack().copy(isRemoved = true),
            removeDocumentRequestCaptor.captured.changePack.toChangePack(),
        )

        target.deactivateAsync().await()
    }

    @Test
    fun `should return false on remove document error without exceptions`() = runTest {
        val document = Document(REMOVE_ERROR_DOCUMENT_KEY)
        target.activateAsync().await()
        target.attachDocument(document).await()

        assertFalse(target.removeDocument(document).await().isSuccess)

        target.detachDocument(document).await()
        target.deactivateAsync().await()
    }

    @Test
    fun `should set a new token when auth error occurs`() = runTest {
        val success = Document(NORMAL_DOCUMENT_KEY)
        target.activateAsync().await()
        target.attachDocument(success).await()
        assertEquals(testAuthToken, runBlocking { target.authToken(false) })
        assertFalse(target.shouldRefreshToken)
        assertTrue(target.detachDocument(success).await().isSuccess)

        val failing = Document(AUTH_ERROR_DOCUMENT_KEY)
        val await = target.attachDocument(failing).await()
        assertTrue(await.isFailure)
        assertTrue(target.shouldRefreshToken)

        target.deactivateAsync().await()
    }

    @Test
    fun `should publish epoch mismatch event when server rejects sync with ErrEpochMismatch`() =
        runTest {
            // given
            val document = Document(EPOCH_MISMATCH_DOCUMENT_KEY)
            target.activateAsync().await()
            target.attachDocument(document, syncMode = Manual).await()

            val epochMismatchDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                document.events.filterIsInstance<EpochMismatch>().first()
            }

            // when
            val syncResult = target.syncAsync(document).await()

            // then
            assertTrue(syncResult.isFailure)
            val event = epochMismatchDeferred.await()
            assertEquals(EpochMismatch.EpochMismatchMethod.PushPull, event.method)

            target.deactivateAsync().await()
        }

    @Test
    fun `should retry on network failure if error code was retryable`() = runTest {
        runBlocking {
            val mockYorkieService = MockYorkieService()
            val yorkieService = spyk<YorkieServiceClientInterface>(MockYorkieService())

            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(
                    key = TEST_KEY,
                    apiKey = TEST_KEY,
                    syncLoopDuration = 500.milliseconds,
                ),
            )
            client.service = yorkieService

            client.activateAsync().await()

            // 01. Simulate retryable errors.
            val document = Document(WATCH_SYNC_ERROR_DOCUMENT_KEY)
            client.attachDocument(document).await()

            document.updateAsync { root, _ ->
                root.setNewText("")
                root.getAs<JsonText>("text").apply {
                    edit(0, 0, "a")
                }
            }.await()

            delay(1000)
            assertTrue(client.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            mockYorkieService.customError[WATCH_SYNC_ERROR_DOCUMENT_KEY] = Code.CANCELED
            document.updateAsync { root, _ ->
                root.getAs<JsonText>("text").apply {
                    edit(0, 0, "b")
                }
            }.await()
            delay(1000)
            assertTrue(client.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            mockYorkieService.customError[WATCH_SYNC_ERROR_DOCUMENT_KEY] = Code.RESOURCE_EXHAUSTED
            document.updateAsync { root, _ ->
                root.getAs<JsonText>("text").apply {
                    edit(0, 0, "b")
                }
            }.await()
            delay(1000)
            assertTrue(client.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            mockYorkieService.customError[WATCH_SYNC_ERROR_DOCUMENT_KEY] = Code.UNAVAILABLE
            document.updateAsync { root, _ ->
                root.getAs<JsonText>("text").apply {
                    edit(0, 0, "b")
                }
            }.await()
            delay(1000)
            assertTrue(client.conditions[Client.ClientCondition.SYNC_LOOP]!!)

            // 02. Simulate FailedPrecondition error which is not retryable.
            Code.entries.filterNot { errorCode ->
                handleConnectException(ConnectException(errorCode))
            }.forEach { nonRetryableErrorCode ->
                mockYorkieService.customError[WATCH_SYNC_ERROR_DOCUMENT_KEY] = nonRetryableErrorCode
                document.updateAsync { root, _ ->
                    root.getAs<JsonText>("text").apply {
                        edit(0, 0, "b")
                    }
                }.await()
                delay(1000)

                assertFalse(client.conditions[Client.ClientCondition.SYNC_LOOP]!!)
                assertFalse(client.conditions[Client.ClientCondition.WATCH_LOOP]!!)
            }

            client.detachDocument(document).await()
            client.deactivateAsync().await()

            document.close()
            client.close()
        }
    }

    @Test
    fun `detachDocument with keepalive true should work even after client close`() = runTest {
        val service = spyk(MockYorkieService())
        val client = Client(
            host = "0.0.0.0",
            options = Client.Options(
                key = TEST_KEY,
                apiKey = TEST_KEY,
            ),
        )
        client.service = service

        val document = Document(NORMAL_DOCUMENT_KEY)

        // Activate and attach document
        client.activateAsync().await()
        client.attachDocument(document, syncMode = Manual).await()
        assertEquals(ResourceStatus.Attached, document.getStatus())

        // Start detach with keepalive = true
        val detachDeferred = async {
            client.detachDocument(document, keepalive = true).await()
        }

        // Add a small delay to ensure the detach has started
        delay(10)

        // Close the client while detach might still be in progress
        client.close()

        // Wait for detach to complete
        val result = detachDeferred.await()

        // Verify that detach completed successfully despite client being closed
        assertTrue(result.isSuccess)
        assertEquals(ResourceStatus.Detached, document.getStatus())
    }

    @Test
    fun `createRevision fails when client is not active`() = runTest {
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.createRevision(document, "v1.0").await()
        }
        assertEquals(YorkieException.Code.ErrClientNotActivated, exception.code)
    }

    @Test
    fun `createRevision fails when document is not attached`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.createRevision(document, "v1.0").await()
        }
        assertEquals(ErrDocumentNotAttached, exception.code)
    }

    @Test
    fun `createRevision returns revision summary with expected label and description`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.attachDocument(document, syncMode = Manual).await()

        val revision = target.createRevision(document, "v1.0", "First release").await()

        assertEquals("v1.0", revision.label)
        assertEquals("First release", revision.description)
        assertTrue(revision.id.isNotEmpty())
    }

    @Test
    fun `listRevisions fails when client is not active`() = runTest {
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.listRevisions(document).await()
        }
        assertEquals(YorkieException.Code.ErrClientNotActivated, exception.code)
    }

    @Test
    fun `listRevisions fails when document is not attached`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.listRevisions(document).await()
        }
        assertEquals(ErrDocumentNotAttached, exception.code)
    }

    @Test
    fun `listRevisions returns empty list when no revisions exist`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.attachDocument(document, syncMode = Manual).await()

        val revisions = target.listRevisions(document).await()

        assertTrue(revisions.isEmpty())
    }

    @Test
    fun `getRevision fails when client is not active`() = runTest {
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.getRevision(document, "revision-id").await()
        }
        assertEquals(YorkieException.Code.ErrClientNotActivated, exception.code)
    }

    @Test
    fun `getRevision fails when document is not attached`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.getRevision(document, "revision-id").await()
        }
        assertEquals(ErrDocumentNotAttached, exception.code)
    }

    @Test
    fun `getRevision returns revision summary matching requested id`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.attachDocument(document, syncMode = Manual).await()

        val revision = target.getRevision(document, "test-revision-id").await()

        assertEquals("test-revision-id", revision.id)
        assertEquals("test-label", revision.label)
        assertEquals("test-description", revision.description)
    }

    @Test
    fun `restoreRevision fails when client is not active`() = runTest {
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.restoreRevision(document, "revision-id").await()
        }
        assertEquals(YorkieException.Code.ErrClientNotActivated, exception.code)
    }

    @Test
    fun `restoreRevision fails when document is not attached`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)

        val exception = assertFailsWith<YorkieException> {
            target.restoreRevision(document, "revision-id").await()
        }
        assertEquals(ErrDocumentNotAttached, exception.code)
    }

    @Test
    fun `restoreRevision succeeds when client is active and document is attached`() = runTest {
        target.activateAsync().await()
        val document = Document(NORMAL_DOCUMENT_KEY)
        target.attachDocument(document, syncMode = Manual).await()

        val result = target.restoreRevision(document, "test-revision-id").await()

        assertTrue(result.isSuccess)
    }

    @Test
    fun `channel refresh publishes changed event only when session count changes`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            client.activateAsync().await()

            val channel = Channel("test-channel")
            client.attachChannel(channel, isRealtime = false).await()

            val events = mutableListOf<ChannelEvent.Changed>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                channel.eventStream.filterIsInstance<ChannelEvent.Changed>()
                    .take(2)
                    .toList(events)
            }

            // when
            mockService.refreshChannelSessionCount = 2L
            client.syncAsync(channel).await()
            client.syncAsync(channel).await()
            mockService.refreshChannelSessionCount = 3L
            client.syncAsync(channel).await()

            // then
            withTimeout(3_000) { collectJob.join() }
            assertEquals(listOf(2L, 3L), events.map { it.sessionCount })

            client.detachChannel(channel).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `channel refresh publishes changed event on count decrease`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            client.activateAsync().await()

            val channel = Channel("test-channel")
            client.attachChannel(channel, isRealtime = false).await()

            val events = mutableListOf<ChannelEvent.Changed>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                channel.eventStream.filterIsInstance<ChannelEvent.Changed>()
                    .take(2)
                    .toList(events)
            }

            // when
            mockService.refreshChannelSessionCount = 3L
            client.syncAsync(channel).await()
            mockService.refreshChannelSessionCount = 2L
            client.syncAsync(channel).await()

            // then
            withTimeout(3_000) { collectJob.join() }
            assertEquals(listOf(3L, 2L), events.map { it.sessionCount })

            client.detachChannel(channel).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `peekChannel succeeds without prior activation`() = runTest {
        // given
        val mockService = MockYorkieService().apply { peekChannelSessionCount = 7L }
        val client = Client(
            host = "0.0.0.0",
            options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
        )
        client.service = mockService

        // when
        val result = client.peekChannel("test-channel").await()

        // then
        assertEquals(7L, result.getOrNull())
        client.close()
    }

    @Test
    fun `channel attach without activate populates client id via first refresh`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            val channel = Channel("test-channel")

            // when: attach registers locally only
            client.attachChannel(channel, isRealtime = false).await()

            // then: nothing on the server yet, client not activated
            assertFalse(client.isActive)
            assertEquals(null, channel.getSessionId())
            assertFalse(channel.isAttached())

            // when: the first refresh performs the first call
            client.syncAsync(channel).await()

            // then: lazy activation happened
            assertTrue(client.isActive)
            assertEquals(TEST_ACTOR_ID, client.requireClientId())
            assertEquals(TEST_ACTOR_ID, channel.getActorID())
            assertEquals(MOCK_SESSION_ID, channel.getSessionId())
            assertTrue(channel.isAttached())
            assertEquals(1, mockService.refreshChannelFirstCallCount)

            client.detachChannel(channel).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `channel attach and detach do not call attachChannel or detachChannel rpc`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val spiedService = spyk<YorkieServiceClientInterface>(mockService)
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = spiedService
            client.activateAsync().await()
            val channel = Channel("test-channel")

            // when
            client.attachChannel(channel, isRealtime = false).await()
            client.syncAsync(channel).await()
            client.detachChannel(channel).await()

            // then
            coVerify(exactly = 0) { spiedService.attachChannel(any(), any()) }
            coVerify(exactly = 0) { spiedService.detachChannel(any(), any()) }
            coVerify(atLeast = 1) { spiedService.refreshChannel(any(), any()) }

            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `session not found clears session id and re-attaches on next refresh`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            client.activateAsync().await()
            val channel = Channel("test-channel")
            client.attachChannel(channel, isRealtime = false).await()
            client.syncAsync(channel).await()
            assertEquals(MOCK_SESSION_ID, channel.getSessionId())

            // when: the server reclaimed the session
            mockService.refreshChannelSessionNotFoundOnce = true
            val recoveryResult = client.syncAsync(channel).await()

            // then: swallowed, ids cleared for transparent re-attach
            assertTrue(recoveryResult.isSuccess)
            assertEquals(null, channel.getSessionId())

            // when: next refresh re-enters the first-call path
            client.syncAsync(channel).await()

            // then
            assertEquals(MOCK_SESSION_ID, channel.getSessionId())
            assertEquals(2, mockService.refreshChannelFirstCallCount)

            client.detachChannel(channel).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `channel refresh publishes sync error event on non-recoverable failure`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            client.activateAsync().await()
            val channel = Channel("test-channel")
            client.attachChannel(channel, isRealtime = false).await()

            val events = mutableListOf<ChannelEvent.SyncError>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                channel.eventStream.filterIsInstance<ChannelEvent.SyncError>()
                    .take(1)
                    .toList(events)
            }

            // when
            mockService.refreshChannelFails = true
            val result = client.syncAsync(channel).await()

            // then
            assertTrue(result.isFailure)
            withTimeout(3_000) { collectJob.join() }
            assertIs<ConnectException>(events.single().cause)

            client.close()
        }
    }

    @Test
    fun `detach during in-flight refresh does not resurrect session`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService().apply { refreshChannelDelayMs = 200L }
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            client.activateAsync().await()
            val channel = Channel("test-channel")
            client.attachChannel(channel, isRealtime = false).await()

            // when: first refresh suspends in the RPC while detach runs
            val syncDeferred = client.syncAsync(channel)
            delay(50)
            client.detachChannel(channel).await()
            syncDeferred.await()

            // then: the stale response was dropped
            assertEquals(null, channel.getSessionId())
            assertFalse(channel.isAttached())
            assertFalse(client.has(channel.getKey()))

            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `deactivate tears down never-activated channel-only client`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            val channel = Channel("test-channel")
            client.attachChannel(channel, isRealtime = false).await()

            // when
            client.deactivateAsync().await()

            // then: local teardown removed the attachment
            assertFalse(client.isActive)
            assertFalse(client.has(channel.getKey()))
            assertFalse(channel.isAttached())

            client.close()
        }
    }

    @Test
    fun `activate after channel attach keeps channel syncing`() = runTest {
        runBlocking {
            // given
            val mockService = MockYorkieService()
            val client = Client(
                host = "0.0.0.0",
                options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            )
            client.service = mockService
            val channel = Channel("test-channel")
            client.attachChannel(channel, isRealtime = false).await()

            // when: explicit activation lands after the lazy entry point
            client.activateAsync().await()
            client.syncAsync(channel).await()

            // then
            assertTrue(client.isActive)
            assertEquals(MOCK_SESSION_ID, channel.getSessionId())

            client.detachChannel(channel).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `peekChannel returns session count without creating an attachment`() = runTest {
        // given
        val mockService = MockYorkieService().apply { peekChannelSessionCount = 5L }
        val spiedService = spyk<YorkieServiceClientInterface>(mockService)
        val client = Client(
            host = "0.0.0.0",
            options = Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
        )
        client.service = spiedService
        client.activateAsync().await()

        // when
        val result = client.peekChannel("test-channel").await()

        // then
        assertEquals(5L, result.getOrNull())
        coVerify(exactly = 0) { spiedService.attachChannel(any(), any()) }

        client.deactivateAsync().await()
        client.close()
    }

    @Test
    fun `peekChannel returns failure and refreshes token on auth error`() = runTest {
        // given
        target.activateAsync().await()

        // when
        val result = target.peekChannel(AUTH_ERROR_DOCUMENT_KEY).await()

        // then
        assertTrue(result.isFailure)
        assertTrue(target.shouldRefreshToken)
    }

    private fun assertIsTestActorID(clientId: String) {
        assertEquals(TEST_ACTOR_ID, clientId)
    }

    /**
     * Forces the client's status without going through activation or deactivation,
     * so tests can observe how running loops react to a status change alone.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Client.forceStatus(status: Client.Status) {
        val field = Client::class.java.getDeclaredField("_status")
        field.isAccessible = true
        (field.get(this) as MutableStateFlow<Client.Status>).value = status
    }

    /**
     * Reads a resource's [Attachment] out of the client's private `attachments` map,
     * then reads a private field off it — same reflection technique as [forceStatus] —
     * so a test can confirm an `Options` value was actually plumbed through to the
     * `Attachment` the client constructed, without exposing that field publicly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Client.attachmentWatchFallbackDelay(documentKey: String): Long {
        val attachmentsField = Client::class.java.getDeclaredField("attachments")
        attachmentsField.isAccessible = true
        val attachments = attachmentsField.get(this) as Map<String, Attachment<out Attachable>>
        val attachment = attachments.getValue(documentKey)
        val delayField = Attachment::class.java.getDeclaredField("watchFallbackDelay")
        delayField.isAccessible = true
        return delayField.get(attachment) as Long
    }

    private fun assertIsInitialChangePack(initialChangePack: ChangePack, changePack: PBChangePack) {
        assertEquals(initialChangePack, changePack.toChangePack())
    }

    companion object {
        private const val ATTACHMENT_LOOP_DOCUMENT_KEY = "ATTACHMENT_LOOP_DOCUMENT_KEY"

        private fun createInitialChangePack(
            changesVersionVector: VersionVector = INITIAL_VERSION_VECTOR,
            changePackVersionVector: VersionVector = INITIAL_VERSION_VECTOR,
        ) = ChangePack(
            NORMAL_DOCUMENT_KEY,
            CheckPoint(0, 1u),
            listOf(
                Change(
                    ChangeID(1u, 0, TEST_ACTOR_ID, changesVersionVector),
                    emptyList(),
                    PresenceChange.Put(emptyMap()),
                ),
            ),
            null,
            false,
            changePackVersionVector,
        )
    }
}
