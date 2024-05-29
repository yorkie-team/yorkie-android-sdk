package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.Client.SyncMode.Realtime
import dev.yorkie.core.Client.SyncMode.RealtimePushOnly
import dev.yorkie.core.Client.SyncMode.RealtimeSyncOff
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.LocalChange
import dev.yorkie.document.Document.Event.RemoteChange
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTreeTest.Companion.assertTreesXmlEquals
import dev.yorkie.document.json.JsonTreeTest.Companion.rootTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
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
            val c1 = createClient()
            val c2 = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(key)
            val d2 = Document(key)

            val d1SyncEvents = mutableListOf<SyncStatusChanged>()
            val d2SyncEvents = mutableListOf<SyncStatusChanged>()
            val d1ChangeEvents = mutableListOf<Document.Event>()
            val d2ChangeEvents = mutableListOf<Document.Event>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<SyncStatusChanged>().collect(d1SyncEvents::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterIsInstance<SyncStatusChanged>().collect(d2SyncEvents::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filter { it is RemoteChange || it is LocalChange }
                        .collect(d1ChangeEvents::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filter { it is RemoteChange || it is LocalChange }
                        .collect(d2ChangeEvents::add)
                },
            )

            c1.activateAsync().await()
            c2.activateAsync().await()

            assertIs<Client.Status.Activated>(c1.status.value)
            assertIs<Client.Status.Activated>(c2.status.value)

            c1.attachAsync(d1).await()
            c2.attachAsync(d2).await()

            d1.updateAsync { root, _ ->
                root["k1"] = "v1"
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d2SyncEvents.none { it is SyncStatusChanged.Synced }) {
                    delay(50)
                }
            }

            val localSetEvent = assertIs<LocalChange>(d1ChangeEvents.last())
            val localSetOperation = assertIs<OperationInfo.SetOpInfo>(
                localSetEvent.changeInfo.operations.first(),
            )
            assertEquals("k1", localSetOperation.key)
            assertEquals("$", localSetEvent.changeInfo.operations.first().path)
            d1ChangeEvents.clear()

            val remoteSetEvent = assertIs<RemoteChange>(d2ChangeEvents.last())
            val remoteSetOperation = assertIs<OperationInfo.SetOpInfo>(
                remoteSetEvent.changeInfo.operations.first(),
            )
            assertEquals("k1", remoteSetOperation.key)
            d2ChangeEvents.clear()

            val root2 = d2.getRoot()
            assertEquals("v1", root2.getAs<JsonPrimitive>("k1").value)

            d1SyncEvents.clear()
            d2SyncEvents.clear()

            d2.updateAsync { root, _ ->
                root.remove("k1")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d1SyncEvents.none { it is SyncStatusChanged.Synced }) {
                    delay(50)
                }
            }
            withTimeout(GENERAL_TIMEOUT) {
                while (d2SyncEvents.isEmpty()) {
                    delay(50)
                }
            }
            val root1 = d1.getRoot()
            assertTrue(root1.keys.isEmpty())

            val remoteRemoveEvent = assertIs<RemoteChange>(d1ChangeEvents.first())
            val remoteRemoveOperation = assertIs<OperationInfo.RemoveOpInfo>(
                remoteRemoveEvent.changeInfo.operations.first(),
            )
            assertEquals(localSetOperation.executedAt, remoteRemoveOperation.executedAt)

            val localRemoveEvent = assertIs<LocalChange>(d2ChangeEvents.first())
            val localRemoveOperation = assertIs<OperationInfo.RemoveOpInfo>(
                localRemoveEvent.changeInfo.operations.first(),
            )
            assertEquals(remoteSetOperation.executedAt, localRemoveOperation.executedAt)

            assertEquals(1, d1.clone?.root?.getGarbageLength())
            assertEquals(1, d2.clone?.root?.getGarbageLength())

            c1.detachAsync(d1).await()
            c2.detachAsync(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            d1.close()
            d2.close()
            c1.close()
            c2.close()

            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_change_sync_mode_between_realtime_and_manual() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(key)
            val d2 = Document(key)

            c1.activateAsync().await()
            c2.activateAsync().await()

            // 01. c1 and c2 attach the doc with manual sync mode.
            //     c1 updates the doc, but c2 doesn't get until call sync manually.
            c1.attachAsync(d1, syncMode = Manual).await()
            c2.attachAsync(d2, syncMode = Manual).await()

            d1.updateAsync { root, _ ->
                root["version"] = "v1"
            }.await()
            assertJsonContentEquals("""{"version":"v1"}""", d1.toJson())
            assertJsonContentEquals("""{}""", d2.toJson())

            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(d1.toJson(), d2.toJson())

            // 02. c2 changes the sync mode to realtime sync mode.
            val d2SyncEvents = mutableListOf<SyncStatusChanged>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d2.events.filterIsInstance<SyncStatusChanged>().collect(d2SyncEvents::add)
            }

            c2.changeSyncMode(d2, Realtime)
            withTimeout(GENERAL_TIMEOUT) {
                while (d2SyncEvents.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<SyncStatusChanged.Synced>(d2SyncEvents.first())
            d2SyncEvents.clear()

            d1.updateAsync { root, _ ->
                root["version"] = "v2"
            }.await()
            c1.syncAsync().await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d2SyncEvents.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<SyncStatusChanged.Synced>(d2SyncEvents.last())
            assertEquals(d1.toJson(), d2.toJson())
            collectJob.cancel()

            // 03. c2 changes the sync mode to manual sync mode again.
            c2.changeSyncMode(d2, Manual)
            d1.updateAsync { root, _ ->
                root["version"] = "v3"
            }.await()
            assertNotEquals(d1.toJson(), d2.toJson())

            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(d1.toJson(), d2.toJson())

            c1.detachAsync(d1).await()
            c2.detachAsync(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            d1.close()
            d2.close()
            c1.close()
            c2.close()
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

        val document2Events = mutableListOf<Document.Event>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            document2.events.filterIsInstance<SyncStatusChanged>().collect(document2Events::add)
        }

        suspend fun assertDocument2IsSynced(expected: String) {
            withTimeout(GENERAL_TIMEOUT) {
                while (document2Events.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<SyncStatusChanged.Synced>(document2Events.first())
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
        document2Events.clear()
        client2.changeSyncMode(document2, Realtime)
        assertDocument2IsSynced("""{"version":"v2"}""")

        // 04. c2 should automatically synchronize changes.
        document2Events.clear()
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
                    d1.events.filter { it is RemoteChange || it is LocalChange }
                        .collect(d1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filter { it is RemoteChange || it is LocalChange }
                        .collect(d2Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d3.events.filter { it is RemoteChange || it is LocalChange }
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
                    d1.events.filter { it is RemoteChange || it is LocalChange }
                        .collect(d1Events::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filter { it is RemoteChange || it is LocalChange }
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
            val d1Events = mutableListOf<Document.Event>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d1.events.filterIsInstance<SyncStatusChanged>().collect(d1Events::add)
            }
            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(1)
            }.await()
            var changePack = d1.createChangePack()
            assertEquals(1, changePack.changes.size)
            c1.changeSyncMode(d1, RealtimePushOnly)
            withTimeout(GENERAL_TIMEOUT) {
                while (d1Events.firstOrNull() !is SyncStatusChanged.Synced) {
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

    @Test
    fun test_avoid_unnecessary_syncs_in_push_only() {
        withTwoClientsAndDocuments { c1, _, d1, d2, _ ->
            val d1SyncEvents = mutableListOf<SyncStatusChanged>()
            val d2SyncEvents = mutableListOf<SyncStatusChanged>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d1.events.filterIsInstance<SyncStatusChanged>().collect(d1SyncEvents::add)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    d2.events.filterIsInstance<SyncStatusChanged>().collect(d2SyncEvents::add)
                },
            )

            d1.updateAsync { root, _ ->
                root.setNewText("t").edit(0, 0, "a")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d2SyncEvents.isEmpty()) {
                    delay(50)
                }
            }

            fun JsonObject.rootText() = getAs<JsonText>("t")

            assertIs<SyncStatusChanged.Synced>(d2SyncEvents.last())
            assertEquals("a", d1.getRoot().rootText().toString())
            assertEquals("a", d2.getRoot().rootText().toString())

            d1SyncEvents.clear()
            c1.changeSyncMode(d1, RealtimePushOnly)
            d2.updateAsync { root, _ ->
                root.rootText().edit(1, 1, "b")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d2SyncEvents.size < 2) {
                    delay(50)
                }
            }
            assertIs<SyncStatusChanged.Synced>(d2SyncEvents.last())

            d2.updateAsync { root, _ ->
                root.rootText().edit(2, 2, "c")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (d2SyncEvents.size < 3) {
                    delay(50)
                }
            }
            assertIs<SyncStatusChanged.Synced>(d2SyncEvents.last())

            assertTrue(d1SyncEvents.isEmpty())
            c1.changeSyncMode(d1, Realtime)

            withTimeout(GENERAL_TIMEOUT) {
                while (d1SyncEvents.isEmpty()) {
                    delay(50)
                }
            }
            assertIs<SyncStatusChanged.Synced>(d1SyncEvents.last())
            assertEquals("abc", d1.getRoot().rootText().toString())
            assertEquals("abc", d2.getRoot().rootText().toString())

            collectJobs.forEach(Job::cancel)
        }
    }
}
