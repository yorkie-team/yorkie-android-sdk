package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.Document
import dev.yorkie.document.Document.DocStatus
import dev.yorkie.document.Document.Event
import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.operation.OperationInfo.AddOpInfo
import dev.yorkie.document.operation.OperationInfo.EditOpInfo
import dev.yorkie.document.operation.OperationInfo.IncreaseOpInfo
import dev.yorkie.document.operation.OperationInfo.MoveOpInfo
import dev.yorkie.document.operation.OperationInfo.RemoveOpInfo
import dev.yorkie.document.operation.OperationInfo.SetOpInfo
import dev.yorkie.document.operation.OperationInfo.StyleOpInfo
import dev.yorkie.util.YorkieException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentTest {

    @Test
    fun test_single_client_deleting_document() {
        runBlocking {
            val client = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)

            // 01. client is not activated.
            assertFailsWith(YorkieException::class) {
                client.removeAsync(document).await()
            }

            // 02. document is not attached.
            client.activateAsync().await()
            assertFailsWith(YorkieException::class) {
                client.removeAsync(document).await()
            }

            // 03. document is attached.
            client.attachAsync(document).await()
            client.removeAsync(document).await()
            assertEquals(DocStatus.Removed, document.status)

            // 04. try to update a removed document.
            assertFailsWith(YorkieException::class) {
                document.updateAsync { root, _ ->
                    root["key"] = 0
                }.await()
            }

            // 05. try to attach a removed document.
            assertFailsWith(YorkieException::class) {
                client.attachAsync(document).await()
            }

            client.deactivateAsync().await()
            document.close()
            client.close()
        }
    }

    @Test
    fun test_creating_document_with_removed_document_key() {
        runBlocking {
            // 01. client1 creates document1 and removes it.
            val client1 = createClient()
            client1.activateAsync().await()

            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document1 = Document(documentKey)
            document1.updateAsync { root, _ ->
                root["key"] = 1
            }.await()
            client1.attachAsync(document1).await()
            assertEquals("""{"key":1}""", document1.toJson())

            client1.removeAsync(document1).await()

            // 02. client2 creates document2 with the same key.
            val client2 = createClient()
            client2.activateAsync().await()
            val document2 = Document(documentKey)
            client2.attachAsync(document2).await()

            // 03. client1 creates document3 with the same key.
            val document3 = Document(documentKey)
            client1.attachAsync(document3).await()
            assertEquals("{}", document2.toJson())
            assertEquals("{}", document3.toJson())

            client1.deactivateAsync().await()
            client2.deactivateAsync().await()
            document1.close()
            document2.close()
            client1.close()
            client2.close()
        }
    }

    @Test
    fun test_removed_document_push_and_pull() {
        withTwoClientsAndDocuments(
            detachDocuments = false,
        ) { client1, client2, document1, document2, _ ->
            document1.updateAsync { root, _ ->
                root["key"] = 1
            }.await()
            assertEquals("""{"key":1}""", document1.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { root, _ ->
                root["key"] = 2
            }.await()
            client1.removeAsync(document1).await()
            assertEquals("""{"key":2}""", document1.toJson())
            assertEquals(DocStatus.Removed, document1.status)

            client2.syncAsync().await()
            assertEquals("""{"key":2}""", document2.toJson())
            assertEquals(DocStatus.Removed, document2.status)
        }
    }

    @Test
    fun test_removed_document_detachment() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)

            d1.updateAsync { root, _ ->
                root["key"] = 1
            }.await()
            c1.activateAsync().await()
            c1.attachAsync(d1, syncMode = Manual).await()
            assertEquals("""{"key":1}""", d1.toJson())

            c2.activateAsync().await()
            c2.attachAsync(d2, syncMode = Manual).await()
            assertEquals("""{"key":1}""", d2.toJson())

            c1.removeAsync(d1).await()
            c2.removeAsync(d2).await()

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertEquals(DocStatus.Removed, d1.status)
            assertEquals(DocStatus.Removed, d2.status)

            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            d1.close()
            d2.close()
            c1.close()
            c2.close()
        }
    }

    // State transition of document
    // ┌──────────┐ Attach ┌──────────┐ Remove ┌─────────┐
    // │ Detached ├───────►│ Attached ├───────►│ Removed │
    // └──────────┘        └─┬─┬──────┘        └─────────┘
    //           ▲           │ │     ▲
    //           └───────────┘ └─────┘
    //              Detach     PushPull
    @Test
    fun test_document_state_transition() {
        runBlocking {
            val client = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)

            client.activateAsync().await()

            // 01. abnormal behavior on detached state
            assertFailsWith(YorkieException::class) {
                client.detachAsync(document).await()
            }
            assertFailsWith(YorkieException::class) {
                client.syncAsync(document).await()
            }
            assertFailsWith(YorkieException::class) {
                client.removeAsync(document).await()
            }

            // 02. abnormal behavior on attached state
            client.attachAsync(document).await()
            assertFailsWith(YorkieException::class) {
                client.attachAsync(document).await()
            }

            // 03. abnormal behavior on removed state
            client.removeAsync(document).await()
            assertFailsWith(YorkieException::class) {
                client.removeAsync(document).await()
            }
            assertFailsWith(YorkieException::class) {
                client.syncAsync(document).await()
            }
            assertFailsWith(YorkieException::class) {
                client.detachAsync(document).await()
            }

            client.deactivateAsync().await()

            document.close()
            client.close()
        }
    }

    @Test
    fun test_document_event_stream() {
        withTwoClientsAndDocuments { _, _, document1, document2, _ ->
            val document1Ops = mutableListOf<OperationInfo>()
            val document2Ops = mutableListOf<OperationInfo>()
            val collectJobs = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document1Ops.addAll(it.changeInfo.operations)
                        }
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.events.filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document2Ops.addAll(it.changeInfo.operations)
                        }
                },
            )

            document1.updateAsync { root, _ ->
                root.setNewCounter("counter", 100)
                root.setNewArray("todos").apply {
                    put("todo1")
                    put("todo2")
                    put("todo3")
                }
                root.setNewText("content").edit(
                    0,
                    0,
                    "hello world",
                    mapOf(
                        "italic" to "true",
                    ),
                )
                root.setNewObject("obj").apply {
                    set("name", "josh")
                    set("age", 14)
                    setNewArray("food").apply {
                        put("apple")
                        put("grape")
                    }
                    setNewObject("score").apply {
                        set("english", 80)
                        set("math", 90)
                    }
                    setNewObject("score").apply {
                        set("science", 100)
                    }
                    remove("food")
                }
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document2Ops.size < 4) {
                    delay(50)
                }
            }

            document2.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(1)
                root.getAs<JsonArray>("todos").apply {
                    put("todo4")
                    val prevItem = requireNotNull(getAs<JsonPrimitive>(1))
                    val currItem = requireNotNull(getAs<JsonPrimitive>(0))
                    moveAfter(prevItem.target.id, currItem.target.id)
                }
                root.getAs<JsonText>("content").style(0, 5, mapOf("bold" to "true"))
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.size < 3) {
                    delay(50)
                }
            }

            assertJsonContentEquals(document1.toJson(), document2.toJson())
            val expectedDocument1Ops = listOf(
                IncreaseOpInfo(path = "$.counter", value = 1),
                AddOpInfo(path = "$.todos", index = 3),
                MoveOpInfo(path = "$.todos", index = 1, previousIndex = 0),
                StyleOpInfo(
                    path = "$.content",
                    from = 0,
                    to = 5,
                    attributes = mapOf("bold" to "true"),
                ),
            )
            val expectedDocument2Ops = listOf(
                SetOpInfo(path = "$", key = "counter"),
                SetOpInfo(path = "$", key = "todos"),
                AddOpInfo(path = "$.todos", index = 0),
                AddOpInfo(path = "$.todos", index = 1),
                AddOpInfo(path = "$.todos", index = 2),
                SetOpInfo(path = "$", key = "content"),
                EditOpInfo(
                    from = 0,
                    to = 0,
                    value = TextWithAttributes("hello world" to mapOf("italic" to "true")),
                    path = "$.content",
                ),
                SetOpInfo(path = "$", key = "obj"),
                SetOpInfo(path = "$.obj", key = "name"),
                SetOpInfo(path = "$.obj", key = "age"),
                SetOpInfo(path = "$.obj", key = "food"),
                AddOpInfo(path = "$.obj.food", index = 0),
                AddOpInfo(path = "$.obj.food", index = 1),
                SetOpInfo(path = "$.obj", key = "score"),
                SetOpInfo(path = "$.obj.score", key = "english"),
                SetOpInfo(path = "$.obj.score", key = "math"),
                SetOpInfo(path = "$.obj", key = "score"),
                SetOpInfo(path = "$.obj.score", key = "science"),
                RemoveOpInfo(path = "$.obj", key = "food", index = null),
            )
            assertEquals(expectedDocument1Ops, document1Ops)
            assertEquals(expectedDocument2Ops, document2Ops)

            collectJobs.forEach(Job::cancel)
        }
    }

    @Test
    fun test_document_event_stream_with_specific_topic() {
        withTwoClientsAndDocuments { _, _, document1, document2, _ ->
            val document1Ops = mutableListOf<OperationInfo>()
            val document1TodosOps = mutableListOf<OperationInfo>()
            val document1CounterOps = mutableListOf<OperationInfo>()
            val collectJobs = mapOf(
                "events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document1Ops.addAll(it.changeInfo.operations)
                        }
                },
                "todos" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events("$.todos").filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document1TodosOps.addAll(it.changeInfo.operations)
                        }
                },
                "counter" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events("$.counter").filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document1CounterOps.addAll(it.changeInfo.operations)
                        }
                },
            )

            document2.updateAsync { root, _ ->
                root.setNewCounter("counter", 0)
                root.setNewArray("todos").apply {
                    put("todo1")
                    put("todo2")
                }
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                // ops: counter, todos, todoOps: todos
                while (document1Ops.size < 2 || document1TodosOps.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    SetOpInfo(path = "$", key = "counter"),
                    SetOpInfo(path = "$", key = "todos"),
                    AddOpInfo(path = "$.todos", index = 0),
                    AddOpInfo(path = "$.todos", index = 1),
                ),
                document1Ops,
            )
            assertEquals(
                listOf<OperationInfo>(
                    AddOpInfo(path = "$.todos", index = 0),
                    AddOpInfo(path = "$.todos", index = 1),
                ),
                document1TodosOps,
            )
            document1Ops.clear()
            document1TodosOps.clear()

            document2.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(10)
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty() || document1CounterOps.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(IncreaseOpInfo(path = "$.counter", value = 10), document1Ops.first())
            assertEquals(
                IncreaseOpInfo(path = "$.counter", value = 10),
                document1CounterOps.first(),
            )
            document1Ops.clear()
            document1CounterOps.clear()

            document2.updateAsync { root, _ ->
                root.getAs<JsonArray>("todos").put("todo3")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty() || document1TodosOps.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(AddOpInfo(path = "$.todos", index = 2), document1Ops.first())
            assertEquals(AddOpInfo(path = "$.todos", index = 2), document1TodosOps.first())
            document1Ops.clear()
            document1TodosOps.clear()

            collectJobs["todos"]?.cancel()
            document2.updateAsync { root, _ ->
                root.getAs<JsonArray>("todos").put("todo4")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(AddOpInfo(path = "$.todos", index = 3), document1Ops.first())
            assert(document1TodosOps.isEmpty())
            document1Ops.clear()

            collectJobs["counter"]?.cancel()
            document2.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(10)
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(IncreaseOpInfo(path = "$.counter", value = 10), document1Ops.first())
            assert(document1CounterOps.isEmpty())

            collectJobs.values.forEach(Job::cancel)
        }
    }

    @Test
    fun test_document_event_stream_with_nested_topic() {
        withTwoClientsAndDocuments { _, _, document1, document2, _ ->
            val document1Ops = mutableListOf<OperationInfo>()
            val document1TodosOps = mutableListOf<OperationInfo>()
            val document1ObjOps = mutableListOf<OperationInfo>()
            val collectJobs = mapOf(
                "events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document1Ops.addAll(it.changeInfo.operations)
                        }
                },
                "todos" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events("$.todos.0").filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document1TodosOps.addAll(it.changeInfo.operations)
                        }
                },
                "obj" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events("$.obj.c1").filterIsInstance<Event.RemoteChange>()
                        .collect {
                            document1ObjOps.addAll(it.changeInfo.operations)
                        }
                },
            )

            document2.updateAsync { root, _ ->
                root.setNewArray("todos").putNewObject().apply {
                    set("text", "todo1")
                    set("completed", false)
                }
                root.setNewObject("obj").setNewObject("c1").apply {
                    set("name", "josh")
                    set("age", 14)
                }
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.size < 2 ||
                    document1TodosOps.isEmpty() ||
                    document1ObjOps.isEmpty()
                ) {
                    delay(50)
                }
            }

            assertEquals(
                listOf(
                    SetOpInfo(path = "$", key = "todos"),
                    AddOpInfo(path = "$.todos", index = 0),
                    SetOpInfo(path = "$.todos.0", key = "text"),
                    SetOpInfo(path = "$.todos.0", key = "completed"),
                    SetOpInfo(path = "$", key = "obj"),
                    SetOpInfo(path = "$.obj", key = "c1"),
                    SetOpInfo(path = "$.obj.c1", key = "name"),
                    SetOpInfo(path = "$.obj.c1", key = "age"),
                ),
                document1Ops,
            )
            assertEquals(
                listOf<OperationInfo>(
                    SetOpInfo(path = "$.todos.0", key = "text"),
                    SetOpInfo(path = "$.todos.0", key = "completed"),
                ),
                document1TodosOps,
            )
            assertEquals(
                listOf<OperationInfo>(
                    SetOpInfo(path = "$.obj.c1", key = "name"),
                    SetOpInfo(path = "$.obj.c1", key = "age"),
                ),
                document1ObjOps,
            )
            document1Ops.clear()
            document1TodosOps.clear()
            document1ObjOps.clear()

            document2.updateAsync { root, _ ->
                root.getAs<JsonObject>("obj").getAs<JsonObject>("c1")["name"] = "john"
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty() || document1ObjOps.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(SetOpInfo(path = "$.obj.c1", key = "name"), document1Ops.first())
            assertEquals(SetOpInfo(path = "$.obj.c1", key = "name"), document1ObjOps.first())
            document1Ops.clear()
            document1ObjOps.clear()

            document2.updateAsync { root, _ ->
                root.getAs<JsonArray>("todos").getAs<JsonObject>(0)?.set("completed", true)
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty() || document1TodosOps.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(SetOpInfo(path = "$.todos.0", key = "completed"), document1Ops.first())
            assertEquals(
                SetOpInfo(path = "$.todos.0", key = "completed"),
                document1TodosOps.first(),
            )
            document1Ops.clear()
            document1TodosOps.clear()

            collectJobs["todos"]?.cancel()
            document2.updateAsync { root, _ ->
                root.getAs<JsonArray>("todos").getAs<JsonObject>(0)?.set("text", "todo_1")
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(SetOpInfo(path = "$.todos.0", key = "text"), document1Ops.first())
            assert(document1TodosOps.isEmpty())
            document1Ops.clear()

            collectJobs["obj"]?.cancel()
            document2.updateAsync { root, _ ->
                root.getAs<JsonObject>("obj").getAs<JsonObject>("c1")["age"] = 15
            }.await()

            withTimeout(GENERAL_TIMEOUT) {
                while (document1Ops.isEmpty()) {
                    delay(50)
                }
            }

            assertEquals(SetOpInfo(path = "$.obj.c1", key = "age"), document1Ops.first())
            assert(document1ObjOps.isEmpty())

            collectJobs.values.forEach(Job::cancel)
        }
    }

    @Test
    fun test_single_document_event_stream_can_emit_document_status_changed() {
        runBlocking {
            val client = createClient()
            val documentKey = UUID.randomUUID().toString().toDocKey()
            val document = Document(documentKey)

            val documentStatusChangedList = mutableListOf<Event.DocumentStatusChanged>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                document.events.filterIsInstance<Event.DocumentStatusChanged>()
                    .collect {
                        documentStatusChangedList.add(it)
                    }
            }

            client.activateAsync().await()
            val actorID = client.requireClientId()

            // 1. Can receive DocumentStatus.Attached event when attached
            client.attachAsync(document, syncMode = Manual).await()
            assertEquals(1, documentStatusChangedList.size)
            assertEquals(DocStatus.Attached, documentStatusChangedList[0].docStatus)
            assertEquals(actorID, documentStatusChangedList[0].actorID)

            // 2. Can receive DocumentStatus.Detached event when detached
            client.detachAsync(document).await()
            assertEquals(2, documentStatusChangedList.size)
            assertEquals(DocStatus.Detached, documentStatusChangedList[1].docStatus)
            assertNull(documentStatusChangedList[1].actorID)

            // 3. Attach could fail if the document has been detached
            client.attachAsync(document).await()
            assertEquals(2, documentStatusChangedList.size)
            assertEquals(DocStatus.Detached, documentStatusChangedList[1].docStatus)
            assertNull(documentStatusChangedList[1].actorID)

            // 4. Exception should be thrown when trying to remove a detached document
            val exception = assertFailsWith(YorkieException::class) {
                client.removeAsync(document).await()
            }
            assertEquals(YorkieException.Code.ErrDocumentNotAttached, exception.code)

            client.deactivateAsync().await()
            document.close()
            client.close()
            job.cancel()
        }
    }

    @Test
    fun test_multiple_document_event_stream_can_emit_document_status_changed() {
        createTwoClientsAndDocuments { client1, client2, document1, document2, _ ->
            val document1StatusChangedList = mutableListOf<Event.DocumentStatusChanged>()
            val document2StatusChangedList = mutableListOf<Event.DocumentStatusChanged>()
            val jobs = mapOf(
                "document1 status changed events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.filterIsInstance<Event.DocumentStatusChanged>()
                        .collect {
                            document1StatusChangedList.add(it)
                        }
                },
                "document2 status changed events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.events.filterIsInstance<Event.DocumentStatusChanged>()
                        .collect {
                            document2StatusChangedList.add(it)
                        }
                },
            )

            client1.activateAsync().await()
            client2.activateAsync().await()
            val c1ID = client1.requireClientId()
            val c2ID = client2.requireClientId()

            // 1. Can receive DocumentStatus.Attached event when attached
            client1.attachAsync(document1, syncMode = Manual).await()
            client2.attachAsync(document2, syncMode = Manual).await()

            assertEquals(1, document1StatusChangedList.size)
            assertEquals(DocStatus.Attached, document1StatusChangedList[0].docStatus)
            assertEquals(c1ID, document1StatusChangedList[0].actorID)

            assertEquals(1, document2StatusChangedList.size)
            assertEquals(DocStatus.Attached, document2StatusChangedList[0].docStatus)
            assertEquals(c2ID, document2StatusChangedList[0].actorID)

            // 2. Can receive DocumentStatus.Detached event when detached
            client1.detachAsync(document1).await()
            assertEquals(2, document1StatusChangedList.size)
            assertEquals(DocStatus.Detached, document1StatusChangedList[1].docStatus)
            assertNull(document1StatusChangedList[1].actorID)

            // 3. Can receive DocumentStatus.Detached event when removed
            client2.deactivateAsync().await()
            assertEquals(2, document2StatusChangedList.size)
            assertEquals(DocStatus.Detached, document2StatusChangedList[1].docStatus)
            assertNull(document2StatusChangedList[1].actorID)

            // 4. Can receive events when other client attaches the same document
            val newDocumentKey = UUID.randomUUID().toString().toDocKey()
            val document3 = Document(newDocumentKey)
            val document4 = Document(newDocumentKey)
            val document3StatusChangedList = mutableListOf<Event.DocumentStatusChanged>()
            val document4StatusChangedList = mutableListOf<Event.DocumentStatusChanged>()

            val newJobs = mapOf(
                "document3 status changed events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document3.events.filterIsInstance<Event.DocumentStatusChanged>()
                        .collect {
                            document3StatusChangedList.add(it)
                        }
                },
                "document4 status changed events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document4.events.filterIsInstance<Event.DocumentStatusChanged>()
                        .collect {
                            document4StatusChangedList.add(it)
                        }
                },
            )
            client1.attachAsync(document3, syncMode = Manual).await()
            assertEquals(1, document3StatusChangedList.size)
            assertEquals(DocStatus.Attached, document3StatusChangedList[0].docStatus)
            assertEquals(c1ID, document3StatusChangedList[0].actorID)

            client2.activateAsync().await()
            client2.attachAsync(document4, syncMode = Manual).await()
            assertEquals(1, document4StatusChangedList.size)
            assertEquals(DocStatus.Attached, document4StatusChangedList[0].docStatus)
            assertEquals(c2ID, document4StatusChangedList[0].actorID)

            // 5. Can receive DocumentStatus.Removed event when removed
            client1.removeAsync(document3).await()
            assertEquals(2, document3StatusChangedList.size)
            assertEquals(DocStatus.Removed, document3StatusChangedList[1].docStatus)
            assertNull(document3StatusChangedList[1].actorID)

            // 6. Can receive DocumentStatus.Removed event another client syncs
            client2.syncAsync().await()
            assertEquals(2, document4StatusChangedList.size)
            assertEquals(DocStatus.Removed, document4StatusChangedList[1].docStatus)
            assertNull(document4StatusChangedList[1].actorID)

            // 7. DocumentStatus.Detached should not be emitted when deactivating the client and it's document is in the removed state
            val eventCount3 = document3StatusChangedList.size
            val eventCount4 = document4StatusChangedList.size
            client1.deactivateAsync().await()
            client2.deactivateAsync().await()
            assertEquals(eventCount3, document3StatusChangedList.size)
            assertEquals(eventCount4, document4StatusChangedList.size)

            document3.close()
            document4.close()

            jobs.values.forEach(Job::cancel)
            newJobs.values.forEach(Job::cancel)
        }
    }

    @Test
    fun test_document_event_stream_can_receive_document_status_changed() {
        createTwoClientsAndDocuments { client1, client2, document1, document2, _ ->
            val document1StatusChangedList = mutableListOf<Event.DocumentStatusChanged>()
            val document2StatusChangedList = mutableListOf<Event.DocumentStatusChanged>()
            val jobs = mapOf(
                "document1 status changed events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document1.events.filterIsInstance<Event.DocumentStatusChanged>()
                        .collect {
                            document1StatusChangedList.add(it)
                        }
                },
                "document2 status changed events" to launch(start = CoroutineStart.UNDISPATCHED) {
                    document2.events.filterIsInstance<Event.DocumentStatusChanged>()
                        .collect {
                            document2StatusChangedList.add(it)
                        }
                },
            )

            client1.activateAsync().await()
            client2.activateAsync().await()
            val c1ID = client1.requireClientId()
            val c2ID = client2.requireClientId()

            // 1. Can receive DocumentStatus.Attached event when attached
            client1.attachAsync(document1, syncMode = Manual).await()
            client2.attachAsync(document2, syncMode = Manual).await()

            assertEquals(true, client1.isActive)
            assertEquals(true, client2.isActive)

            assertEquals(1, document1StatusChangedList.size)
            assertEquals(DocStatus.Attached, document1StatusChangedList[0].docStatus)
            assertEquals(c1ID, document1StatusChangedList[0].actorID)

            assertEquals(1, document2StatusChangedList.size)
            assertEquals(DocStatus.Attached, document2StatusChangedList[0].docStatus)
            assertEquals(c2ID, document2StatusChangedList[0].actorID)

            // 2. Can receive DocumentStatus.Removed event when removed
            client1.removeAsync(document1).await()
            assertEquals(2, document1StatusChangedList.size)
            assertEquals(DocStatus.Removed, document1StatusChangedList[1].docStatus)
            assertNull(document1StatusChangedList[1].actorID)

            // 3. Can receive DocumentStatus.Detached event when deactivating the client after peer client removes the document
            client2.deactivateAsync().await()

            assertEquals(2, document2StatusChangedList.size)
            assertEquals(DocStatus.Detached, document2StatusChangedList[1].docStatus)

            jobs.values.forEach(Job::cancel)
        }
    }
}
