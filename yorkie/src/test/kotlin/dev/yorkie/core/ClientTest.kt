package dev.yorkie.core

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
import dev.yorkie.core.Client.Event.DocumentChanged
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.MockYorkieService.Companion.ATTACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.DETACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.NORMAL_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.REMOVE_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.core.MockYorkieService.Companion.WATCH_SYNC_ERROR_DOCUMENT_KEY
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Key
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.createSingleThreadDispatcher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    @Before
    fun setUp() {
        service = mock<YorkieServiceClientInterface>(
            defaultAnswer = delegatesTo(MockYorkieService()),
        )

        target = Client(
            service,
            Client.Options(key = TEST_KEY, apiKey = TEST_KEY),
            createSingleThreadDispatcher("Client Test"),
            OkHttpClient(),
            OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        target.close()
    }

    @Test
    fun `should activate and deactivate`() {
        runTest {
            assertFalse(target.isActive)
            val activateRequestCaptor = argumentCaptor<ActivateClientRequest>()
            assertTrue(target.activateAsync().await())
            verify(service).activateClient(activateRequestCaptor.capture(), any())
            assertEquals(TEST_KEY, activateRequestCaptor.firstValue.clientKey)
            assertTrue(target.isActive)

            val activatedStatus = assertIs<Client.Status.Activated>(target.status.value)
            assertEquals(TEST_ACTOR_ID, activatedStatus.clientId)

            val deactivateRequestCaptor = argumentCaptor<DeactivateClientRequest>()
            assertTrue(target.deactivateAsync().await())
            verify(service).deactivateClient(deactivateRequestCaptor.capture(), any())
            assertIsTestActorID(deactivateRequestCaptor.firstValue.clientId)
            assertFalse(target.isActive)
            assertIs<Client.Status.Deactivated>(target.status.value)
        }
    }

    @Test
    fun `should sync when document is attached and on manual sync requests`() {
        runTest {
            val document = Document(Key(NORMAL_DOCUMENT_KEY))
            target.activateAsync().await()

            val attachRequestCaptor = argumentCaptor<AttachDocumentRequest>()
            target.attachAsync(document, syncMode = Manual).await()
            verify(service).attachDocument(attachRequestCaptor.capture(), any())
            assertIsTestActorID(attachRequestCaptor.firstValue.clientId)
            assertIsInitialChangePack(attachRequestCaptor.firstValue.changePack)
            assertJsonContentEquals("""{"k1": 4}""", document.toJson())

            val syncRequestCaptor = argumentCaptor<PushPullChangesRequest>()
            target.syncAsync().await()
            verify(service).pushPullChanges(syncRequestCaptor.capture(), any())
            assertIsTestActorID(syncRequestCaptor.firstValue.clientId)
            assertIsInitialChangePack(syncRequestCaptor.firstValue.changePack)
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
    }

    @Test
    fun `should run watch and sync when document is attached`() {
        runTest {
            val document = Document(Key(NORMAL_DOCUMENT_KEY))
            target.activateAsync().await()

            target.attachAsync(document).await()
            val event = target.events.first { it is DocumentChanged }
            val changeEvent = assertIs<DocumentChanged>(event)
            verify(service, atLeastOnce()).watchDocument(any())
            assertEquals(1, changeEvent.documentKeys.size)
            assertEquals(NORMAL_DOCUMENT_KEY, changeEvent.documentKeys.first().value)

            val syncRequestCaptor = argumentCaptor<PushPullChangesRequest>()
            val syncEvent = assertIs<DocumentSynced>(target.events.first())
            verify(service, atLeastOnce()).pushPullChanges(syncRequestCaptor.capture(), any())
            assertIsTestActorID(syncRequestCaptor.firstValue.clientId)
            val synced = assertIs<Client.DocumentSyncResult.Synced>(syncEvent.result)
            assertEquals(document, synced.document)
            assertJsonContentEquals("""{"k2": 100.0}""", document.toJson())

            assertEquals(
                Client.StreamConnectionStatus.Connected,
                target.streamConnectionStatus.value,
            )

            target.detachAsync(document).await()
            target.deactivateAsync().await()

            assertEquals(
                Client.StreamConnectionStatus.Disconnected,
                target.streamConnectionStatus.value,
            )
        }
    }

    @Test
    fun `should emit according event when watch stream fails`() {
        runTest {
            val document = Document(Key(WATCH_SYNC_ERROR_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(document).await()

            val syncEventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                target.events.first()
            }

            document.updateAsync { root, _ ->
                root["k1"] = 1
            }.await()

            val syncEvent = assertIs<DocumentSynced>(syncEventDeferred.await())
            val failed = assertIs<Client.DocumentSyncResult.SyncFailed>(syncEvent.result)
            assertEquals(document, failed.document)

            assertEquals(
                Client.StreamConnectionStatus.Disconnected,
                target.streamConnectionStatus.first {
                    it == Client.StreamConnectionStatus.Disconnected
                },
            )

            target.detachAsync(document).await()
            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should return sync result according to server response`() {
        runTest {
            val success = Document(Key(NORMAL_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(success).await()

            assertTrue(target.syncAsync().await())
            target.detachAsync(success).await()

            val failing = Document(Key(WATCH_SYNC_ERROR_DOCUMENT_KEY))
            target.attachAsync(failing).await()
            assertFalse(target.syncAsync().await())

            target.detachAsync(failing).await()
            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should return false on attach failure without exceptions`() {
        runTest {
            val document = Document(Key(ATTACH_ERROR_DOCUMENT_KEY))
            target.activateAsync().await()

            assertFalse(target.attachAsync(document).await())

            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should return false on detach failure without exceptions`() {
        runTest {
            val document = Document(Key(DETACH_ERROR_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(document).await()

            assertFalse(target.detachAsync(document).await())

            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should handle activating and deactivating multiple times`() {
        runTest {
            assertTrue(target.activateAsync().await())
            assertTrue(target.activateAsync().await())
            delay(500)
            assertTrue(target.deactivateAsync().await())
            assertTrue(target.deactivateAsync().await())
        }
    }

    @Test
    fun `should remove document`() {
        runTest {
            val document = Document(Key(NORMAL_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(document).await()

            val removeDocumentRequestCaptor = argumentCaptor<RemoveDocumentRequest>()
            target.removeAsync(document).await()
            verify(service).removeDocument(removeDocumentRequestCaptor.capture(), any())
            assertIsTestActorID(removeDocumentRequestCaptor.firstValue.clientId)
            assertEquals(
                InitialChangePack.copy(isRemoved = true),
                removeDocumentRequestCaptor.firstValue.changePack.toChangePack(),
            )

            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should return false on remove document error without exceptions`() {
        runTest {
            val document = Document(Key(REMOVE_ERROR_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(document).await()

            assertFalse(target.removeAsync(document).await())

            target.detachAsync(document).await()
            target.deactivateAsync().await()
        }
    }

    private fun assertIsTestActorID(clientId: String) {
        assertEquals(TEST_ACTOR_ID, ActorID(clientId))
    }

    private fun assertIsInitialChangePack(changePack: PBChangePack) {
        assertEquals(InitialChangePack, changePack.toChangePack())
    }

    companion object {
        private val InitialChangePack = ChangePack(
            NORMAL_DOCUMENT_KEY,
            CheckPoint(0, 1u),
            listOf(
                Change(
                    ChangeID(1u, 1, TEST_ACTOR_ID),
                    emptyList(),
                    PresenceChange.Put(emptyMap()),
                ),
            ),
            null,
            null,
            false,
        )
    }
}
