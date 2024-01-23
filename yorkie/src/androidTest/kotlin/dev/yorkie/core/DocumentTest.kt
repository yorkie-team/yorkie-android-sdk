package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.Document
import dev.yorkie.document.Document.DocumentStatus
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            assertFailsWith(IllegalStateException::class) {
                client.removeAsync(document).await()
            }

            // 02. document is not attached.
            client.activateAsync().await()
            assertFailsWith(IllegalArgumentException::class) {
                client.removeAsync(document).await()
            }

            // 03. document is attached.
            client.attachAsync(document).await()
            client.removeAsync(document).await()
            assertEquals(DocumentStatus.Removed, document.status)

            // 04. try to update a removed document.
            assertFailsWith(IllegalStateException::class) {
                document.updateAsync { root, _ ->
                    root["key"] = 0
                }.await()
            }

            // 05. try to attach a removed document.
            assertFailsWith(IllegalArgumentException::class) {
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
        withTwoClientsAndDocuments(false) { client1, client2, document1, document2, _ ->
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
            assertEquals(DocumentStatus.Removed, document1.status)

            client2.syncAsync().await()
            assertEquals("""{"key":2}""", document2.toJson())
            assertEquals(DocumentStatus.Removed, document2.status)
        }
    }

    @Test
    fun test_removed_document_detachment() {
        withTwoClientsAndDocuments(false) { client1, client2, document1, document2, _ ->
            document1.updateAsync { root, _ ->
                root["key"] = 1
            }.await()
            assertEquals("""{"key":1}""", document1.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            client1.removeAsync(document1).await()
            if (document2.status != DocumentStatus.Removed) {
                client2.detachAsync(document2).await()
            }
            assertEquals(DocumentStatus.Removed, document1.status)
            assertEquals(DocumentStatus.Removed, document2.status)
        }
    }

    @Test
    fun test_removing_already_removed_document() {
        withTwoClientsAndDocuments(false) { client1, client2, document1, document2, _ ->
            document1.updateAsync { root, _ ->
                root["key"] = 1
            }.await()
            assertEquals("""{"key":1}""", document1.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            client1.removeAsync(document1).await()
            if (document2.status == DocumentStatus.Attached) {
                client2.removeAsync(document2).await()
            }
            assertEquals(DocumentStatus.Removed, document1.status)
            assertEquals(DocumentStatus.Removed, document2.status)
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
            assertFailsWith(IllegalArgumentException::class) {
                client.detachAsync(document).await()
            }
            assertFailsWith(IllegalArgumentException::class) {
                client.syncAsync(document).await()
            }
            assertFailsWith(IllegalArgumentException::class) {
                client.removeAsync(document).await()
            }

            // 02. abnormal behavior on attached state
            client.attachAsync(document).await()
            assertFailsWith(IllegalArgumentException::class) {
                client.attachAsync(document).await()
            }

            // 03. abnormal behavior on removed state
            client.removeAsync(document).await()
            assertFailsWith(IllegalArgumentException::class) {
                client.removeAsync(document).await()
            }
            assertFailsWith(IllegalArgumentException::class) {
                client.syncAsync(document).await()
            }
            assertFailsWith(IllegalArgumentException::class) {
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

            withTimeout(2_000) {
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

            withTimeout(2_000) {
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

            withTimeout(2_000) {
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

            withTimeout(2_000) {
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

            withTimeout(2_000) {
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

            withTimeout(2_000) {
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

            withTimeout(2_000) {
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
}
