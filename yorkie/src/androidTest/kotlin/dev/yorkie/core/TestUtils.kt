package dev.yorkie.core

import androidx.test.platform.app.InstrumentationRegistry
import dev.yorkie.document.Document
import dev.yorkie.document.time.VersionVector
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

const val DEFAULT_SNAPSHOT_THRESHOLD = 1_000
const val GENERAL_TIMEOUT = 15_000L

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
    createTwoClientsAndDocuments { rawClient1, rawClient2, rawDocument1, rawDocument2, key ->
        rawClient1.activateAsync().await()
        rawClient2.activateAsync().await()

        // The actor used to be the time-ordered session id (second-created client won
        // ties); the stable actor is derived from the client key, so creation order no
        // longer implies actor order — pin it so callback assertions that depend on a
        // consistent actor ordering (e.g. tie-breaks) stay meaningful (JS
        // `integration_helper.ts`; iOS `withTwoClientsAndDocuments`).
        val swap = rawClient1.requireActorId() > rawClient2.requireActorId()
        val client1 = if (swap) rawClient2 else rawClient1
        val client2 = if (swap) rawClient1 else rawClient2
        val document1 = if (swap) rawDocument2 else rawDocument1
        val document2 = if (swap) rawDocument1 else rawDocument2

        if (attachDocuments) {
            attachAndAwaitWatch(client1, document1, syncMode, presences.first)
            attachAndAwaitWatch(client2, document2, syncMode, presences.second)
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
            rawClient1,
            rawClient2,
            rawClient3,
            rawDocument1,
            rawDocument2,
            rawDocument3,
            key,
        ->
        rawClient1.activateAsync().await()
        rawClient2.activateAsync().await()
        rawClient3.activateAsync().await()

        // Sort the three (client, document) pairs ascending by the stable actor —
        // see the matching comment in withTwoClientsAndDocuments.
        val pairs = listOf(
            rawClient1 to rawDocument1,
            rawClient2 to rawDocument2,
            rawClient3 to rawDocument3,
        ).sortedBy { it.first.requireActorId() }
        val (client1, document1) = pairs[0]
        val (client2, document2) = pairs[1]
        val (client3, document3) = pairs[2]

        attachAndAwaitWatch(client1, document1, syncMode)
        attachAndAwaitWatch(client2, document2, syncMode)
        attachAndAwaitWatch(client3, document3, syncMode)

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

/**
 * Attaches [document] with [client] and, for every sync mode that opens a
 * watch stream (all but [Client.SyncMode.Manual] and
 * [Client.SyncMode.Polling], mirroring [Client]'s own condition), waits for
 * the server to register that stream via [awaitWatchConnected]. A peer
 * attaching after this returns arrives as Others.Watched instead of inside
 * [document]'s Initialization frame.
 */
suspend fun attachAndAwaitWatch(
    client: Client,
    document: Document,
    syncMode: Client.SyncMode,
    initialPresence: Map<String, String> = emptyMap(),
) {
    val attach = suspend {
        client.attachDocument(
            document,
            syncMode = syncMode,
            initialPresence = initialPresence,
        ).await()
    }
    if (syncMode != Client.SyncMode.Manual && syncMode != Client.SyncMode.Polling) {
        awaitWatchConnected(document) { attach() }
    } else {
        attach()
    }
}

/**
 * Runs [block] and waits until the server has registered [document]'s watch
 * stream, failing with a timeout after [timeoutMs].
 *
 * The signal is a [Document.Event.PresenceChanged.MyPresence.Initialized]
 * event observed while this client is in [document]'s online clients.
 * [Client] publishes Initialized for the stream's Initialization frame —
 * after setting the online clients to the server's subscriber list, which
 * includes this client — and ALSO when a stream fails or closes, after
 * clearing them. Neither
 * [Document.Event.StreamConnectionChanged.Connected] (published when the
 * request is SENT, before the server registers the watcher) nor
 * Initialized alone means registered.
 *
 * The collector subscribes before [block] runs, so the event is not missed.
 * The timeout surfaces from [kotlinx.coroutines.Deferred.await] in this
 * scope, so a missing stream fails the test instead of returning silently.
 */
suspend fun awaitWatchConnected(
    document: Document,
    timeoutMs: Long = GENERAL_TIMEOUT,
    block: suspend () -> Unit,
) = coroutineScope {
    val registered = async(start = CoroutineStart.UNDISPATCHED) {
        document.events
            .filterIsInstance<Document.Event.PresenceChanged.MyPresence.Initialized>()
            .first { document.changeID.actor in document.getOnlineClients() }
    }
    block()
    withTimeout(timeoutMs) { registered.await() }
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
