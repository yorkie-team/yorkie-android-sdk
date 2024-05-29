package dev.yorkie.core

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ServerOnlyStreamInterface
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.getOrElse
import com.connectrpc.getOrThrow
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchDocumentResponse
import dev.yorkie.api.v1.YorkieServiceClient
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.api.v1.activateClientRequest
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.pushPullChangesRequest
import dev.yorkie.api.v1.removeDocumentRequest
import dev.yorkie.api.v1.watchDocumentRequest
import dev.yorkie.core.Presences.Companion.UninitializedPresences
import dev.yorkie.core.Presences.Companion.asPresences
import dev.yorkie.document.Document
import dev.yorkie.document.Document.DocumentStatus
import dev.yorkie.document.Document.Event.PresenceChanged.MyPresence.Initialized
import dev.yorkie.document.Document.Event.PresenceChanged.Others
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.Logger.Companion.log
import dev.yorkie.util.OperationResult
import dev.yorkie.util.SUCCESS
import dev.yorkie.util.createSingleThreadDispatcher
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Client that can communicate with the server.
 * It has [Document]s and sends changes of the documents in local
 * to the server to synchronize with other replicas in remote.
 *
 * A single-threaded, [Closeable] [dispatcher] is used as default.
 * Therefore you need to [close] the client, when the client is no longer needed.
 * If you provide your own [dispatcher], it is up to you to decide [close] is needed or not.
 */
public class Client @VisibleForTesting internal constructor(
    private val service: YorkieServiceClientInterface,
    private val options: Options,
    private val dispatcher: CoroutineDispatcher,
    private val unaryClient: OkHttpClient,
    private val streamClient: OkHttpClient,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val activationJob = SupervisorJob()

    private val attachments = MutableStateFlow<Map<Document.Key, Attachment>>(emptyMap())

    private val _status = MutableStateFlow<Status>(Status.Deactivated)
    public val status = _status.asStateFlow()

    public val isActive: Boolean
        get() = status.value is Status.Activated

    private val projectBasedRequestHeader = mapOf("x-shard-key" to listOf(options.apiKey.orEmpty()))

    private val Document.Key.documentBasedRequestHeader
        get() = mapOf(
            "x-shard-key" to listOf("${options.apiKey.orEmpty()}/$value"),
        )

    public constructor(
        host: String,
        options: Options = Options(),
        unaryClient: OkHttpClient = OkHttpClient(),
        streamClient: OkHttpClient = unaryClient,
        dispatcher: CoroutineDispatcher = createSingleThreadDispatcher("Client(${options.key})"),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        service = YorkieServiceClient(
            ProtocolClient(
                ConnectOkHttpClient(unaryClient, streamClient),
                ProtocolClientConfig(
                    host = host,
                    serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                    networkProtocol = NetworkProtocol.CONNECT,
                    ioCoroutineContext = ioDispatcher,
                    interceptors = buildList {
                        add { UserAgentInterceptor }
                        options.authInterceptor()?.let { interceptor ->
                            add { interceptor }
                        }
                    },
                ),
            ),
        ),
        options = options,
        dispatcher = dispatcher,
        unaryClient = unaryClient,
        streamClient = streamClient,
    )

    /**
     * Activates this [Client]. That is, it registers itself to the server
     * and receives a unique ID from the server. The given ID is used to
     * distinguish different clients.
     */
    public fun activateAsync(): Deferred<OperationResult> {
        return scope.async {
            if (isActive) {
                return@async SUCCESS
            }
            val activateResponse = service.activateClient(
                activateClientRequest {
                    clientKey = options.key
                },
                projectBasedRequestHeader,
            ).getOrElse {
                ensureActive()
                return@async Result.failure(it)
            }
            _status.emit(Status.Activated(ActorID(activateResponse.clientId)))
            runSyncLoop()
            runWatchLoop()
            SUCCESS
        }
    }

    private fun runSyncLoop() {
        scope.launch(activationJob) {
            while (true) {
                filterRealTimeSyncNeeded().asSyncFlow().collect { (document, result) ->
                    document.publishEvent(
                        if (result.isSuccess) {
                            SyncStatusChanged.Synced
                        } else {
                            SyncStatusChanged.SyncFailed(result.exceptionOrNull())
                        },
                    )
                }
                delay(options.syncLoopDuration.inWholeMilliseconds)
            }
        }
    }

    private fun filterRealTimeSyncNeeded() = attachments.value.filterValues { attachment ->
        attachment.needRealTimeSync() &&
            (attachment.document.hasLocalChanges || attachment.remoteChangeEventReceived)
    }.map { (key, attachment) ->
        attachments.value += key to attachment.copy(remoteChangeEventReceived = false)
        attachment
    }

    /**
     * Pushes local changes of the attached documents to the server and
     * receives changes of the remote replica from the server then apply them to local documents.
     */
    public fun syncAsync(document: Document? = null): Deferred<OperationResult> {
        return scope.async {
            var failure: Throwable? = null
            val attachments = document?.let {
                listOf(
                    attachments.value[it.key]?.copy(syncMode = SyncMode.Realtime)
                        ?: throw IllegalArgumentException("document is not attached"),
                )
            } ?: attachments.value.values
            attachments.asSyncFlow().collect { (document, result) ->
                document.publishEvent(
                    if (result.isSuccess) {
                        SyncStatusChanged.Synced
                    } else {
                        val exception = result.exceptionOrNull()
                        if (failure == null && exception != null) {
                            failure = exception
                        }
                        SyncStatusChanged.SyncFailed(exception)
                    },
                )
            }
            failure?.let { Result.failure(it) } ?: SUCCESS
        }
    }

    private suspend fun Collection<Attachment>.asSyncFlow(): Flow<SyncResult> {
        return asFlow()
            .map { attachment ->
                val (document, documentID, syncMode) = attachment
                SyncResult(
                    document,
                    runCatching {
                        val request = pushPullChangesRequest {
                            clientId = requireClientId().value
                            changePack = document.createChangePack().toPBChangePack()
                            documentId = documentID
                            pushOnly = syncMode == SyncMode.RealtimePushOnly
                        }
                        val response = service.pushPullChanges(
                            request,
                            document.key.documentBasedRequestHeader,
                        ).getOrThrow()
                        val responsePack = response.changePack.toChangePack()
                        // NOTE(7hong13, chacha912, hackerwins): If syncLoop already executed with
                        // PushPull, ignore the response when the syncMode is PushOnly.
                        if (responsePack.hasChanges &&
                            attachments.value[document.key]?.syncMode == SyncMode.RealtimePushOnly
                        ) {
                            return@runCatching
                        }

                        document.applyChangePack(responsePack)
                        // NOTE(chacha912): If a document has been removed, watchStream should
                        // be disconnected to not receive an event for that document.
                        if (document.status == DocumentStatus.Removed) {
                            attachments.value -= document.key
                        }
                    }.onFailure {
                        coroutineContext.ensureActive()
                    },
                )
            }
    }

    private fun runWatchLoop() {
        scope.launch(activationJob) {
            attachments.map { attachment ->
                attachment.filterValues { it.syncMode != SyncMode.Manual }
            }.fold(emptyMap<Document.Key, WatchJobHolder>()) { accumulator, attachments ->
                (accumulator.keys - attachments.keys).forEach {
                    accumulator.getValue(it).job.cancel()
                }
                attachments.entries.associate { (key, attachment) ->
                    val previous = accumulator[key]
                    key to if (previous?.documentID == attachment.documentID &&
                        previous.job.isActive
                    ) {
                        previous
                    } else {
                        previous?.job?.cancel()
                        WatchJobHolder(attachment.documentID, createWatchJob(attachment))
                    }
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun createWatchJob(attachment: Attachment): Job {
        var latestStream: ServerOnlyStreamInterface<*, *>? = null
        return scope.launch(activationJob) {
            while (true) {
                ensureActive()
                latestStream.safeClose()
                val stream = service.watchDocument(
                    attachment.document.key.documentBasedRequestHeader,
                ).also {
                    latestStream = it
                }
                val streamJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    val channel = stream.responseChannel()
                    var retry = 0
                    while (!stream.isReceiveClosed() && !channel.isClosedForReceive) {
                        val receiveResult = channel.receiveCatching()
                        receiveResult.onSuccess {
                            attachment.document.publishEvent(StreamConnectionChanged.Connected)
                            handleWatchDocumentsResponse(attachment.document.key, it)
                            retry = 0
                        }.onFailure {
                            if (receiveResult.isClosed) {
                                return@onFailure
                            }
                            retry++
                            handleWatchStreamFailure(attachment.document, stream, it, retry > 3)
                        }.onClosed {
                            handleWatchStreamFailure(
                                attachment.document,
                                stream,
                                it ?: ClosedReceiveChannelException("Channel was closed"),
                                true,
                            )
                        }
                    }
                }
                stream.sendAndClose(
                    watchDocumentRequest {
                        clientId = requireClientId().value
                        documentId = attachment.documentID
                    },
                )
                streamJob.join()
            }
        }.also {
            it.invokeOnCompletion {
                scope.launch {
                    onWatchStreamCanceled(attachment.document)
                    latestStream.safeClose()
                }
            }
        }
    }

    private suspend fun handleWatchStreamFailure(
        document: Document,
        stream: ServerOnlyStreamInterface<*, *>,
        cause: Throwable?,
        closeStream: Boolean,
    ) {
        onWatchStreamCanceled(document)
        if (closeStream) {
            stream.safeClose()
        }
        cause?.let(::sendWatchStreamException)
        coroutineContext.ensureActive()
        delay(options.reconnectStreamDelay.inWholeMilliseconds)
    }

    private suspend fun onWatchStreamCanceled(document: Document) {
        if (document.status == DocumentStatus.Attached && status.value is Status.Activated) {
            document.publishEvent(
                Initialized((requireClientId() to document.myPresence).asPresences()),
            )
            document.setOnlineClients(emptySet())
            document.publishEvent(StreamConnectionChanged.Disconnected)
        }
    }

    private fun sendWatchStreamException(t: Throwable) {
        val tag = "Client.WatchStream"
        when (t) {
            is CancellationException -> {
                return
            }

            is ConnectException -> {
                log(
                    if (t.cause is InterruptedIOException) Log.DEBUG else Log.ERROR,
                    tag,
                    throwable = t,
                )
            }

            else -> {
                log(
                    if (t is ClosedReceiveChannelException) Log.DEBUG else Log.ERROR,
                    tag,
                    throwable = t,
                )
            }
        }
    }

    private suspend fun ServerOnlyStreamInterface<*, *>?.safeClose() {
        if (this == null || isReceiveClosed()) {
            return
        }
        withContext(NonCancellable) {
            receiveClose()
        }
    }

    private suspend fun handleWatchDocumentsResponse(
        documentKey: Document.Key,
        response: WatchDocumentResponse,
    ) {
        if (response.hasInitialization()) {
            val document = attachments.value[documentKey]?.document ?: return
            val clientIDs = response.initialization.clientIdsList.map { ActorID(it) }
            document.publishEvent(
                Initialized(
                    document.allPresences.value.filterKeys { it in clientIDs }.asPresences(),
                ),
            )
            document.setOnlineClients(clientIDs.toSet())
            return
        }

        val watchEvent = response.event
        val eventType = checkNotNull(watchEvent.type)
        // only single key will be received since 0.3.1 server.
        val attachment = attachments.value[documentKey] ?: return
        val document = attachment.document
        val publisher = ActorID(watchEvent.publisher)

        when (eventType) {
            DocEventType.DOC_EVENT_TYPE_DOCUMENT_WATCHED -> {
                // NOTE(chacha912): We added to onlineClients, but we won't trigger watched event
                // unless we also know their initial presence data at this point.
                val presence = document.allPresences.value[publisher]
                if (presence != null) {
                    document.publishEvent(Others.Watched(PresenceInfo(publisher, presence)))
                }
                document.addOnlineClient(publisher)
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_UNWATCHED -> {
                // NOTE(chacha912): There is no presence,
                // when PresenceChange(clear) is applied before unwatching. In that case,
                // the 'unwatched' event is triggered while handling the PresenceChange.
                val presence = document.presences.value[publisher] ?: return
                document.publishEvent(Others.Unwatched(PresenceInfo(publisher, presence)))
                document.removeOnlineClient(publisher)
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED -> {
                attachments.value += documentKey to attachment.copy(
                    remoteChangeEventReceived = true,
                )
            }

            DocEventType.UNRECOGNIZED, DocEventType.DOC_EVENT_TYPE_DOCUMENT_BROADCAST -> {
                // nothing to do
            }
        }
    }

    /**
     * Attaches the given [Document] to this [Client].
     * It tells the server that this [Client] will synchronize the given [document].
     */
    public fun attachAsync(
        document: Document,
        initialPresence: P = emptyMap(),
        syncMode: SyncMode = SyncMode.Realtime,
    ): Deferred<OperationResult> {
        return scope.async {
            check(isActive) {
                "client is not active"
            }
            require(document.status == DocumentStatus.Detached) {
                "document is not detached"
            }
            document.setActor(requireClientId())
            document.updateAsync { _, presence ->
                presence.put(initialPresence)
            }.await()

            val request = attachDocumentRequest {
                clientId = requireClientId().value
                changePack = document.createChangePack().toPBChangePack()
            }
            val response = service.attachDocument(
                request,
                document.key.documentBasedRequestHeader,
            ).getOrElse {
                ensureActive()
                return@async Result.failure(it)
            }
            val pack = response.changePack.toChangePack()
            document.applyChangePack(pack)

            if (document.status == DocumentStatus.Removed) {
                return@async SUCCESS
            }

            document.status = DocumentStatus.Attached
            attachments.value += document.key to Attachment(
                document,
                response.documentId,
                syncMode,
            )
            waitForInitialization(document.key)
            SUCCESS
        }
    }

    /**
     * Detaches the given [document] from this [Client]. It tells the
     * server that this client will no longer synchronize the given [Document].
     *
     * To collect garbage things like CRDT tombstones left on the [Document], all
     * the changes should be applied to other replicas before GC time. For this,
     * if the [document] is no longer used by this [Client], it should be detached.
     */
    public fun detachAsync(document: Document): Deferred<OperationResult> {
        return scope.async {
            check(isActive) {
                "client is not active"
            }
            val attachment = attachments.value[document.key]
                ?: throw IllegalArgumentException("document is not attached")

            document.updateAsync { _, presence ->
                presence.clear()
            }.await()

            val request = detachDocumentRequest {
                clientId = requireClientId().value
                changePack = document.createChangePack().toPBChangePack()
                documentId = attachment.documentID
            }
            val response = service.detachDocument(
                request,
                document.key.documentBasedRequestHeader,
            ).getOrElse {
                ensureActive()
                return@async Result.failure(it)
            }
            val pack = response.changePack.toChangePack()
            document.applyChangePack(pack)
            if (document.status != DocumentStatus.Removed) {
                document.status = DocumentStatus.Detached
                attachments.value -= document.key
            }
            SUCCESS
        }
    }

    /**
     * Deactivates this [Client].
     */
    public fun deactivateAsync(): Deferred<OperationResult> {
        return scope.async {
            if (!isActive) {
                return@async SUCCESS
            }
            activationJob.cancelChildren()

            service.deactivateClient(
                deactivateClientRequest {
                    clientId = requireClientId().value
                },
                projectBasedRequestHeader,
            ).getOrElse {
                ensureActive()
                return@async Result.failure(it)
            }
            _status.emit(Status.Deactivated)
            SUCCESS
        }
    }

    /**
     * Removes the given [document].
     */
    public fun removeAsync(document: Document): Deferred<OperationResult> {
        return scope.async {
            check(isActive) {
                "client is not active"
            }
            val attachment = attachments.value[document.key]
                ?: throw IllegalArgumentException("document is not attached")

            val request = removeDocumentRequest {
                clientId = requireClientId().value
                changePack = document.createChangePack(forceRemove = true).toPBChangePack()
                documentId = attachment.documentID
            }
            val response = service.removeDocument(
                request,
                document.key.documentBasedRequestHeader,
            ).getOrElse {
                ensureActive()
                return@async Result.failure(it)
            }
            val pack = response.changePack.toChangePack()
            document.applyChangePack(pack)
            attachments.value -= document.key
            SUCCESS
        }
    }

    private suspend fun waitForInitialization(documentKey: Document.Key) {
        val attachment = attachments.first { documentKey in it.keys }[documentKey] ?: return
        attachment.document.presences.first { it != UninitializedPresences }
        if (attachment.syncMode != SyncMode.Manual) {
            attachment.document.events.first { it is Initialized }
        }
    }

    public fun requireClientId() = (status.value as Status.Activated).clientId

    /**
     * Changes the sync mode of the [document].
     */
    public fun changeSyncMode(document: Document, syncMode: SyncMode) {
        check(isActive) {
            "client is not active"
        }
        val attachment = attachments.value[document.key]
            ?: throw IllegalArgumentException("document is not attached")
        attachments.value += document.key to if (syncMode == SyncMode.Realtime) {
            attachment.copy(syncMode = syncMode, remoteChangeEventReceived = true)
        } else {
            attachment.copy(syncMode = syncMode)
        }
    }

    override fun close() {
        scope.cancel()
        (dispatcher as? Closeable)?.close()
        unaryClient.dispatcher.executorService.shutdown()
        streamClient.dispatcher.executorService.shutdown()
    }

    private data class SyncResult(val document: Document, val result: OperationResult)

    private class WatchJobHolder(val documentID: String, val job: Job)

    /**
     * Represents the status of the client.
     */
    public sealed interface Status {
        /**
         * Means that the client is activated. If the client is activated,
         * all [Document]s of the client are ready to be used.
         */
        public class Activated internal constructor(public val clientId: ActorID) : Status

        /**
         * Means that the client is not activated. It is the initial stastus of the client.
         * If the client is deactivated, all [Document]s of the client are also not used.
         */
        public data object Deactivated : Status
    }

    /**
     * [SyncMode] defines synchronization modes for the PushPullChanges API.
     */
    public enum class SyncMode(val needRealTimeSync: Boolean) {
        Realtime(true),
        RealtimePushOnly(true),
        RealtimeSyncOff(false),
        Manual(false),
    }

    /**
     * User-settable options used when defining [Client].
     */
    public data class Options(
        /**
         * Client key used to identify the client.
         * If not set, a random key is generated.
         */
        public val key: String = UUID.randomUUID().toString(),
        /**
         * API key of the project used to identify the project.
         */
        public val apiKey: String? = null,
        /**
         * Authentication token of this [Client] used to identify the user of the client.
         */
        public val token: String? = null,
        /**
         * Duration of the sync loop.
         * After each sync loop, the client waits for the duration to next sync.
         * The default value is `50`(ms).
         */
        public val syncLoopDuration: Duration = 50.milliseconds,
        /**
         * Delay of the reconnect stream.
         * If the stream is disconnected, the client waits for the delay to reconnect the stream.
         * The default value is `1000`(ms).
         */
        public val reconnectStreamDelay: Duration = 1_000.milliseconds,
    )
}
