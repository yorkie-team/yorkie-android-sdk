package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisablePresenceTest {

    @Test
    fun test_disable_presence_attachment_drops_presence_but_keeps_operations() {
        runBlocking {
            val client = createClient()
            client.activateAsync().await()

            val documentKey = "disable-presence-${UUID.randomUUID()}".toDocKey()
            val document = Document(documentKey)

            // Attaching with disablePresence must not push the initial presence
            // and subsequent presence updates are dropped, while document
            // operations still apply.
            client.attachDocument(
                document,
                initialPresence = mapOf("cursor" to "0"),
                disablePresence = true,
            ).await()

            document.updateAsync { root, presence ->
                root["k1"] = 1
                presence.put(mapOf("cursor" to "1"))
            }.await()
            client.syncAsync(document).await()

            assertEquals("""{"k1":1}""", document.toJson())
            assertTrue(document.myPresence.isEmpty())

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun test_presence_disabled_state_is_fixated_by_the_server_across_attaches() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            c1.activateAsync().await()
            c2.activateAsync().await()

            val documentKey = "disable-presence-fixate-${UUID.randomUUID()}".toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)

            // First attach fixates disablePresence=true on the server.
            c1.attachDocument(d1, disablePresence = true).await()

            // Second attach requests presence but the server-fixated value wins,
            // so presence stays dropped for this document.
            c2.attachDocument(
                d2,
                initialPresence = mapOf("cursor" to "1"),
                disablePresence = false,
            ).await()

            d2.updateAsync { _, presence ->
                presence.put(mapOf("cursor" to "2"))
            }.await()
            c2.syncAsync(d2).await()

            assertTrue(d2.myPresence.isEmpty())

            c1.detachDocument(d1).await()
            c2.detachDocument(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }
}
