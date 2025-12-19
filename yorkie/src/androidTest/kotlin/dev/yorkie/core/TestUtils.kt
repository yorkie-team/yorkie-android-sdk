package dev.yorkie.core

import androidx.test.platform.app.InstrumentationRegistry
import dev.yorkie.document.Document
import dev.yorkie.document.time.VersionVector
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

const val DEFAULT_SNAPSHOT_THRESHOLD = 1_000
const val GENERAL_TIMEOUT = 3_000L

/**
 * Gets a test configuration value from instrumentation arguments.
 *
 * For Android instrumentation tests, configuration is passed via testInstrumentationRunnerArguments
 * which are read from local.properties at build time and passed to the test runner.
 *
 * This avoids polluting the main BuildConfig with test-only configuration.
 */
private fun getTestArgument(key: String, defaultValue: String = ""): String {
    return InstrumentationRegistry.getArguments().getString(key)
        ?: defaultValue.takeIf { it.isNotEmpty() }
        ?: error("$key not found in instrumentation arguments")
}

/**
 * Gets the Yorkie server URL for tests
 */
fun getYorkieServerUrl(): String = getTestArgument("YORKIE_SERVER_URL")

const val TEST_API_ID = "admin"
const val TEST_API_PW = "admin"

fun createClient(options: Client.Options = Client.Options()): Client {
    return Client(
        options = options,
        host = getYorkieServerUrl(),
    )
}

fun String.toDocKey(): String {
    return lowercase().replace("[^a-z\\d-]".toRegex(), "-")
        .substring(0, length.coerceAtMost(120))
}

fun createTwoClientsAndDocuments(
    callback: suspend CoroutineScope.(Client, Client, Document, Document, String) -> Unit,
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
        String,
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
    callback: suspend CoroutineScope.(Client, Client, Document, Document, String) -> Unit,
) {
    createTwoClientsAndDocuments { client1, client2, document1, document2, key ->
        client1.activateAsync().await()
        client2.activateAsync().await()

        if (attachDocuments) {
            client1.attachDocument(
                document1,
                syncMode = syncMode,
                initialPresence = presences.first,
            ).await()
            client2.attachDocument(
                document2,
                syncMode = syncMode,
                initialPresence = presences.second,
            ).await()
        }

        callback.invoke(this, client1, client2, document1, document2, key)

        if (detachDocuments) {
            client1.detachDocument(document1).await()
            client2.detachDocument(document2).await()
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
    syncMode: Client.SyncMode = Client.SyncMode.Realtime,
    callback: suspend CoroutineScope.(
        Client,
        Client,
        Client,
        Document,
        Document,
        Document,
        String,
    ) -> Unit,
) {
    createThreeClientsAndDocuments {
            client1,
            client2,
            client3,
            document1,
            document2,
            document3,
            key,
        ->
        client1.activateAsync().await()
        client2.activateAsync().await()
        client3.activateAsync().await()

        client1.attachDocument(
            document = document1,
            syncMode = syncMode,
        ).await()
        client2.attachDocument(
            document = document2,
            syncMode = syncMode,
        ).await()
        client3.attachDocument(
            document = document3,
            syncMode = syncMode,
        ).await()

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

        client1.detachDocument(document1).await()
        client2.detachDocument(document2).await()
        client3.detachDocument(document3).await()

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
