package dev.yorkie.core

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
import dev.yorkie.core.MockYorkieService.Companion.DETACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.NORMAL_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.REMOVE_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.core.MockYorkieService.Companion.WATCH_SYNC_ERROR_DOCUMENT_KEY
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.Document.Key
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
import dev.yorkie.util.createSingleThreadDispatcher
import dev.yorkie.util.handleConnectException
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class ClientTest {
    private lateinit var target: Client

    private lateinit var service: YorkieServiceClientInterface

    private val refreshTestAuthToken = "RefreshTestAuthToken"
    private val testAuthToken = "TestAuthToken"

    @Before
    fun setUp() {
        service = mock<YorkieServiceClientInterface>(
            defaultAnswer = delegatesTo(MockYorkieService()),
        )

        target = Client(
            Client.Options(key = TEST_KEY, apiKey = TEST_KEY, fetchAuthToken = { refresh ->
                if (refresh) {
                    refreshTestAuthToken
                } else {
                    testAuthToken
                }
            }),
            OkHttpClient(),
            OkHttpClient(),
            createSingleThreadDispatcher("Client Test"),
            "0.0.0.0",
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
        val activateRequestCaptor = argumentCaptor<ActivateClientRequest>()
        assertTrue(target.activateAsync().await().isSuccess)
        verify(service).activateClient(activateRequestCaptor.capture(), any())
        assertEquals(TEST_KEY, activateRequestCaptor.firstValue.clientKey)
        assertTrue(target.isActive)

        val activatedStatus = assertIs<Client.Status.Activated>(target.status.value)
        assertEquals(TEST_ACTOR_ID, activatedStatus.clientId)

        val deactivateRequestCaptor = argumentCaptor<DeactivateClientRequest>()
        assertTrue(target.deactivateAsync().await().isSuccess)
        verify(service).deactivateClient(deactivateRequestCaptor.capture(), any())
        assertIsTestActorID(deactivateRequestCaptor.firstValue.clientId)
        assertFalse(target.isActive)
        assertIs<Client.Status.Deactivated>(target.status.value)
    }

    @Test
    fun `should sync when document is attached and on manual sync requests`() = runTest {
        val document = Document(Key(NORMAL_DOCUMENT_KEY))
        target.activateAsync().await()

        val attachRequestCaptor = argumentCaptor<AttachDocumentRequest>()
        target.attachAsync(document, syncMode = Manual).await()
        verify(service).attachDocument(attachRequestCaptor.capture(), any())
        assertIsTestActorID(attachRequestCaptor.firstValue.clientId)
        assertIsInitialChangePack(
            createInitialChangePack(initialAttachVersionVector, initialAttachVersionVector),
            attachRequestCaptor.firstValue.changePack,
        )
        assertJsonContentEquals("""{"k1": 4}""", document.toJson())

        val syncRequestCaptor = argumentCaptor<PushPullChangesRequest>()
        target.syncAsync().await()
        verify(service).pushPullChanges(syncRequestCaptor.capture(), any())
        assertIsTestActorID(syncRequestCaptor.firstValue.clientId)
        assertIsInitialChangePack(
            createInitialChangePack(initialSyncVersionVector, VersionVector()),
            syncRequestCaptor.firstValue.changePack,
        )
        assertJsonContentEquals("""{"k2": 100.0}""", document.toJson())

        val detachRequestCaptor = argumentCaptor<DetachDocumentRequest>()
        target.detachAsync(document).await()
        verify(service).detachDocument(detachRequestCaptor.capture(), any())
        assertIsTestActorID(detachRequestCaptor.firstValue.clientId)
        val detachmentChange =
            detachRequestCaptor.firstValue.changePack.toChangePack().changes.last()
        assertIs<PresenceChange.Clear>(detachmentChange.presenceChange)
        target.deactivateAsync().await()
    }

    @Test
    fun `should run watch and sync when document is attached`() = runTest {
        val document = Document(Key(NORMAL_DOCUMENT_KEY))
        target.activateAsync().await()

        target.attachAsync(document).await()

        val syncRequestCaptor = argumentCaptor<PushPullChangesRequest>()
        assertIs<SyncStatusChanged.Synced>(
            document.events.filterIsInstance<SyncStatusChanged>().first(),
        )
        verify(service, atLeastOnce()).pushPullChanges(syncRequestCaptor.capture(), any())
        assertIsTestActorID(syncRequestCaptor.firstValue.clientId)
        assertJsonContentEquals("""{"k2": 100.0}""", document.toJson())

        target.detachAsync(document).await()
        target.deactivateAsync().await()
    }

    @Test
    fun `should emit according event when watch stream fails`() = runTest {
        val document = Document(Key(WATCH_SYNC_ERROR_DOCUMENT_KEY))
        target.activateAsync().await()
        target.attachAsync(document).await()
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

        target.detachAsync(document).await()
        target.deactivateAsync().await()
    }

    @Test
    fun `should return sync result according to server response`() = runTest {
        val success = Document(Key(NORMAL_DOCUMENT_KEY))
        target.activateAsync().await()
        target.attachAsync(success).await()

        assertTrue(target.syncAsync().await().isSuccess)
        target.detachAsync(success).await()

        val failing = Document(Key(ATTACH_ERROR_DOCUMENT_KEY))
        assertTrue(target.attachAsync(failing).await().isFailure)

        val exception = assertFailsWith(YorkieException::class) {
            target.detachAsync(failing).await()
        }
        assertEquals(ErrDocumentNotAttached, exception.code)
        assertTrue(target.syncAsync().await().isSuccess)

        target.deactivateAsync().await()
    }

    @Test
    fun `should return false on attach failure without exceptions`() = runTest {
        val document = Document(Key(ATTACH_ERROR_DOCUMENT_KEY))
        target.activateAsync().await()

        assertFalse(target.attachAsync(document).await().isSuccess)

        target.deactivateAsync().await()
    }

    @Test
    fun `should return false on detach failure without exceptions`() = runTest {
        val document = Document(Key(DETACH_ERROR_DOCUMENT_KEY))
        target.activateAsync().await()
        target.attachAsync(document).await()

        assertFalse(target.detachAsync(document).await().isSuccess)

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
        val document = Document(Key(NORMAL_DOCUMENT_KEY))
        target.activateAsync().await()
        target.attachAsync(document).await()

        val removeDocumentRequestCaptor = argumentCaptor<RemoveDocumentRequest>()
        target.removeAsync(document).await()
        verify(service).removeDocument(removeDocumentRequestCaptor.capture(), any())
        assertIsTestActorID(removeDocumentRequestCaptor.firstValue.clientId)
        assertEquals(
            createInitialChangePack(initialRemoveVersionVector).copy(isRemoved = true),
            removeDocumentRequestCaptor.firstValue.changePack.toChangePack(),
        )

        target.deactivateAsync().await()
    }

    @Test
    fun `should return false on remove document error without exceptions`() = runTest {
        val document = Document(Key(REMOVE_ERROR_DOCUMENT_KEY))
        target.activateAsync().await()
        target.attachAsync(document).await()

        assertFalse(target.removeAsync(document).await().isSuccess)

        target.detachAsync(document).await()
        target.deactivateAsync().await()
    }

//    @Test
//    fun `should set a new token when auth error occurs`() = runTest {
//        val success = Document(Key(NORMAL_DOCUMENT_KEY))
//        target.activateAsync().await()
//        target.attachAsync(success).await()
//        assertEquals(testAuthToken, runBlocking { target.authToken(false) })
//        assertFalse(target.shouldRefreshToken)
//        assertTrue(target.detachAsync(success).await().isSuccess)
//
//        val failing = Document(Key(AUTH_ERROR_DOCUMENT_KEY))
//        val await = target.attachAsync(failing).await()
//        assertTrue(await.isFailure)
//        assertTrue(target.shouldRefreshToken)
//
//        target.deactivateAsync().await()
//    }

    @Test
    fun `should retry on network failure if error code was retryable`() = runTest {
        runBlocking {
            val mockYorkieService = MockYorkieService()
            val yorkieService = mock<YorkieServiceClientInterface>(
                defaultAnswer = delegatesTo(mockYorkieService),
            )

            val client = Client(
                Client.Options(
                    key = TEST_KEY,
                    apiKey = TEST_KEY,
                    syncLoopDuration = 500.milliseconds,
                ),
                OkHttpClient(),
                OkHttpClient(),
                createSingleThreadDispatcher("Client Test"),
                host = "0.0.0.0",
            )
            client.service = yorkieService

            client.activateAsync().await()

            delay(1000)
            assertTrue(client.conditions[Client.ClientCondition.WATCH_LOOP]!!)

            // 01. Simulate retryable errors.
            val document = Document(Key(WATCH_SYNC_ERROR_DOCUMENT_KEY))
            client.attachAsync(document).await()

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

            client.detachAsync(document).await()
            client.deactivateAsync().await()

            // 03. Assert watch loop is reactivated after client is reactivated.
            client.activateAsync().await()
            delay(1000)
            assertTrue(client.conditions[Client.ClientCondition.WATCH_LOOP]!!)

            client.deactivateAsync().await()
            document.close()
            client.close()
        }
    }

    private fun assertIsTestActorID(clientId: String) {
        assertEquals(TEST_ACTOR_ID, ActorID(clientId))
    }

    private fun assertIsInitialChangePack(initialChangePack: ChangePack, changePack: PBChangePack) {
        assertEquals(initialChangePack, changePack.toChangePack())
    }

    companion object {
        val initialAttachVersionVector = VersionVector().apply {
            set(TEST_ACTOR_ID.value, 1)
        }
        val initialSyncVersionVector = VersionVector().apply {
            set(TEST_ACTOR_ID.value, 1)
        }
        val initialRemoveVersionVector = VersionVector().apply {
            set(TEST_ACTOR_ID.value, 1)
        }

        private fun createInitialChangePack(
            changesVersionVector: VersionVector,
            changePackVersionVector: VersionVector = INITIAL_VERSION_VECTOR,
        ) = ChangePack(
            NORMAL_DOCUMENT_KEY,
            CheckPoint(0, 1u),
            listOf(
                Change(
                    ChangeID(1u, 1, TEST_ACTOR_ID, changesVersionVector),
                    emptyList(),
                    PresenceChange.Put(emptyMap()),
                ),
            ),
            null,
            null,
            false,
            changePackVersionVector,
        )
    }
}
