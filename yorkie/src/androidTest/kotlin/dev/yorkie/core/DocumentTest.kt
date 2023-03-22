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
import kotlin.test.assertFalse
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
            assertFailsWith(IllegalArgumentException::class) {
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
            assertFailsWith(IllegalArgumentException::class) {
                document.updateAsync { it["key"] = 0 }.await()
            }

            // 05. try to attach a removed document.
            assertFailsWith(IllegalArgumentException::class) {
                client.attachAsync(document).await()
            }

            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
    }

    @Test
    fun test_creating_document_with_removed_document_key() {
        withTwoClientsAndDocuments { client1, _, document1, document2, key ->
            document1.updateAsync { it["key"] = 1 }.await()
            assertTrue(client1.removeAsync(document1).await())

            val document3 = Document(key)
            client1.attachAsync(document3).await()

            val doc2Content = document2.toJson()
            val doc3Content = document3.toJson()
            assertEquals(doc2Content, doc3Content)
        }
    }

    @Test
    fun test_removed_document_push_and_pull() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, _ ->
            document1.updateAsync { it["key"] = 1 }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            document1.updateAsync { it["key"] = 2 }.await()
            client1.removeAsync(document1).await()

            client2.syncAsync().await()
            assertEquals(document1.status, document2.status)
        }
    }

    @Test
    fun test_removed_document_detachment() {
        withTwoClientsAndDocuments { client1, client2, document1, document2, _ ->
            document1.updateAsync { it["key"] = 1 }.await()

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
        withTwoClientsAndDocuments { client1, client2, document1, document2, _ ->
            document1.updateAsync { it["key"] = 1 }.await()

            client1.syncAsync().await()
            client2.syncAsync().await()
            assertEquals(document1.toJson(), document2.toJson())

            client1.removeAsync(document1).await()
            client2.removeAsync(document2).await()
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
            assertFalse(client.syncAsync().await())
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
            assertFalse(client.syncAsync().await())
            assertFailsWith(IllegalArgumentException::class) {
                client.detachAsync(document).await()
            }

            client.detachAsync(document).await()
            client.deactivateAsync().await()
        }
    }
}
