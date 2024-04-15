package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.DocumentSyncResult
import dev.yorkie.core.Client.Event.DocumentChanged
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.Client.StreamConnectionStatus
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.Client.SyncMode.Realtime
import dev.yorkie.core.Client.SyncMode.RealtimePushOnly
import dev.yorkie.core.Client.SyncMode.RealtimeSyncOff
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.LocalChange
import dev.yorkie.document.Document.Event.RemoteChange
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTreeTest.Companion.assertTreesXmlEquals
import dev.yorkie.document.json.JsonTreeTest.Companion.rootTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

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
                    client1.events.collect(client1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    client2.events.collect(client2Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.filterNot {
                        it is Document.Event.PresenceChange
                    }.collect(document1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.events.filterNot {
                        it is Document.Event.PresenceChange
                    }.collect(document2Events::add)
                },
            )

            client1.activateAsync().await()
            client2.activateAsync().await()

            assertIs<Client.Status.Activated>(client1.status.value)
            assertIs<Client.Status.Activated>(client2.status.value)

            client1.attachAsync(document1).await()
            client2.attachAsync(document2).await()

            withTimeout(GENERAL_TIMEOUT) {
                client1.streamConnectionStatus.first { it == StreamConnectionStatus.Connected }
                client2.streamConnectionStatus.first { it == StreamConnectionStatus.Connected }
            }

            document1.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (client2Events.none { it is DocumentSynced }) {
                    delay(50)
                }
            }
            val changeEvent = assertIs<DocumentChanged>(
                client2Events.first { it is DocumentChanged },
            )
            assertContentEquals(listOf(documentKey), changeEvent.documentKeys)
            var syncEvent = assertIs<DocumentSynced>(client2Events.first { it is DocumentSynced })
            assertIs<DocumentSyncResult.Synced>(syncEvent.result)

            val localSetEvent = assertIs<LocalChange>(document1Events.last())
            val localSetOperation = assertIs<OperationInfo.SetOpInfo>(
                localSetEvent.changeInfo.operations.first(),
            )
            assertEquals("k1", localSetOperation.key)
            assertEquals("$", localSetEvent.changeInfo.operations.first().path)
            document1Events.clear()

            val remoteSetEvent = assertIs<RemoteChange>(document2Events.last())
            val remoteSetOperation = assertIs<OperationInfo.SetOpInfo>(
                remoteSetEvent.changeInfo.operations.first(),
            )
            assertEquals("k1", remoteSetOperation.key)
            document2Events.clear()

            val root2 = document2.getRoot()
            assertEquals("v1", root2.getAs<JsonPrimitive>("k1").value)

            client1Events.clear()
            client2Events.clear()

            document2.updateAsync { root, _ ->
                root.remove("k1")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (client1Events.none { it is DocumentSynced }) {
                    delay(50)
                }
            }
            withTimeout(GENERAL_TIMEOUT) {
                while (client2Events.isEmpty()) {
                    delay(50)
                }
            }
            syncEvent = assertIs(client2Events.first { it is DocumentSynced })
            assertIs<DocumentSyncResult.Synced>(syncEvent.result)
            val root1 = document1.getRoot()
            assertTrue(root1.keys.isEmpty())

            val remoteRemoveEvent = assertIs<RemoteChange>(document1Events.first())
            val remoteRemoveOperation = assertIs<OperationInfo.RemoveOpInfo>(
                remoteRemoveEvent.changeInfo.operations.first(),
            )
            assertEquals(localSetOperation.executedAt, remoteRemoveOperation.executedAt)

            val localRemoveEvent = assertIs<LocalChange>(document2Events.first())
            val localRemoveOperation = assertIs<OperationInfo.RemoveOpInfo>(
                localRemoveEvent.changeInfo.operations.first(),
            )
            assertEquals(remoteSetOperation.executedAt, localRemoveOperation.executedAt)

            assertEquals(1, document1.clone?.root?.getGarbageLength())
            assertEquals(1, document2.clone?.root?.getGarbageLength())

            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()
            document1.close()
            document2.close()
            client1.close()
            client2.close()

            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_change_sync_mode_between_realtime_and_manual() {
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
            client1.attachAsync(document1, syncMode = Manual).await()
            client2.attachAsync(document2, syncMode = Manual).await()

            document1.updateAsync { root, _ ->
                root["version"] = "v1"
            }.await()
            assertJsonContentEquals("""{"version":"v1"}""", document1.toJson())
            assertJsonContentEquals("""{}""", document2.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            // 02. c2 changes the sync mode to realtime sync mode.
            val client2Events = mutableListOf<Client.Event>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                client2.events.collect(client2Events::add)
            }

            client2.changeSyncMode(document2, Realtime)
            withTimeout(GENERAL_TIMEOUT) {
                while (client2Events.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<DocumentSynced>(client2Events.first())

            document1.updateAsync { root, _ ->
                root["version"] = "v2"
            }.await()
            client1.syncAsync().await()

            withTimeout(GENERAL_TIMEOUT) {
                while (client2Events.size < 3) {
                    delay(50)
                }
            }
            assertIs<DocumentSynced>(client2Events.last())
            assertEquals(document1.toJson(), document2.toJson())
            collectJob.cancel()

            // 03. c2 changes the sync mode to manual sync mode again.
            client2.changeSyncMode(document2, Manual)
            document1.updateAsync { root, _ ->
                root["version"] = "v3"
            }.await()
            assertNotEquals(document1.toJson(), document2.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()
            document1.close()
            document2.close()
            client1.close()
            client2.close()
        }
    }

    @Test
    fun test_applying_previous_changes_after_switching_to_realtime() = runBlocking {
        val client1 = createClient()
        val client2 = createClient()
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document1 = Document(documentKey)
        val document2 = Document(documentKey)

        client1.activateAsync().await()
        client2.activateAsync().await()

        val client2Events = mutableListOf<Client.Event>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client2.events.filterIsInstance<DocumentSynced>().collect(client2Events::add)
        }

        suspend fun assertDocument2IsSynced(expected: String) {
            withTimeout(GENERAL_TIMEOUT) {
                while (client2Events.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<DocumentSynced>(client2Events.first())
            assertJsonContentEquals(expected, document2.toJson())
        }

        // 01. c2 attach the doc with realtime sync mode at first.
        client1.attachAsync(document1, syncMode = Manual).await()
        client2.attachAsync(document2).await()
        document1.updateAsync { root, _ ->
            root["version"] = "v1"
        }.await()
        client1.syncAsync().await()
        assertJsonContentEquals("""{"version":"v1"}""", document1.toJson())
        assertDocument2IsSynced("""{"version":"v1"}""")

        // 02. c2 pauses realtime sync mode. So, c2 doesn't get the changes of c1.
        client2.changeSyncMode(document2, Manual)
        document1.updateAsync { root, _ ->
            root["version"] = "v2"
        }.await()
        client1.syncAsync().await()
        assertJsonContentEquals("""{"version":"v2"}""", document1.toJson())
        assertJsonContentEquals("""{"version":"v1"}""", document2.toJson())

        // 03. c2 resumes realtime sync mode.
        // c2 should be able to apply changes made to the document while c2 is not in realtime sync.
        client2Events.clear()
        client2.changeSyncMode(document2, Realtime)
        assertDocument2IsSynced("""{"version":"v2"}""")

        // 04. c2 should automatically synchronize changes.
        client2Events.clear()
        document1.updateAsync { root, _ ->
            root["version"] = "v3"
        }.await()
        client1.syncAsync().await()
        assertDocument2IsSynced("""{"version":"v3"}""")

        client1.deactivateAsync().await()
        client2.deactivateAsync().await()

        document1.close()
        document2.close()
        client1.close()
        client2.close()
        collectJob.cancel()
    }

    @Test
    fun test_change_sync_mode_in_realtime() {
        withTwoClientsAndDocuments { c1, c2, d1, d2, key ->
            val c3 = createClient()
            c3.activateAsync().await()

            // 01. c1, c2, c3 attach to the same document in realtime sync.
            val d3 = Document(key)
            c3.attachAsync(d3).await()

            val d1Events = mutableListOf<Document.Event>()
            val d2Events = mutableListOf<Document.Event>()
            val d3Events = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterNot { it is Document.Event.PresenceChange }
                        .collect(d1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterNot { it is Document.Event.PresenceChange }
                        .collect(d2Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d3.events.filterNot { it is Document.Event.PresenceChange }
                        .collect(d3Events::add)
                },
            )

            // 02. [Step1] c1, c2, c3 sync in realtime.
            d1.updateAsync { root, _ ->
                root["c1"] = 0
            }.await()
            d2.updateAsync { root, _ ->
                root["c2"] = 0
            }.await()
            d3.updateAsync { root, _ ->
                root["c3"] = 0
            }.await()
            withTimeout(GENERAL_TIMEOUT) {
                // 1 LocalChange, 2 RemoteChanges
                while (d1Events.size < 3 || d2Events.size < 3 || d3Events.size < 3) {
                    delay(50)
                }
            }
            assertJsonContentEquals("""{"c1":0,"c2":0,"c3":0}""", d1.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":0,"c3":0}""", d2.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":0,"c3":0}""", d3.toJson())

            // 03. [Step2] c1 sync with push-only mode, c2 sync with sync-off mode.
            // c3 can get the changes of c1 and c2, because c3 sync with push-pull mode.
            c1.changeSyncMode(d1, RealtimePushOnly)
            c2.changeSyncMode(d2, RealtimeSyncOff)
            d1.updateAsync { root, _ ->
                root["c1"] = 1
            }.await()
            d2.updateAsync { root, _ ->
                root["c2"] = 1
            }.await()
            d3.updateAsync { root, _ ->
                root["c3"] = 1
            }.await()
            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 4 || d2Events.size < 4 || d3Events.size < 5) {
                    delay(50)
                }
            }
            assertJsonContentEquals("""{"c1":1,"c2":0,"c3":0}""", d1.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":1,"c3":0}""", d2.toJson())
            assertJsonContentEquals("""{"c1":1,"c2":0,"c3":1}""", d3.toJson())

            // 04. [Step3] c1 sync with sync-off mode, c2 sync with push-only mode.
            c1.changeSyncMode(d1, RealtimeSyncOff)
            c2.changeSyncMode(d2, RealtimePushOnly)
            d1.updateAsync { root, _ ->
                root["c1"] = 2
            }.await()
            d2.updateAsync { root, _ ->
                root["c2"] = 2
            }.await()
            d3.updateAsync { root, _ ->
                root["c3"] = 2
            }.await()
            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 5 || d2Events.size < 5 || d3Events.size < 8) {
                    delay(50)
                }
            }
            assertJsonContentEquals("""{"c1":2,"c2":0,"c3":0}""", d1.toJson())
            assertJsonContentEquals("""{"c1":0,"c2":2,"c3":0}""", d2.toJson())
            assertJsonContentEquals("""{"c1":1,"c2":2,"c3":2}""", d3.toJson())

            // 05. [Step4] c1 and c2 sync with push-pull mode.
            c1.changeSyncMode(d1, Realtime)
            c2.changeSyncMode(d2, Realtime)
            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 9 || d2Events.size < 9 || d3Events.size < 9) {
                    delay(50)
                }
            }
            assertJsonContentEquals("""{"c1":2,"c2":2,"c3":2}""", d1.toJson())
            assertJsonContentEquals("""{"c1":2,"c2":2,"c3":2}""", d2.toJson())
            assertJsonContentEquals("""{"c1":2,"c2":2,"c3":2}""", d3.toJson())

            c3.detachAsync(d3).await()
            c3.deactivateAsync().await()
            c3.close()
            d3.close()
            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_prevent_remote_change_in_push_only_mode() {
        withTwoClientsAndDocuments { c1, c2, d1, d2, _ ->
            val d1Events = mutableListOf<Document.Event>()
            val d2Events = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterNot { it is Document.Event.PresenceChange }
                        .collect(d1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterNot { it is Document.Event.PresenceChange }
                        .collect(d2Events::add)
                },
            )

            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") { text { "12" } }
                        element("p") { text { "34" } }
                    },
                )
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d2Events.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<RemoteChange>(d2Events.first())
            assertTreesXmlEquals("<doc><p>12</p><p>34</p></doc>", d1, d2)

            d1.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, text { "a" })
            }.await()
            c1.syncAsync().await()

            // Simulate the situation in the runSyncLoop where a pushpull request has been sent
            // but a response has not yet been received.
            d2Events.clear()
            val deferred = c2.syncAsync()
            c2.changeSyncMode(d2, RealtimePushOnly)
            deferred.await()

            // In push-only mode, remote-change events should not occur.
            d2Events.clear()
            c2.changeSyncMode(d2, RealtimePushOnly)

            delay(100) // Keep the push-only state.
            assertTrue(d2Events.none { it is RemoteChange })

            c2.changeSyncMode(d2, Realtime)

            d2.updateAsync { root, _ ->
                root.rootTree().edit(2, 2, text { "b" })
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.size < 3) {
                    delay(50)
                }
            }
            assertIs<RemoteChange>(d1Events.last())
            assertTreesXmlEquals("<doc><p>1ba2</p><p>34</p></doc>", d1)

            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_concurrent_deletions() {
        withTwoClientsAndDocuments { c1, c2, d1, d2, _ ->
            repeat(10) { repeat ->
                d1.updateAsync { root, _ ->
                    root.setNewTree(
                        "t",
                        element("doc") {
                            repeat(100) {
                                text { "1" }
                            }
                        },
                    )
                }.await()

                while (d1.toJson() != d2.toJson()) {
                    delay(100)
                }

                listOf(
                    launch {
                        c1.changeSyncMode(d1, RealtimePushOnly)
                        d1.updateAsync { root, _ ->
                            val tree = root.getAs<JsonTree>("t")
                            val size = (tree.rootTreeNode as JsonTree.ElementNode).children.size
                            if (size > 99) {
                                tree.editByPath(
                                    listOf(99),
                                    listOf(100),
                                )
                            }
                        }.await()
                        c1.changeSyncMode(d1, Realtime)
                        delay(10)

                        c1.changeSyncMode(d1, RealtimePushOnly)
                        d1.updateAsync { root, _ ->
                            val tree = root.getAs<JsonTree>("t")
                            val size = (tree.rootTreeNode as JsonTree.ElementNode).children.size
                            if (size > 31) {
                                tree.editByPath(
                                    listOf(30),
                                    listOf(99.coerceAtMost(size)),
                                )
                            }
                        }.await()
                        c1.changeSyncMode(d1, Realtime)
                        delay(10)

                        c1.changeSyncMode(d1, RealtimePushOnly)
                        d1.updateAsync { root, _ ->
                            val tree = root.getAs<JsonTree>("t")
                            val size = (tree.rootTreeNode as JsonTree.ElementNode).children.size
                            if (size > 0) {
                                tree.editByPath(
                                    listOf(0),
                                    listOf(30.coerceAtMost(size)),
                                )
                            }
                        }.await()
                        c1.changeSyncMode(d1, Realtime)
                    },
                    launch {
                        repeat(100) {
                            c2.changeSyncMode(d2, RealtimePushOnly)
                            d2.updateAsync { root, _ ->
                                val tree = root.getAs<JsonTree>("t")
                                val size = (tree.rootTreeNode as JsonTree.ElementNode).children.size
                                if (size > 0) {
                                    tree.editByPath(
                                        listOf((100 - it - 1).coerceIn(0 until size)),
                                        listOf((100 - it).coerceIn(1..size)),
                                    )
                                }
                            }.await()
                            c2.changeSyncMode(d2, Realtime)
                            delay(10)
                        }
                    },
                ).joinAll()

                suspend fun checkEmpty(document: Document): Boolean {
                    return (
                        document.getRoot()
                            .getAs<JsonTree>("t").rootTreeNode as JsonTree.ElementNode
                        ).children.isEmpty()
                }

                withTimeoutOrNull(15_000) {
                    while (!checkEmpty(d1) || !checkEmpty(d2)) {
                        delay(100)
                    }
                } ?: run {
                    error(
                        "empty check failed on ${repeat + 1}th test\n" +
                            "d1: ${d1.toJson()}\nd2: ${d2.toJson()}",
                    )
                }

                assertTrue(checkEmpty(d1))
                assertTrue(checkEmpty(d2))

                listOf(
                    launch {
                        d1.updateAsync { root, _ ->
                            val tree = root.getAs<JsonTree>("t")
                            tree.editByPath(
                                listOf(0),
                                listOf(0),
                                text { "0" },
                            )
                        }.await()
                    },
                    launch {
                        d2.updateAsync { root, _ ->
                            val tree = root.getAs<JsonTree>("t")
                            tree.editByPath(
                                listOf(0),
                                listOf(0),
                                text { "2" },
                            )
                        }.await()
                    },
                ).joinAll()

                withTimeoutOrNull(15_000) {
                    while (d1.toJson() != d2.toJson()) {
                        delay(100)
                    }
                } ?: run {
                    error("failed on ${repeat + 1}th test\nd1: ${d1.toJson()}\nd2: ${d2.toJson()}")
                }

                assertFalse(checkEmpty(d1))
                assertFalse(checkEmpty(d2))

                assertEquals(d1.toJson(), d2.toJson())
            }
        }
    }

    @Test
    fun test_not_include_changes_from_push_only_after_switching_to_realtime() {
        runBlocking {
            val c1 = createClient()
            c1.activateAsync().await()

            // 01. cli attach to the document having counter.
            val docKey = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(docKey)
            c1.attachAsync(d1, syncMode = Manual).await()

            // 02. cli update the document with creating a counter
            //     and sync with push-pull mode: CP(1, 1) -> CP(2, 2)
            d1.updateAsync { root, _ ->
                root.setNewCounter("counter", 0)
            }.await()

            var checkPoint = d1.checkPoint
            assertEquals(1u, checkPoint.clientSeq)
            assertEquals(1, checkPoint.serverSeq)

            c1.syncAsync().await()
            checkPoint = d1.checkPoint
            assertEquals(2u, checkPoint.clientSeq)
            assertEquals(2, checkPoint.serverSeq)

            // 03. cli update the document with increasing the counter(0 -> 1)
            //     and sync with push-only mode: CP(2, 2) -> CP(3, 2)
            val c1Events = mutableListOf<Client.Event>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                c1.events.filterIsInstance<DocumentSynced>().collect(c1Events::add)
            }
            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(1)
            }.await()
            var changePack = d1.createChangePack()
            assertEquals(1, changePack.changes.size)
            c1.changeSyncMode(d1, RealtimePushOnly)
            withTimeout(GENERAL_TIMEOUT) {
                while (c1Events.firstOrNull() !is DocumentSynced) {
                    delay(50)
                }
            }
            checkPoint = d1.checkPoint
            assertEquals(3u, checkPoint.clientSeq)
            assertEquals(2, checkPoint.serverSeq)
            c1.changeSyncMode(d1, Manual)

            // 04. cli update the document with increasing the counter(1 -> 2)
            //     and sync with push-pull mode. CP(3, 2) -> CP(4, 4)
            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(1)
            }.await()

            // The previous increase(0 -> 1) is already pushed to the server,
            // so the ChangePack of the request only has the increase(1 -> 2).
            changePack = d1.createChangePack()
            assertEquals(1, changePack.changes.size)

            c1.syncAsync().await()
            checkPoint = d1.checkPoint
            assertEquals(4u, checkPoint.clientSeq)
            assertEquals(4, checkPoint.serverSeq)
            assertEquals(2, d1.getRoot().getAs<JsonCounter>("counter").value)

            collectJob.cancel()
            c1.detachAsync(d1).await()
            c1.deactivateAsync().await()
            c1.close()
            d1.close()
        }
    }
}
