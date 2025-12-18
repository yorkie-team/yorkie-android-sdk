package dev.yorkie.core

import android.util.Base64
import com.connectrpc.Code
import com.connectrpc.ConnectException
import dev.yorkie.api.PBChangePack
import dev.yorkie.api.toChangePack
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.DeactivateClientRequest
import dev.yorkie.api.v1.DetachDocumentRequest
import dev.yorkie.api.v1.PushPullChangesRequest
import dev.yorkie.api.v1.RemoveDocumentRequest
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.MockYorkieService.Companion.ATTACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.AUTH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.DETACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.NORMAL_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.REMOVE_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_USER_ID
import dev.yorkie.core.MockYorkieService.Companion.WATCH_SYNC_ERROR_DOCUMENT_KEY
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.VersionVector
import dev.yorkie.document.time.VersionVector.Companion.INITIAL_VERSION_VECTOR
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotAttached
import dev.yorkie.util.handleConnectException
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    private fun assertIsTestActorID(clientId: String) {
        assertEquals(TEST_ACTOR_ID, ActorID(clientId))
    }

    private fun assertIsInitialChangePack(initialChangePack: ChangePack, changePack: PBChangePack) {
        assertEquals(initialChangePack, changePack.toChangePack())
    }

    companion object {
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
