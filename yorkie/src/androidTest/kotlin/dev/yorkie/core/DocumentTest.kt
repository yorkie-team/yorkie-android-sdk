package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.document.Document.DocumentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
                document.updateAsync {
                    it["key"] = 0
                }.await()
            }

            // 05. try to attach a removed document.
            assertFailsWith(IllegalArgumentException::class) {
                client.attachAsync(document).await()
            }

            client.deactivateAsync().await()
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
            document1.updateAsync {
                it["key"] = 1
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
        }
    }

    @Test
    fun test_removed_document_push_and_pull() {
        withTwoClientsAndDocuments(false) { client1, client2, document1, document2, _ ->
            document1.updateAsync {
                it["key"] = 1
            }.await()
            assertEquals("""{"key":1}""", document1.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync {
                it["key"] = 2
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
            document1.updateAsync {
                it["key"] = 1
            }.await()
            assertEquals("""{"key":1}""", document1.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            client1.removeAsync(document1).await()
            client2.detachAsync(document2).await()
            assertEquals(DocumentStatus.Removed, document1.status)
            assertEquals(DocumentStatus.Removed, document2.status)
        }
    }

    @Test
    fun test_removing_already_removed_document() {
        withTwoClientsAndDocuments(false) { client1, client2, document1, document2, _ ->
            document1.updateAsync {
                it["key"] = 1
            }.await()
            assertEquals("""{"key":1}""", document1.toJson())

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            assertTrue(client1.removeAsync(document1).await())
            assertTrue(client2.removeAsync(document2).await())
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
        }
    }
}
