package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.yorkie.core.Client.DocumentSyncResult
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.Client.Event.DocumentsChanged
import dev.yorkie.core.Client.StreamConnectionStatus
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.LocalChange
import dev.yorkie.document.Document.Event.RemoteChange
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ClientTest {

    @Test
    fun test_multiple_clients_working_on_same_document() {
        runBlocking {
            val client1 = createClient()
            val client2 = createClient()
            val documentKey = UUID.randomUUID().toString()
            val document1 = createDocument(documentKey)
            val document2 = createDocument(documentKey)

            val client1Events = mutableListOf<Client.Event>()
            val client2Events = mutableListOf<Client.Event>()
            val document1Events = mutableListOf<Document.Event>()
            val document2Events = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    client1.collect(client1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    client2.collect(client2Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.collect(document1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.collect(document2Events::add)
                },
            )

            client1.activateAsync().await()
            client2.activateAsync().await()

            assertIs<Client.Status.Activated>(client1.status.value)
            assertIs<Client.Status.Activated>(client2.status.value)

            client1.attachAsync(document1).await()
            var peerStatus = client1.peerStatus.dropWhile { it.isEmpty() }.first()
            assertEquals(1, peerStatus.size)
            assertEquals(peerStatus.first().actorId, client1.requireClientId())

            client2.attachAsync(document2).await()
            peerStatus = client1.peerStatus.dropWhile { it.size < 2 }.first()
            assertEquals(2, peerStatus.size)
            assertEquals(peerStatus.first().actorId, client1.requireClientId())
            assertEquals(peerStatus.last().actorId, client2.requireClientId())

            withTimeout(1_000) {
                client1.streamConnectionStatus.first { it == StreamConnectionStatus.Connected }
                client2.streamConnectionStatus.first { it == StreamConnectionStatus.Connected }
            }

            document1.updateAsync {
                it["k1"] = "v1"
            }.await()

            while (client2Events.none { it is DocumentSynced }) {
                delay(50)
            }
            val changeEvent = assertIs<DocumentsChanged>(
                client2Events.first { it is DocumentsChanged },
            )
            assertContentEquals(listOf(Document.Key(documentKey)), changeEvent.documentKeys)
            var syncEvent = assertIs<DocumentSynced>(client2Events.first { it is DocumentSynced })
            assertIs<DocumentSyncResult.Synced>(syncEvent.result)

            val localSetEvent = assertIs<LocalChange>(document1Events.first())
            val localSetOperation = assertIs<SetOperation>(
                localSetEvent.changeInfos.first().change.operations.first(),
            )
            assertEquals("k1", localSetOperation.key)
            assertEquals("v1", (localSetOperation.value as CrdtPrimitive).value)
            assertEquals(".k.1", localSetEvent.changeInfos.first().paths.first())
            document1Events.clear()

            val remoteSetEvent = assertIs<RemoteChange>(document2Events.first())
            val remoteSetOperation = assertIs<SetOperation>(
                remoteSetEvent.changeInfos.first().change.operations.first(),
            )
            assertEquals("k1", remoteSetOperation.key)
            assertEquals("v1", (remoteSetOperation.value as CrdtPrimitive).value)
            document2Events.clear()

            val root2 = document2.getRoot()
            assertEquals("v1", root2.getAs<JsonPrimitive>("k1").value)

            client1Events.clear()
            client2Events.clear()

            document2.updateAsync {
                it.remove("k1")
            }.await()

            while (client1Events.none { it is DocumentSynced }) {
                delay(50)
            }
            syncEvent = assertIs(client2Events.first { it is DocumentSynced })
            assertIs<DocumentSyncResult.Synced>(syncEvent.result)
            val root1 = document1.getRoot()
            assertTrue(root1.keys.isEmpty())

            val remoteRemoveEvent = assertIs<RemoteChange>(document1Events.first())
            val remoteRemoveOperation = assertIs<RemoveOperation>(
                remoteRemoveEvent.changeInfos.first().change.operations.first(),
            )
            assertEquals(localSetOperation.effectedCreatedAt, remoteRemoveOperation.createdAt)

            val localRemoveEvent = assertIs<LocalChange>(document2Events.first())
            val localRemoveOperation = assertIs<RemoveOperation>(
                localRemoveEvent.changeInfos.first().change.operations.first(),
            )
            assertEquals(remoteSetOperation.effectedCreatedAt, localRemoveOperation.createdAt)

            assertEquals(1, document1.clone?.getGarbageLength())
            assertEquals(1, document2.clone?.getGarbageLength())

            client1.updatePresenceAsync("k2", "v2").await()
            peerStatus = client2.peerStatus.dropWhile { it == peerStatus }.first()
            val status = peerStatus.first { it.actorId == client1.requireClientId() }
            assertEquals(mapOf("k2" to "v2"), status.presenceInfo.data)
            assertTrue(
                peerStatus.first {
                    it.actorId == client2.requireClientId()
                }.presenceInfo.data.isEmpty(),
            )

            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()

            collectJobs.forEach(Job::cancel)
        }
    }

    private fun createClient() = Client(
        InstrumentationRegistry.getInstrumentation().targetContext,
        "10.0.2.2:8080",
        true,
    )

    private fun createDocument(key: String) = Document(Document.Key(key))
}
