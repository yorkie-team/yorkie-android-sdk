package dev.yorkie.core

import com.google.protobuf.ByteString
import com.google.protobuf.value
import dev.yorkie.api.PBChangePack
import dev.yorkie.api.toActorID
import dev.yorkie.api.toChangePack
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.DeactivateClientRequest
import dev.yorkie.api.v1.DetachDocumentRequest
import dev.yorkie.api.v1.PushPullRequest
import dev.yorkie.api.v1.UpdatePresenceRequest
import dev.yorkie.api.v1.WatchDocumentsRequest
import dev.yorkie.api.v1.YorkieServiceGrpc
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.Event.DocumentChanged
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.MockYorkieService.Companion.ATTACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.DETACH_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.INITIALIZATION_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.NORMAL_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.TEST_ACTOR_ID
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.core.MockYorkieService.Companion.UPDATE_PRESENCE_ERROR_DOCUMENT_KEY
import dev.yorkie.core.MockYorkieService.Companion.WATCH_SYNC_ERROR_DOCUMENT_KEY
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Key
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import io.grpc.Channel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ClientTest {
    @get:Rule
    val grpcCleanup = GrpcCleanupRule()

    private lateinit var channel: Channel
    private lateinit var target: Client

    private val service = mock<YorkieServiceGrpc.YorkieServiceImplBase>(
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
        target = Client(channel, Client.Options(key = TEST_KEY))
    }

    @Test
    fun `should activate and deactivate`() {
        runTest {
            assertFalse(target.isActive)
            val activateRequestCaptor = ArgumentCaptor.forClass(ActivateClientRequest::class.java)
            assertTrue(target.activateAsync().await())
            verify(service).activateClient(activateRequestCaptor.capture(), any())
            assertEquals(TEST_KEY, activateRequestCaptor.value.clientKey)
            assertTrue(target.isActive)

            val activatedStatus = assertIs<Client.Status.Activated>(target.status.value)
            assertEquals(TEST_KEY, activatedStatus.clientKey)
            assertEquals(TEST_ACTOR_ID, activatedStatus.clientId)

            val deactivateRequestCaptor = ArgumentCaptor.forClass(
                DeactivateClientRequest::class.java,
            )
            assertTrue(target.deactivateAsync().await())
            verify(service).deactivateClient(
                deactivateRequestCaptor.capture(),
                any(),
            )
            assertIsTestActorID(deactivateRequestCaptor.value.clientId)
            assertFalse(target.isActive)
            assertIs<Client.Status.Deactivated>(target.status.value)
        }
    }

    @Test
    fun `should sync when document is attached and on manual sync requests`() {
        runTest {
            val document = Document(Key(NORMAL_DOCUMENT_KEY))
            target.activateAsync().await()

            val attachRequestCaptor = ArgumentCaptor.forClass(AttachDocumentRequest::class.java)
            target.attachAsync(document, true).await()
            verify(service).attachDocument(attachRequestCaptor.capture(), any())
            assertIsTestActorID(attachRequestCaptor.value.clientId)
            assertIsEmptyChangePack(attachRequestCaptor.value.changePack)
            assertJsonContentEquals("""{"k1": 4}""", document.toJson())

            val syncRequestCaptor = ArgumentCaptor.forClass(PushPullRequest::class.java)
            target.syncAsync().await()
            verify(service).pushPull(syncRequestCaptor.capture(), any())
            assertIsTestActorID(syncRequestCaptor.value.clientId)
            assertIsEmptyChangePack(syncRequestCaptor.value.changePack)
            assertJsonContentEquals("""{"k2": 100.0}""", document.toJson())

            val detachRequestCaptor = ArgumentCaptor.forClass(DetachDocumentRequest::class.java)
            target.detachAsync(document).await()
            verify(service).detachDocument(detachRequestCaptor.capture(), any())
            assertIsTestActorID(detachRequestCaptor.value.clientId)
            assertIsEmptyChangePack(detachRequestCaptor.value.changePack)
            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should run watch and sync when document is attached`() {
        runTest {
            val document = Document(Key(NORMAL_DOCUMENT_KEY))
            target.activateAsync().await()

            val eventAsync = async(UnconfinedTestDispatcher()) {
                target.take(2).toList()
            }
            val watchRequestCaptor = ArgumentCaptor.forClass(WatchDocumentsRequest::class.java)
            target.attachAsync(document).await()
            val events = eventAsync.await()
            val changeEvent = assertIs<DocumentChanged>(events.first())
            verify(service).watchDocuments(watchRequestCaptor.capture(), any())
            assertIsTestActorID(watchRequestCaptor.value.client.id)
            assertEquals(1, changeEvent.documentKeys.size)
            assertEquals(NORMAL_DOCUMENT_KEY, changeEvent.documentKeys.first().value)

            val syncRequestCaptor = ArgumentCaptor.forClass(PushPullRequest::class.java)
            val syncEvent = assertIs<DocumentSynced>(events.last())
            verify(service).pushPull(syncRequestCaptor.capture(), any())
            assertIsTestActorID(syncRequestCaptor.value.clientId)
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

            document.updateAsync {
                it["k1"] = 1
            }.await()
            val syncEvent = assertIs<DocumentSynced>(target.first())
            val failed = assertIs<Client.DocumentSyncResult.SyncFailed>(syncEvent.result)
            assertEquals(document, failed.document)

            assertEquals(
                Client.StreamConnectionStatus.Disconnected,
                target.streamConnectionStatus.value,
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
    fun `should update presence`() {
        runTest {
            val document = Document(Key(NORMAL_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(document).await()

            val updatePresenceRequestCaptor = ArgumentCaptor.forClass(
                UpdatePresenceRequest::class.java,
            )
            assertTrue(target.updatePresenceAsync("k1", "v2").await())
            verify(service).updatePresence(updatePresenceRequestCaptor.capture(), any())
            assertIsTestActorID(updatePresenceRequestCaptor.value.client.id)
            assertEquals(1, updatePresenceRequestCaptor.value.client.presence.clock)
            assertContentEquals(
                listOf("k1" to "v2"),
                updatePresenceRequestCaptor.value.client.presence.dataMap.map {
                    it.key to it.value
                },
            )
            assertEquals(1, target.presenceInfo.clock)
            assertContentEquals(
                listOf("k1" to "v2"),
                target.presenceInfo.data.map { it.key to it.value },
            )

            assertEquals(
                PeerStatus(
                    Key(NORMAL_DOCUMENT_KEY),
                    target.requireClientId(),
                    PresenceInfo(1, mapOf("k1" to "v2")),
                ),
                target.peerStatus.value.first(),
            )

            target.detachAsync(document).await()
            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should return false on update presence error without exceptions`() {
        runTest {
            val document = Document(Key(UPDATE_PRESENCE_ERROR_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(document).await()

            assertFalse(target.updatePresenceAsync("k1", "v3").await())

            target.detachAsync(document).await()
            target.deactivateAsync().await()
        }
    }

    @Test
    fun `should update presence on document watch if it has initialization`() {
        runTest {
            val initialStatus = target.peerStatus.value
            val document = Document(Key(INITIALIZATION_DOCUMENT_KEY))
            target.activateAsync().await()
            target.attachAsync(document).await()

            assertEquals(
                PeerStatus(
                    Key(INITIALIZATION_DOCUMENT_KEY),
                    target.requireClientId(),
                    PresenceInfo(1, mapOf("k1" to "v1")),
                ),
                target.peerStatus.filter { it != initialStatus }.first().first(),
            )

            target.detachAsync(document).await()
            target.deactivateAsync().await()
        }
    }

    private fun assertIsTestActorID(clientId: ByteString) {
        assertEquals(TEST_ACTOR_ID, clientId.toActorID())
    }

    private fun assertIsEmptyChangePack(changePack: PBChangePack) {
        assertEquals(InitialEmptyChangePack, changePack.toChangePack())
    }

    companion object {
        private val InitialEmptyChangePack = ChangePack(
            NORMAL_DOCUMENT_KEY,
            CheckPoint(0, 0),
            emptyList(),
            null,
            null,
        )
    }
}
