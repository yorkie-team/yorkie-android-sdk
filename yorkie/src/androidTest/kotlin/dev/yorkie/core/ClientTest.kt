package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.DocumentSyncResult
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.Client.Event.DocumentsChanged
import dev.yorkie.core.Client.StreamConnectionStatus
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.LocalChange
import dev.yorkie.document.Document.Event.RemoteChange
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.gson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ClientTest {

    @Test
    fun test_multiple_clients_working_on_same_document() {
        runBlocking {
            val client1 = createClient()
            val client2 = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document1 = Document(documentKey)
            val document2 = Document(documentKey)

            val client1Events = mutableListOf<Client.Event>()
            val client2Events = mutableListOf<Client.Event>()
            val document1Events = mutableListOf<Document.Event>()
            val document2Events = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    client1.events.filterNot {
                        it is Client.Event.PeersChanged
                    }.collect(client1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    client2.events.filterNot {
                        it is Client.Event.PeersChanged
                    }.collect(client2Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.collect(document1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.events.collect(document2Events::add)
                },
            )

            client1.activateAsync().await()
            client2.activateAsync().await()

            assertIs<Client.Status.Activated>(client1.status.value)
            assertIs<Client.Status.Activated>(client2.status.value)

            client1.attachAsync(document1).await()
            var peerStatus = client1.peerStatusByDoc(documentKey).dropWhile { it.isEmpty() }.first()
            assertEquals(1, peerStatus.size)
            assertEquals(peerStatus.entries.first().key, client1.requireClientId())

            client2.attachAsync(document2).await()
            peerStatus = client1.peerStatusByDoc(documentKey).dropWhile { it.size < 2 }.first()
            assertEquals(2, peerStatus.size)
            assertEquals(peerStatus.entries.first().key, client1.requireClientId())
            assertEquals(peerStatus.entries.last().key, client2.requireClientId())

            withTimeout(1_000) {
                client1.streamConnectionStatus.first { it == StreamConnectionStatus.Connected }
                client2.streamConnectionStatus.first { it == StreamConnectionStatus.Connected }
            }

            document1.updateAsync {
                it["k1"] = "v1"
            }.await()

            withTimeout(2_000) {
                while (client2Events.none { it is DocumentSynced }) {
                    delay(50)
                }
            }
            val changeEvent = assertIs<DocumentsChanged>(
                client2Events.first { it is DocumentsChanged },
            )
            assertContentEquals(listOf(documentKey), changeEvent.documentKeys)
            var syncEvent = assertIs<DocumentSynced>(client2Events.first { it is DocumentSynced })
            assertIs<DocumentSyncResult.Synced>(syncEvent.result)

            val localSetEvent = assertIs<LocalChange>(document1Events.first())
            val localSetOperation = assertIs<OperationInfo.SetOpInfo>(
                localSetEvent.changeInfos.first().operations.first(),
            )
            assertEquals("k1", localSetOperation.key)
            assertEquals("$", localSetEvent.changeInfos.first().operations.first().path)
            document1Events.clear()

            val remoteSetEvent = assertIs<RemoteChange>(document2Events.first())
            val remoteSetOperation = assertIs<OperationInfo.SetOpInfo>(
                remoteSetEvent.changeInfos.first().operations.first(),
            )
            assertEquals("k1", remoteSetOperation.key)
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
            while (client2Events.isEmpty()) {
                delay(50)
            }
            syncEvent = assertIs(client2Events.first { it is DocumentSynced })
            assertIs<DocumentSyncResult.Synced>(syncEvent.result)
            val root1 = document1.getRoot()
            assertTrue(root1.keys.isEmpty())

            val remoteRemoveEvent = assertIs<RemoteChange>(document1Events.first())
            val remoteRemoveOperation = assertIs<OperationInfo.RemoveOpInfo>(
                remoteRemoveEvent.changeInfos.first().operations.first(),
            )
            assertEquals(localSetOperation.executedAt, remoteRemoveOperation.executedAt)

            val localRemoveEvent = assertIs<LocalChange>(document2Events.first())
            val localRemoveOperation = assertIs<OperationInfo.RemoveOpInfo>(
                localRemoveEvent.changeInfos.first().operations.first(),
            )
            assertEquals(remoteSetOperation.executedAt, localRemoveOperation.executedAt)

            assertEquals(1, document1.clone?.getGarbageLength())
            assertEquals(1, document2.clone?.getGarbageLength())

            client1.updatePresenceAsync("k2", "v2").await()
            peerStatus = client2.peerStatusByDoc(documentKey)
                .first {
                    it[client1.requireClientId()]?.data?.get("k2") == "v2" &&
                        it.containsKey(client2.requireClientId())
                }

            val status = peerStatus.entries.first { it.key == client1.requireClientId() }
            assertEquals(mapOf("k2" to "v2"), status.value.data)
            assertTrue(
                peerStatus.entries.first {
                    it.key == client2.requireClientId()
                }.value.data.isEmpty(),
            )

            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()

            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_peer_presence_consistency() {
        withTwoClientsAndDocuments { client1, client2, _, _, key ->
            client1.updatePresenceAsync("name", "A").await()
            client2.updatePresenceAsync("name", "B").await()

            withTimeout(1_000) {
                client1.peerStatusByDoc(key).first {
                    it.size == 2 && it.none { peerStatus -> peerStatus.value.data.isEmpty() }
                }
                client2.peerStatusByDoc(key).first {
                    it.size == 2 && it.none { peerStatus -> peerStatus.value.data.isEmpty() }
                }
            }
            listOf(
                client1.peerStatusByDoc(key).first(),
                client2.peerStatusByDoc(key).first(),
            ).forEach { status ->
                assertEquals(
                    mapOf("name" to "A"),
                    status.entries.first { it.key == client1.requireClientId() }.value.data,
                )
                assertEquals(
                    mapOf("name" to "B"),
                    status.entries.first { it.key == client2.requireClientId() }.value.data,
                )
            }
        }
    }

    @Test
    fun test_change_realtime_sync() {
        runBlocking {
            val client1 = createClient()
            val client2 = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document1 = Document(documentKey)
            val document2 = Document(documentKey)

            client1.activateAsync().await()
            client2.activateAsync().await()

            // 01. c1 and c2 attach the doc with manual sync mode.
            //     c1 updates the doc, but c2 doesn't get until call sync manually.
            client1.attachAsync(document1, false).await()
            client2.attachAsync(document2, false).await()

            document1.updateAsync {
                it["version"] = "v1"
            }.await()
            assertNotEquals(document1.toJson(), document2.toJson())
            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            // 02. c2 changes the sync mode to realtime sync mode.
            val client2Events = mutableListOf<Client.Event>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client2.events.filterNot {
                    it is Client.Event.PeersChanged
                }.collect(client2Events::add)
            }
            client2.resume(document2)
            document1.updateAsync {
                it["version"] = "v2"
            }.await()
            client1.syncAsync().await()
            withTimeout(2_000) {
                while (client2Events.size < 2) {
                    delay(50)
                }
            }
            assertIs<DocumentSynced>(client2Events.last())
            assertEquals(document1.toJson(), document2.toJson())
            collectJob.cancel()

            // 03. c2 changes the sync mode to manual sync mode again.
            client2.pause(document2)
            document1.updateAsync {
                it["version"] = "v3"
            }.await()
            assertNotEquals(document1.toJson(), document2.toJson())
            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()
        }
    }

    @Test
    fun test_change_sync_mode_in_manual_sync() {
        runBlocking {
            val client1 = createClient()
            val client2 = createClient()
            val client3 = createClient()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document1 = Document(documentKey)
            val document2 = Document(documentKey)
            val document3 = Document(documentKey)

            client1.activateAsync().await()
            client2.activateAsync().await()
            client3.activateAsync().await()

            // 01. client2, client2, client3 attach to the same document
            client1.attachAsync(document1, false).await()
            client2.attachAsync(document2, false).await()
            client3.attachAsync(document3, false).await()

            // 02. client1 and client2 sync with push-pull mode.
            document1.updateAsync {
                it["c1"] = 0
            }.await()
            document2.updateAsync {
                it["c2"] = 0
            }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            client1.syncAsync().await()
            assertJsonContentEquals("""{"c1":0,"c2":0}""", document1.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":0}""", document2.toJson())

            // 03. client1 and client2 sync with push-only mode.
            // So, the changes of client1 and client2 are not reflected to each other.
            // But, client3 can get the changes of client1 and client2,
            // because client3 sync with push-pull mode.
            document1.updateAsync {
                it["c1"] = 1
            }.await()
            document2.updateAsync {
                it["c2"] = 1
            }.await()

            client1.syncAsync(document1, Client.SyncMode.PushOnly).await()
            client2.syncAsync(document2, Client.SyncMode.PushOnly).await()
            client3.syncAsync().await()
            assertJsonContentEquals("""{"c1":1,"c2":0}""", document1.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":1}""", document2.toJson())
            assertJsonContentEquals("""{"c1":1,"c2":1}""", document3.toJson())

            // 04. client1 and client2 sync with push-pull mode.
            client1.syncAsync().await()
            client2.syncAsync().await()
            assertJsonContentEquals("""{"c1":1,"c2":1}""", document1.toJson())
            assertJsonContentEquals("""{"c1":1,"c2":1}""", document2.toJson())

            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
            client3.detachAsync(document3).await()
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()
            client3.deactivateAsync().await()
        }
    }

    @Test
    fun test_change_sync_mode_in_realtime_sync() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, key ->
            val client3 = createClient()
            client3.activateAsync().await()

            // 01. c1, c2, c3 attach to the same document in realtime sync.
            val document3 = Document(key)
            client3.attachAsync(document3).await()

            val document1Events = mutableListOf<Document.Event>()
            val document2Events = mutableListOf<Document.Event>()
            val document3Ops = mutableListOf<OperationInfo>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.collect(document1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.events.collect(document2Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document3.events.filterIsInstance<RemoteChange>().collect { event ->
                        document3Ops.addAll(event.changeInfos.flatMap { it.operations })
                    }
                },
            )

            // 02. c1, c2 sync in realtime.
            document1.updateAsync {
                it["c1"] = 0
            }.await()
            document2.updateAsync {
                it["c2"] = 0
            }.await()
            withTimeout(2_000) {
                // size should be 2 since it has local-change and remote-change
                while (document1Events.size < 2 ||
                    document2Events.size < 2 ||
                    document3Ops.size < 2
                ) {
                    delay(50)
                }
            }
            assertJsonContentEquals("""{"c1":0,"c2":0}""", document1.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":0}""", document2.toJson())

            // 03. c1 and c2 sync with push-only mode. So, the changes of c1 and c2
            // are not reflected to each other.
            // But, c3 can get the changes of c1 and c2, because c3 sync with pull-pull mode.
            client1.pauseRemoteChange(document1)
            client2.pauseRemoteChange(document2)
            document1.updateAsync {
                it["c1"] = 1
            }.await()
            document2.updateAsync {
                it["c2"] = 1
            }.await()
            withTimeout(2_000) {
                while (document1Events.size < 3 ||
                    document2Events.size < 3 ||
                    document3Ops.size < 4
                ) {
                    delay(50)
                }
            }
            assertJsonContentEquals("""{"c1":1,"c2":0}""", document1.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":1}""", document2.toJson())
            assertJsonContentEquals("""{"c1":1,"c2":1}""", document3.toJson())

            // 04. c1 and c2 sync with push-pull mode.
            client1.resumeRemoteChanges(document1)
            client2.resumeRemoteChanges(document2)
            withTimeout(2_000) {
                while (document1Events.size < 4 || document2Events.size < 4) {
                    delay(50)
                }
            }
            assertJsonContentEquals("""{"c1":1,"c2":1}""", document1.toJson())
            assertJsonContentEquals("""{"c1":1,"c2":1}""", document2.toJson())

            client3.detachAsync(document3).await()
            client3.deactivateAsync().await()
            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_sync_option_with_mixed_mode() {
        runBlocking {
            val client = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)

            // 01. cli attach to the document having counter.
            client.activateAsync().await()
            client.attachAsync(document, false).await()

            // 02. cli update the document with creating a counter
            //     and sync with push-pull mode: CP(0, 0) -> CP(1, 1)
            document.updateAsync {
                it.setNewCounter("counter", 0)
            }.await()

            assertEquals(CheckPoint(0, 0), document.checkPoint)
            client.syncAsync().await()
            assertEquals(CheckPoint(1, 1), document.checkPoint)

            // 03. cli update the document with increasing the counter(0 -> 1)
            //     and sync with push-only mode: CP(1, 1) -> CP(2, 1)
            document.updateAsync {
                it.getAs<JsonCounter>("counter").increase(1)
            }.await()

            var changePack = document.createChangePack()
            assertEquals(1, changePack.changes.size)

            client.syncAsync(document, Client.SyncMode.PushOnly).await()
            assertEquals(CheckPoint(1, 2), document.checkPoint)

            // 04. cli update the document with increasing the counter(1 -> 2)
            //     and sync with push-pull mode. CP(2, 1) -> CP(3, 3)
            document.updateAsync {
                it.getAs<JsonCounter>("counter").increase(1)
            }.await()

            // The previous increase(0->1) is already pushed to the server,
            // so the ChangePack of the request only has the increase(1->2).
            changePack = document.createChangePack()
            assertEquals(1, changePack.changes.size)

            client.syncAsync().await()

            assertEquals(CheckPoint(3, 3), document.checkPoint)
            assertEquals(2, document.getRoot().getAs<JsonCounter>("counter").value)

            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
    }

    @Test
    fun test_access_peer_presence() {
        runBlocking {
            data class Cursor(val x: Int, val y: Int)

            val serializedCursor = gson.toJson(Cursor(1, 1))

            val client1 = createClient(presence = Presence(mapOf("name" to "a")))
            val client2 = createClient(
                presence = Presence(mapOf("name" to "b", "cursor" to serializedCursor)),
            )
            val client1Events = mutableListOf<Client.Event>()
            val client1EventsJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client1.events.collect(client1Events::add)
            }

            client1.activateAsync().await()
            client2.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document1 = Document(documentKey)
            val document2 = Document(documentKey)

            client1.attachAsync(document1).await()
            withTimeout(2_000) {
                // initialized
                while (client1Events.isEmpty()) {
                    delay(50)
                }
            }
            assertEquals(
                null,
                client1.peerStatusByDoc(documentKey).first()[client2.requireClientId()]?.data,
            )

            client2.attachAsync(document2).await()
            withTimeout(2_000) {
                client1.peerStatusByDoc(documentKey).first {
                    it[client2.requireClientId()]?.data != null
                }
            }
            assertEquals(
                mapOf("name" to "b", "cursor" to serializedCursor),
                client1.peerStatusByDoc(documentKey).first()[client2.requireClientId()]?.data,
            )

            client1EventsJob.cancel()
            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()
        }
    }

    private fun Client.peerStatusByDoc(key: Document.Key) = peerStatus.mapNotNull {
        it[key]
    }
}
