package dev.yorkie.core

import androidx.test.platform.app.InstrumentationRegistry
import dev.yorkie.document.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.util.UUID

fun createClient(presence: Presence? = null) = Client(
    InstrumentationRegistry.getInstrumentation().targetContext,
    "10.0.2.2",
    8080,
    usePlainText = true,
    options = Client.Options(presence = presence),
)

fun String.toDocKey(): Document.Key {
    return Document.Key(
        lowercase().replace("[^a-z\\d-]".toRegex(), "-")
            .substring(0, length.coerceAtMost(120)),
    )
}

fun withTwoClientsAndDocuments(
    detachDocuments: Boolean = true,
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

        client1.attachAsync(document1).await()
        client2.attachAsync(document2).await()

        callback.invoke(this, client1, client2, document1, document2, documentKey)

        if (detachDocuments) {
            client1.detachAsync(document1).await()
            client2.detachAsync(document2).await()
        }
        client1.deactivateAsync().await()
        client2.deactivateAsync().await()
    }
}
