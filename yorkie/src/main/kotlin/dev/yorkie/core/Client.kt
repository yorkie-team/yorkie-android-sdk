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
import com.google.protobuf.ByteString
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchDocumentResponse
import dev.yorkie.api.v1.YorkieServiceClient
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.api.v1.activateClientRequest
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.broadcastRequest
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.pushPullChangesRequest
import dev.yorkie.api.v1.removeDocumentRequest
import dev.yorkie.api.v1.watchDocumentRequest
import dev.yorkie.document.Document
import dev.yorkie.document.Document.DocStatus
import dev.yorkie.document.Document.Event.AuthError
import dev.yorkie.document.Document.Event.AuthError.AuthErrorMethod.PushPull
import dev.yorkie.document.Document.Event.AuthError.AuthErrorMethod.WatchDocuments
import dev.yorkie.document.Document.Event.PresenceChanged.MyPresence.Initialized
import dev.yorkie.document.Document.Event.PresenceChanged.Others
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.presence.Presences.Companion.UninitializedPresences
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.Logger.Companion.log
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.OperationResult
import dev.yorkie.util.SUCCESS
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrClientNotActivated
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotAttached
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotDetached
import dev.yorkie.util.YorkieException.Code.ErrUnauthenticated
import dev.yorkie.util.checkYorkieError
import dev.yorkie.util.errorCodeOf
import dev.yorkie.util.errorMetadataOf
import dev.yorkie.util.handleConnectException
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.collections.Map.Entry
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
public class Client @VisibleForTesting constructor(
    private val options: Options,
    private val unaryClient: OkHttpClient,
    private val streamClient: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
    host: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    private val mutexForDocuments = mutableMapOf<Document.Key, Mutex>()

    private val Document.mutex
        get() = mutexForDocuments.getOrPut(key) { Mutex() }

    private val streamTimeout = with(streamClient) {
        callTimeoutMillis.takeIf { it > 0 } ?: (connectTimeoutMillis + readTimeoutMillis)
    }.takeIf { it > 0 }?.milliseconds ?: 5.minutes

    @VisibleForTesting
    internal val conditions: MutableMap<ClientCondition, Boolean> = mutableMapOf(
        ClientCondition.SYNC_LOOP to false,
        ClientCondition.WATCH_LOOP to false,
    )

    var shouldRefreshToken: Boolean = false

    @VisibleForTesting
    suspend fun authToken(shouldRefresh: Boolean): String? {
        return options.fetchAuthToken?.invoke(shouldRefresh)
    }

    @VisibleForTesting
    var service: YorkieServiceClientInterface = YorkieServiceClient(
        ProtocolClient(
            ConnectOkHttpClient(unaryClient, streamClient),
            ProtocolClientConfig(
                host = host,
                serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                networkProtocol = NetworkProtocol.CONNECT,
                ioCoroutineContext = ioDispatcher,
                interceptors = buildList {
                    add { UserAgentInterceptor }
                    options.authInterceptor(
                        { shouldRefreshToken },
                        { shouldRefreshToken = false },
                    )?.let { interceptor ->
                        add { interceptor }
                    }
                },
            ),
        ),
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
                handleConnectException(it) { exception ->
                    if (errorCodeOf(exception) == ErrUnauthenticated.codeString) {
                        shouldRefreshToken = true
                    }
                    deactivateInternal()
                }
                return@async Result.failure(it)
            }
            _status.emit(Status.Activated(ActorID(activateResponse.clientId)))
            runSyncLoop()
            runWatchLoop()
            SUCCESS
        }
    }

    /**
     * runSyncLoop() runs the sync loop. The sync loop pushes local changes to
     * the server and pulls remote changes from the server.
     */
    private fun runSyncLoop() {
        scope.launch(activationJob) {
            while (true) {
                if (!isActive) {
                    conditions[ClientCondition.SYNC_LOOP] = false
                    return@launch
                }
                attachments.value.entries.asSyncFlow(true).collect { (document, result) ->
                    document.publishEvent(
                        if (result.isSuccess) {
                            conditions[ClientCondition.SYNC_LOOP] = true
                            SyncStatusChanged.Synced
                        } else if (handleConnectException(result.exceptionOrNull() as? ConnectException) { connectException ->
                                if (errorCodeOf(connectException) == ErrUnauthenticated.codeString) {
                                    shouldRefreshToken = true
                                    document.publishEvent(
                                        AuthError(
                                            errorMetadataOf(
                                                connectException,
                                            )["reason"] ?: "AuthError",
                                            PushPull,
                                        ),
                                    )
                                }
                            }) { // check if the exception is retryable
                            conditions[ClientCondition.SYNC_LOOP] = true
                            SyncStatusChanged.SyncFailed(
                                result.exceptionOrNull(),
                            )
                        } else {
                            conditions[ClientCondition.SYNC_LOOP] = false
                            SyncStatusChanged.SyncFailed(
                                result.exceptionOrNull(),
                            )
                            return@collect
                        },
                    )
                }
                delay(options.syncLoopDuration.inWholeMilliseconds)
            }
        }
    }

    /**
     * Pushes local changes of the attached documents to the server and
     * receives changes of the remote replica from the server then apply them to local documents.
     */
    public fun syncAsync(document: Document? = null): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            shouldRefreshToken = options.fetchAuthToken != null
            println("dlt, syncAsync shouldRefreshToken : $shouldRefreshToken")

            var failure: Throwable? = null
            val attachments = document?.let {
                val attachment = attachments.value[it.key]?.copy(syncMode = SyncMode.Realtime)
                    ?: throw YorkieException(
                        ErrDocumentNotAttached,
                        "document(${document.key}) is not attached",
                    )

                listOf(AttachmentEntry(it.key, attachment))
            } ?: attachments.value.entries
            attachments.asSyncFlow(false).collect { (document, result) ->
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

    private suspend fun Collection<Entry<Document.Key, Attachment>>.asSyncFlow(
        realTimeOnly: Boolean,
    ): Flow<SyncResult> {
        return asFlow()
            .mapNotNull { (key, attachment) ->
                val (document, documentID, syncMode) = if (realTimeOnly) {
                    if (!attachment.needRealTimeSync()) {
                        return@mapNotNull null
                    }
                    if (attachment.remoteChangeEventReceived) {
                        attachment.copy(remoteChangeEventReceived = false).also {
                            attachments.value += key to it
                        }
                    } else {
                        attachment
                    }
                } else {
                    attachment
                }

                SyncResult(
                    document,
                    runCatching {
                        document.mutex.withLock {
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
                            val currentSyncMode = attachments.value[document.key]?.syncMode
                            if (responsePack.hasChanges &&
                                currentSyncMode == SyncMode.RealtimePushOnly ||
                                currentSyncMode == SyncMode.RealtimeSyncOff
                            ) {
                                return@runCatching
                            }

                            document.applyChangePack(responsePack)
                            // NOTE(chacha912): If a document has been removed, watchStream should
                            // be disconnected to not receive an event for that document.
                            if (document.status == DocStatus.Removed) {
                                attachments.value -= document.key
                                mutexForDocuments.remove(document.key)
                            }
                        }
                    }.onFailure {
                        coroutineContext.ensureActive()
                        (it as? ConnectException)?.let { exception ->
                            handleConnectException(exception) { it ->
                                if (errorCodeOf(it) == ErrUnauthenticated.codeString) {
                                    shouldRefreshToken = true
                                }
                                deactivateInternal()
                            }
                        }
                    },
                )
            }
    }

    /**
     * runWatchLoop() runs the watch loop for the given document. The watch loop
     * listens to the events of the given document from the server.
     */
    private fun runWatchLoop() {
        scope.launch(activationJob) {
            conditions[ClientCondition.WATCH_LOOP] = true

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
            var shouldContinue = true
            while (shouldContinue) {
                ensureActive()
                latestStream.safeClose()

                val stream = withTimeoutOrNull(streamTimeout) {
                    service.watchDocument(
                        attachment.document.key.documentBasedRequestHeader,
                    ).also {
                        latestStream = it
                    }
                } ?: continue
                val streamJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    val channel = stream.responseChannel()
                    while (!stream.isReceiveClosed() && !channel.isClosedForReceive && shouldContinue) {
                        withTimeoutOrNull(streamTimeout) {
                            val receiveResult = channel.receiveCatching()
                            receiveResult.onSuccess {
                                attachment.document.publishEvent(StreamConnectionChanged.Connected)
                                handleWatchDocumentsResponse(attachment.document.key, it)
                                shouldContinue = true
                            }.onFailure {
                                if (receiveResult.isClosed) {
                                    stream.safeClose()
                                    return@onFailure
                                }
                                shouldContinue =
                                    handleWatchStreamFailure(attachment.document, stream, it)
                            }.onClosed {
                                handleWatchStreamFailure(
                                    attachment.document,
                                    stream,
                                    it ?: ClosedReceiveChannelException("Channel was closed"),
                                )
                            }
                        } ?: run {
                            handleWatchStreamFailure(
                                attachment.document,
                                stream,
                                TimeoutException("channel timed out"),
                            )
                            shouldContinue = true
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

    /**
     * handleWatchStreamFailure() handles the failure of the watch stream.
     * return true if the stream should be reconnected, false otherwise.
     */
    private suspend fun handleWatchStreamFailure(
        document: Document,
        stream: ServerOnlyStreamInterface<*, *>,
        cause: Throwable?,
    ): Boolean {
        onWatchStreamCanceled(document)
        stream.safeClose()

        cause?.let(::sendWatchStreamException)

        if (handleConnectException(cause as? ConnectException) { connectException ->
                if (errorCodeOf(connectException) == ErrUnauthenticated.codeString) {
                    shouldRefreshToken = true
                    document.publishEvent(
                        AuthError(
                            errorMetadataOf(
                                connectException,
                            )["reason"] ?: "AuthError",
                            WatchDocuments,
                        ),
                    )
                }
            }) {
            coroutineContext.ensureActive()
            delay(options.reconnectStreamDelay.inWholeMilliseconds)
            return true
        } else {
            conditions[ClientCondition.WATCH_LOOP] = false
            return false
        }
    }

    private suspend fun onWatchStreamCanceled(document: Document) {
        if (document.status == DocStatus.Attached && status.value is Status.Activated) {
            document.publishEvent(
                Initialized(
                    (requireClientId() to document.myPresence).asPresences(),
                ),
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
        if (this == null) {
            return
        }
        withContext(NonCancellable) {
            runCatching {
                responseChannel().cancel()
                receiveClose()
            }
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
                if (document.getOnlineClients()
                        .contains(publisher) && document.presences.value.contains(publisher)
                ) {
                    return
                }
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

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_BROADCAST -> {
                val topic = response.event.body.topic
                val payload = response.event.body.payload.toStringUtf8()
                document.publishEvent(
                    Document.Event.Broadcast(
                        actorID = publisher,
                        topic = topic,
                        payload = payload,
                    ),
                )
            }

            DocEventType.UNRECOGNIZED -> {
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
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            checkYorkieError(
                document.status == DocStatus.Detached,
                YorkieException(ErrDocumentNotDetached, "document(${document.key} is not detached"),
            )

            document.mutex.withLock {
                val clientID = requireClientId()
                document.setActor(clientID)
                document.updateAsync { _, presence ->
                    presence.put(initialPresence)
                }.await()

                val request = attachDocumentRequest {
                    clientId = clientID.value
                    changePack = document.createChangePack().toPBChangePack()
                }
                val response = service.attachDocument(
                    request,
                    document.key.documentBasedRequestHeader,
                ).getOrElse {
                    ensureActive()
                    handleConnectException(it) { exception ->
                        if (errorCodeOf(exception) == ErrUnauthenticated.codeString) {
                            shouldRefreshToken = true
                        }
                        deactivateInternal()
                    }
                    return@async Result.failure(it)
                }
                val pack = response.changePack.toChangePack()
                document.applyChangePack(pack)

                if (document.status == DocStatus.Removed) {
                    return@async SUCCESS
                }

                document.applyDocumentStatus(DocStatus.Attached)
                attachments.value += document.key to Attachment(
                    document,
                    response.documentId,
                    syncMode,
                )
                waitForInitialization(document.key)
            }
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
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            document.mutex.withLock {
                val attachment = attachments.value[document.key]
                    ?: throw YorkieException(
                        ErrDocumentNotAttached,
                        "document(${document.key}) is not attached",
                    )

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
                    handleConnectException(it) { exception ->
                        if (errorCodeOf(exception) == ErrUnauthenticated.codeString) {
                            shouldRefreshToken = true
                        }
                        deactivateInternal()
                    }
                    return@async Result.failure(it)
                }
                val pack = response.changePack.toChangePack()
                document.applyChangePack(pack)
                if (document.status != DocStatus.Removed) {
                    document.applyDocumentStatus(DocStatus.Detached)
                    detachInternal(document)
                }
            }
            SUCCESS
        }
    }

    private fun detachInternal(document: Document) {
        attachments.value[document.key] ?: return
        attachments.value -= document.key
        mutexForDocuments.remove(document.key)
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
                handleConnectException(it) { exception ->
                    if (errorCodeOf(exception) == ErrUnauthenticated.codeString) {
                        shouldRefreshToken = true
                    }
                    deactivateInternal()
                }
                return@async Result.failure(it)
            }

            deactivateInternal()
            SUCCESS
        }
    }

    private suspend fun deactivateInternal() {
        attachments.value.values.forEach {
            detachInternal(it.document)
            it.document.applyDocumentStatus(DocStatus.Detached)
        }

        _status.emit(Status.Deactivated)
    }

    /**
     * Removes the given [document].
     */
    public fun removeAsync(document: Document): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            document.mutex.withLock {
                val attachment = attachments.value[document.key]
                    ?: throw YorkieException(
                        ErrDocumentNotAttached,
                        "document(${document.key}) is not attached",
                    )

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
                mutexForDocuments.remove(document.key)
            }
            SUCCESS
        }
    }

    public fun broadcast(
        document: Document,
        topic: String,
        payload: String,
        options: Document.BroadcastOptions = Document.BroadcastOptions(),
    ): Deferred<OperationResult> {
        val maxRetries = options.maxRetries
        val maxBackoff = BROADCAST_MAX_BACK_OFF
        var retryCount = 0

        fun exponentialBackoff(retryCount: Int): Long {
            return minOf(
                BROADCAST_INITIAL_RETRY_INTERVAL * (2.0.pow(retryCount.toDouble())).toLong(),
                maxBackoff,
            )
        }

        suspend fun doLoop(): OperationResult {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            val attachment = attachments.value[document.key] ?: throw YorkieException(
                ErrDocumentNotAttached,
                "document(${document.key}) is not attached",
            )
            val clientID = requireClientId()

            val request = broadcastRequest {
                clientId = clientID.value
                documentId = attachment.documentID
                this.topic = topic
                this.payload = ByteString.copyFromUtf8(payload)
            }

            while (retryCount <= maxRetries) {
                try {
                    service.broadcast(
                        request,
                        document.key.documentBasedRequestHeader,
                    ).getOrElse {
                        throw it
                    }
                    return SUCCESS
                } catch (err: Exception) {
                    retryCount++
                    if (retryCount > maxRetries) {
                        logError("BROADCAST", "Exceeded maximum retry attempts for topic $topic")
                        throw err
                    }

                    val retryInterval = exponentialBackoff(retryCount - 1)
                    logError(
                        "BROADCAST",
                        "Retry attempt $retryCount/$maxRetries for topic $topic after $retryInterval ms",
                    )
                    delay(retryInterval)
                }
            }
            throw Exception("Unexpected error during broadcast")
        }

        return scope.async {
            doLoop()
        }
    }

    private suspend fun waitForInitialization(documentKey: Document.Key) {
        val attachment = attachments.first { documentKey in it.keys }[documentKey] ?: return
        attachment.document.presences.first { it != UninitializedPresences }
        if (attachment.syncMode != SyncMode.Manual) {
            attachment.document.events.first { it is Initialized }
        }
    }

    public fun requireClientId(): ActorID {
        if (status.value is Status.Deactivated) {
            throw YorkieException(ErrClientNotActivated, "client is not active")
        }
        return (status.value as Status.Activated).clientId
    }

    /**
     * Changes the sync mode of the [document].
     */
    public fun changeSyncMode(document: Document, syncMode: SyncMode) {
        checkYorkieError(isActive, YorkieException(ErrClientNotActivated, "client is not active"))

        val attachment = attachments.value[document.key]
            ?: throw YorkieException(
                ErrDocumentNotAttached,
                "document(${document.key}) is not attached",
            )
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

    private class AttachmentEntry(
        override val key: Document.Key,
        override val value: Attachment,
    ) : Entry<Document.Key, Attachment>

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
         * Means that the client is not activated. It is the initial status of the client.
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
         * `fetchAuthToken` provides a token for the auth webhook.
         * When the webhook response status code is 401, this function is called to refresh the token.
         * The `reason` parameter is the reason from the webhook response.
         */
        public val fetchAuthToken: (suspend (shouldRefresh: Boolean) -> String)? = null,
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

    /**
     * [ClientCondition] represents the condition of the client.
     */
    public enum class ClientCondition {
        /**
         * Key of the sync loop condition.
         */
        SYNC_LOOP,

        /**
         * Key of the watch loop condition.
         */
        WATCH_LOOP,
    }

    companion object {
        private const val BROADCAST_MAX_BACK_OFF = 20_000L
        private const val BROADCAST_INITIAL_RETRY_INTERVAL = 1_000
    }
}
