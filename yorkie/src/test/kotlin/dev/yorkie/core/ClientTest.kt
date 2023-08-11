package dev.yorkie.core

import com.google.protobuf.ByteString
import dev.yorkie.api.PBChangePack
import dev.yorkie.api.toActorID
import dev.yorkie.api.toChangePack
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.DeactivateClientRequest
import dev.yorkie.api.v1.DetachDocumentRequest
import dev.yorkie.api.v1.PushPullChangesRequest
import dev.yorkie.api.v1.RemoveDocumentRequest
import dev.yorkie.api.v1.WatchDocumentRequest
import dev.yorkie.api.v1.YorkieServiceGrpcKt
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.Client.Event.DocumentsChanged
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
import io.grpc.Channel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientTest {
    @get:Rule
    val grpcCleanup = GrpcCleanupRule()

    private lateinit var channel: Channel
    private lateinit var target: Client

    private val service = mock<YorkieServiceGrpcKt.YorkieServiceCoroutineImplBase>(
        defaultAnswer = delegatesTo(MockYorkieService()),
    )

    @Before
    fun setUp() {
        val serverName = InProcessServerBuilder.generateName()
        grpcCleanup.register(
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start(),
        )
        channel = grpcCleanup.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build(),
        )
        target = Client(channel, Client.Options(key = TEST_KEY, apiKey = TEST_KEY))
    }

    @Test
    fun `should activate and deactivate`() {
        runTest {
            assertFalse(target.isActive)
            val activateRequestCaptor = argumentCaptor<ActivateClientRequest>()
            assertTrue(target.activateAsync().await())
            verify(service).activateClient(activateRequestCaptor.capture())
            assertEquals(TEST_KEY, activateRequestCaptor.firstValue.clientKey)
            assertTrue(target.isActive)

            val activatedStatus = assertIs<Client.Status.Activated>(target.status.value)
            assertEquals(TEST_KEY, activatedStatus.clientKey)
            assertEquals(TEST_ACTOR_ID, activatedStatus.clientId)

            val deactivateRequestCaptor = argumentCaptor<DeactivateClientRequest>()
            assertTrue(target.deactivateAsync().await())
            verify(service).deactivateClient(deactivateRequestCaptor.capture())
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
            target.attachAsync(document, isRealTimeSync = false).await()
            verify(service).attachDocument(attachRequestCaptor.capture())
            assertIsTestActorID(attachRequestCaptor.firstValue.clientId)
            assertIsInitialChangePack(attachRequestCaptor.firstValue.changePack)
            assertJsonContentEquals("""{"k1": 4}""", document.toJson())

            val syncRequestCaptor = argumentCaptor<PushPullChangesRequest>()
            target.syncAsync().await()
            verify(service).pushPullChanges(syncRequestCaptor.capture())
            assertIsTestActorID(syncRequestCaptor.firstValue.clientId)
            assertIsInitialChangePack(syncRequestCaptor.firstValue.changePack)
            assertJsonContentEquals("""{"k2": 100.0}""", document.toJson())

            val detachRequestCaptor = argumentCaptor<DetachDocumentRequest>()
            target.detachAsync(document).await()
            verify(service).detachDocument(detachRequestCaptor.capture())
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

            val watchRequestCaptor = argumentCaptor<WatchDocumentRequest>()
            target.attachAsync(document).await()
            val event = target.events.first { it is DocumentsChanged }
            val changeEvent = assertIs<DocumentsChanged>(event)
            verify(service, atLeastOnce()).watchDocument(watchRequestCaptor.capture())
            assertIsTestActorID(watchRequestCaptor.firstValue.clientId)
            assertEquals(1, changeEvent.documentKeys.size)
            assertEquals(NORMAL_DOCUMENT_KEY, changeEvent.documentKeys.first().value)

            val syncRequestCaptor = argumentCaptor<PushPullChangesRequest>()
            val syncEvent = assertIs<DocumentSynced>(target.events.first())
            verify(service, atLeastOnce()).pushPullChanges(syncRequestCaptor.capture())
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

            document.updateAsync { root, _ ->
                root["k1"] = 1
            }.await()
            val syncEvent = assertIs<DocumentSynced>(target.events.first())
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
            verify(service).removeDocument(removeDocumentRequestCaptor.capture())
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

    private fun assertIsTestActorID(clientId: ByteString) {
        assertEquals(TEST_ACTOR_ID, clientId.toActorID())
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
