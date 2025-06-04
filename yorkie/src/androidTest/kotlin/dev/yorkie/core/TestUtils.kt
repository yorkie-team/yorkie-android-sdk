package dev.yorkie.core

import dev.yorkie.document.Document
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.createSingleThreadDispatcher
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol

const val DEFAULT_SNAPSHOT_THRESHOLD = 1_000
const val GENERAL_TIMEOUT = 3_000L

fun createClient(): Client {
    val unaryClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    return Client(
        options = Client.Options(),
        host = "http://10.0.2.2:8080",
        unaryClient = unaryClient,
        streamClient = unaryClient,
        dispatcher = createSingleThreadDispatcher("YorkieClient"),
    )
}

fun String.toDocKey(): Document.Key {
    return Document.Key(
        lowercase().replace("[^a-z\\d-]".toRegex(), "-")
            .substring(0, length.coerceAtMost(120)),
    )
}

fun createTwoClientsAndDocuments(
    callback: suspend CoroutineScope.(Client, Client, Document, Document, Document.Key) -> Unit,
) {
    runBlocking {
        val client1 = createClient()
        val client2 = createClient()
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document1 = Document(documentKey)
        val document2 = Document(documentKey)

        callback.invoke(this, client1, client2, document1, document2, documentKey)
    }
}

fun createThreeClientsAndDocuments(
    callback: suspend CoroutineScope.(
        Client,
        Client,
        Client,
        Document,
        Document,
        Document,
        Document.Key,
    ) -> Unit,
) {
    runBlocking {
        val client1 = createClient()
        val client2 = createClient()
        val client3 = createClient()
        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document1 = Document(documentKey)
        val document2 = Document(documentKey)
        val document3 = Document(documentKey)

        callback.invoke(
            this,
            client1,
            client2,
            client3,
            document1,
            document2,
            document3,
            documentKey,
        )
    }
}

fun withTwoClientsAndDocuments(
    attachDocuments: Boolean = true,
    detachDocuments: Boolean = true,
    syncMode: Client.SyncMode = Client.SyncMode.Realtime,
    presences: Pair<Map<String, String>, Map<String, String>> = Pair(emptyMap(), emptyMap()),
    callback: suspend CoroutineScope.(Client, Client, Document, Document, Document.Key) -> Unit,
) {
    createTwoClientsAndDocuments { client1, client2, document1, document2, key ->
        client1.activateAsync().await()
        client2.activateAsync().await()

        if (attachDocuments) {
            client1.attachAsync(
                document1,
                syncMode = syncMode,
                initialPresence = presences.first,
            ).await()
            client2.attachAsync(
                document2,
                syncMode = syncMode,
                initialPresence = presences.second,
            ).await()
        }

        callback.invoke(this, client1, client2, document1, document2, key)

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

fun withThreeClientsAndDocuments(
    callback: suspend CoroutineScope.(
        Client,
        Client,
        Client,
        Document,
        Document,
        Document,
        Document.Key,
    ) -> Unit,
) {
    createThreeClientsAndDocuments { client1, client2, client3, document1, document2, document3, key ->
        client1.activateAsync().await()
        client2.activateAsync().await()
        client3.activateAsync().await()

        client1.attachAsync(document1).await()
        client2.attachAsync(document2).await()
        client3.attachAsync(document3).await()

        callback.invoke(
            this,
            client1,
            client2,
            client3,
            document1,
            document2,
            document3,
            key,
        )

        client1.detachAsync(document1).await()
        client2.detachAsync(document2).await()
        client3.detachAsync(document3).await()

        client1.deactivateAsync().await()
        client2.deactivateAsync().await()
        client3.deactivateAsync().await()

        document1.close()
        document2.close()
        document3.close()
        client1.close()
        client2.close()
        client3.close()
    }
}

fun versionVectorHelper(
    versionVector: VersionVector,
    actorData: Array<Pair<String, Long>>,
): Boolean {
    if (versionVector.size() != actorData.size) {
        return false
    }

    for ((actor, lamport) in actorData) {
        val vvLamport = versionVector.get(actor) ?: return false
        if (vvLamport != lamport) {
            return false
        }
    }
    return true
}
