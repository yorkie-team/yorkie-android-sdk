package dev.yorkie.core

import androidx.test.platform.app.InstrumentationRegistry
import dev.yorkie.document.Document
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

const val GENERAL_TIMEOUT = 3_000L

fun createClient() = Client(
    InstrumentationRegistry.getInstrumentation().targetContext,
    "10.0.2.2",
    8080,
) {
    it.usePlaintext()
}

fun String.toDocKey(): Document.Key {
    return Document.Key(
        lowercase().replace("[^a-z\\d-]".toRegex(), "-")
            .substring(0, length.coerceAtMost(120)),
    )
}

fun withTwoClientsAndDocuments(
    detachDocuments: Boolean = true,
    realTimeSync: Boolean = true,
    presences: Pair<Map<String, String>, Map<String, String>> = Pair(emptyMap(), emptyMap()),
    callback: suspend CoroutineScope.(Client, Client, Document, Document, Document.Key) -> Unit,
) {
    runBlocking {
        val client1 = createClient()
        val client2 = createClient()
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document1 = Document(documentKey)
        val document2 = Document(documentKey)

        client1.activateAsync().await()
        client2.activateAsync().await()

        client1.attachAsync(
            document1,
            isRealTimeSync = realTimeSync,
            initialPresence = presences.first,
        ).await()
        client2.attachAsync(
            document2,
            isRealTimeSync = realTimeSync,
            initialPresence = presences.second,
        ).await()

        callback.invoke(this, client1, client2, document1, document2, documentKey)

        if (detachDocuments) {
            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
        }
        client1.deactivateAsync().await()
        client2.deactivateAsync().await()

        document1.close()
        document2.close()
        client1.close()
        client2.close()
    }
}
