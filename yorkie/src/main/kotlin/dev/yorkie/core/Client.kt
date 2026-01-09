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
import dev.yorkie.api.fromSchemaRules
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchDocumentResponse
import dev.yorkie.api.v1.WatchPresenceResponse
import dev.yorkie.api.v1.YorkieServiceClient
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.attachPresenceRequest
import dev.yorkie.api.v1.broadcastRequest
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.detachPresenceRequest
import dev.yorkie.api.v1.pushPullChangesRequest
import dev.yorkie.api.v1.refreshPresenceRequest
import dev.yorkie.api.v1.removeDocumentRequest
import dev.yorkie.api.v1.watchDocumentRequest
import dev.yorkie.api.v1.watchPresenceRequest
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.AuthError
import dev.yorkie.document.Document.Event.AuthError.AuthErrorMethod.PushPull
import dev.yorkie.document.Document.Event.PresenceChanged.MyPresence.Initialized
import dev.yorkie.document.Document.Event.PresenceChanged.Others
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.document.time.ActorID
import dev.yorkie.presence.Presence
import dev.yorkie.presence.PresenceEvent
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
import dev.yorkie.util.createSingleThreadDispatcher
import dev.yorkie.util.errorCodeOf
import dev.yorkie.util.errorMetadataOf
import dev.yorkie.util.handleConnectException
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
public class Client(
    host: String,
    private val options: Options,
    private val unaryClient: OkHttpClient = OkHttpClient.Builder()
        .build(),
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : Closeable {
    private val dispatcher = createSingleThreadDispatcher("YorkieClient")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val activationJob = SupervisorJob()

    private val attachments = ConcurrentHashMap<String, Attachment<out Attachable>>()

    private val _status = MutableStateFlow<Status>(Status.Deactivated)
    public val status = _status.asStateFlow()

    public val isActive: Boolean
        get() = status.value is Status.Activated

    private val projectBasedRequestHeader = mapOf(
        "x-shard-key" to listOf("${options.apiKey.orEmpty()}/${options.key}"),
    )

    private val String.attachmentBasedRequestHeader
        get() = mapOf(
            "x-shard-key" to listOf("${options.apiKey.orEmpty()}/$this"),
        )

    private val mutexForAttachments = mutableMapOf<String, Mutex>()
    private val Attachable.mutex
        get() = mutexForAttachments.getOrPut(getKey()) { Mutex() }

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
                ioCoroutineContext = Dispatchers.IO,
                interceptors = buildList {
                    add { UserAgentInterceptor }
                    options.authInterceptor(
                        { shouldRefreshToken },
                        { shouldRefreshToken = false },
                    )?.let { interceptor ->
                        add { interceptor }
                    }
                },
                timeoutOracle = {
                    5.toDuration(DurationUnit.MINUTES)
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
                request = ActivateClientRequest.newBuilder()
                    .setClientKey(options.key)
                    .putAllMetadata(options.metadata)
                    .build(),
                headers = projectBasedRequestHeader,
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
            SUCCESS
        }
    }

    /**
     * runSyncLoop() runs the sync loop. The sync loop pushes local changes to
     * the server and pulls remote changes from the server.
     */
    private fun runSyncLoop() {
        conditions[ClientCondition.SYNC_LOOP] = true
        scope.launch(activationJob) {
            while (true) {
                if (!isActive) {
                    conditions[ClientCondition.SYNC_LOOP] = false
                    return@launch
                }
                for ((_, attachment) in attachments) {
                    val heartbeatInterval = options.presenceHeartbeatInterval.inWholeMilliseconds
                    if (!attachment.needSync(heartbeatInterval)) {
                        continue
                    }

                    // Reset changeEventReceived for Document resources
                    if (attachment.changeEventReceived != null) {
                        attachment.changeEventReceived = false
                    }

                    val (attachment, result) = syncInternal(attachment, attachment.syncMode)
                    val isRetryAble = handleConnectException(
                        result.exceptionOrNull() as? ConnectException,
                    ) { connectException ->
                        if (attachment.resource is Document) {
                            handleDocumentAuthenticationError(
                                exception = connectException,
                                document = attachment.resource,
                                method = PushPull,
                            )
                        }
                    }
                    if (result.isFailure && !isRetryAble) {
                        conditions[ClientCondition.SYNC_LOOP] = false
                    }
                }
                delay(options.syncLoopDuration.inWholeMilliseconds)
            }
        }
    }

    /**
     * Pushes local changes of the attached documents to the server and
     * receives changes of the remote replica from the server then apply them to local documents.
     */
    public fun syncAsync(resource: Attachable? = null): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            shouldRefreshToken = options.fetchAuthToken != null

            var failure: Throwable? = null

            if (resource != null) {
                val key = resource.getKey()
                val attachment = attachments[key] ?: throw YorkieException(
                    ErrDocumentNotAttached,
                    "$key is not attached",
                )
                val syncResult = syncInternal(attachment, SyncMode.Realtime)
                if (syncResult.result.isFailure) {
                    val exception = syncResult.result.exceptionOrNull()
                    if (exception != null) {
                        failure = exception
                    }
                }
            } else {
                attachments.forEach { (_, attachment) ->
                    if (attachment.syncMode != null && attachment.resource is Document) {
                        val syncResult = syncInternal(attachment, attachment.syncMode)
                        if (syncResult.result.isFailure) {
                            val exception = syncResult.result.exceptionOrNull()
                            if (exception != null) {
                                failure = exception
                            }
                        }
                    }
                }
            }
            failure?.let { Result.failure(it) } ?: SUCCESS
        }
    }

    private suspend fun syncInternal(
        attachment: Attachment<out Attachable>,
        syncMode: SyncMode?,
    ): SyncResult {
        val resource = attachment.resource
        return SyncResult(
            attachment,
            runCatching {
                if (resource is Document) {
                    resource.mutex.withLock {
                        val documentKey = resource.getKey()
                        val request = pushPullChangesRequest {
                            clientId = requireClientId().value
                            changePack = resource.createChangePack().toPBChangePack()
                            documentId = attachment.resourceId
                            pushOnly = syncMode == SyncMode.RealtimePushOnly
                        }
                        val response = service.pushPullChanges(
                            request,
                            documentKey.attachmentBasedRequestHeader,
                        ).getOrThrow()
                        val responsePack = response.changePack.toChangePack()
                        // NOTE(7hong13, chacha912, hackerwins): If syncLoop already executed with
                        // PushPull, ignore the response when the syncMode is PushOnly.
                        val currentSyncMode = attachments[documentKey]?.syncMode
                        if (responsePack.hasChanges &&
                            currentSyncMode == SyncMode.RealtimePushOnly ||
                            currentSyncMode == SyncMode.RealtimeSyncOff
                        ) {
                            return@runCatching
                        }
                        resource.applyChangePack(responsePack)
                        attachment.resource.publish(
                            event = SyncStatusChanged.Synced,
                        )

                        // NOTE(chacha912): If a document has been removed, watchStream should
                        // be disconnected to not receive an event for that document.
                        if (resource.getStatus() == ResourceStatus.Removed) {
                            detachInternal(documentKey)
                        }
                    }
                } else if (resource is Presence) {
                    resource.mutex.withLock {
                        val request = refreshPresenceRequest {
                            clientId = requireClientId().value
                            resource.getPresenceId()?.let {
                                presenceId = it
                            }
                            presenceKey = resource.getKey()
                        }
                        val response = service.refreshPresence(
                            request,
                            resource.getKey().attachmentBasedRequestHeader,
                        ).getOrThrow()
                        resource.updateCount(response.count, 0L)
                        attachment.updateHeartbeatTime()
                    }
                }
            }.onFailure {
                coroutineContext.ensureActive()

                if (resource is Document) {
                    resource.publish(
                        event = SyncStatusChanged.SyncFailed(
                            cause = it,
                        ),
                    )
                }

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

    /**
     * runWatchLoop() runs the watch loop for the given document. The watch loop
     * listens to the events of the given document from the server.
     */
    private fun runWatchLoop(key: String) {
        scope.launch(activationJob) {
            val attachment = attachments[key]
                ?: throw YorkieException(
                    ErrDocumentNotAttached,
                    "$key is not attached",
                )

            conditions[ClientCondition.WATCH_LOOP] = true

            attachment.watchJobHolder = WatchJobHolder(
                attachment.resource.getKey(),
                when (attachment.resource) {
                    is Document -> {
                        createDocumentWatchJob(attachment)
                    }

                    is Presence -> {
                        createPresenceWatchJob(attachment)
                    }

                    else -> {
                        throw IllegalArgumentException("attachment resource type is not correct")
                    }
                },
            )
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun createDocumentWatchJob(attachment: Attachment<out Attachable>): Job {
        val document = attachment.resource as Document
        val documentKey = document.getKey()
        var latestStream: ServerOnlyStreamInterface<*, *>? = null
        return scope.launch(activationJob) {
            var shouldContinue = true
            while (shouldContinue) {
                ensureActive()
                latestStream.safeClose()

                val stream = withTimeoutOrNull(streamTimeout) {
                    service.watchDocument(
                        documentKey.attachmentBasedRequestHeader,
                    ).also {
                        latestStream = it
                    }
                } ?: continue

                val streamJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    val channel = stream.responseChannel()
                    while (!stream.isReceiveClosed() &&
                        !channel.isClosedForReceive && shouldContinue
                    ) {
                        withTimeoutOrNull(streamTimeout) {
                            val receiveResult = channel.receiveCatching()
                            receiveResult.onSuccess {
                                handleWatchDocumentResponse(
                                    documentKey = documentKey,
                                    response = it,
                                )
                                shouldContinue = true
                            }.onFailure {
                                if (receiveResult.isClosed) {
                                    stream.safeClose()
                                    return@onFailure
                                }
                                shouldContinue =
                                    handleWatchDocumentStreamFailure(
                                        document = document,
                                        stream = stream,
                                        cause = it,
                                        cancelled = attachment.cancelled,
                                    )
                            }.onClosed {
                                handleWatchDocumentStreamFailure(
                                    document = document,
                                    stream = stream,
                                    cause = it
                                        ?: ClosedReceiveChannelException("Channel was closed"),
                                    cancelled = attachment.cancelled,
                                )
                            }
                        } ?: run {
                            handleWatchDocumentStreamFailure(
                                document = document,
                                stream = stream,
                                cause = TimeoutException("channel timed out"),
                                cancelled = attachment.cancelled,
                            )
                            shouldContinue = true
                        }
                    }
                }
                stream.sendAndClose(
                    watchDocumentRequest {
                        clientId = requireClientId().value
                        documentId = attachment.resourceId
                    },
                )

                document.publish(
                    StreamConnectionChanged.Connected,
                )

                if (attachment.changeEventReceived != null) {
                    attachment.changeEventReceived = true
                }

                streamJob.join()
            }
        }.also {
            it.invokeOnCompletion {
                scope.launch {
                    onWatchDocumentStreamCanceled(document)
                    latestStream.safeClose()
                }
            }
        }
    }

    private suspend fun handleWatchDocumentResponse(
        documentKey: String,
        response: WatchDocumentResponse,
    ) {
        if (response.hasInitialization()) {
            val document = attachments[documentKey]?.resource as? Document ?: return
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
        val attachment = attachments[documentKey] ?: return
        val document = attachment.resource as? Document ?: return
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
                if (attachment.changeEventReceived != null) {
                    attachment.changeEventReceived = true
                }
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
     * handleWatchStreamFailure() handles the failure of the watch stream.
     * return true if the stream should be reconnected, false otherwise.
     */
    private suspend fun handleWatchDocumentStreamFailure(
        document: Document,
        stream: ServerOnlyStreamInterface<*, *>,
        cause: Throwable?,
        cancelled: Boolean,
    ): Boolean {
        onWatchDocumentStreamCanceled(document)
        stream.safeClose()

        cause?.let {
            sendWatchAttachmentResourceStreamException(
                tag = "Client.WatchDocument",
                t = it,
            )
        }

        if (handleDocumentAuthenticationError(
                exception = cause,
                document = document,
                method = AuthError.AuthErrorMethod.WatchDocument,
            ) && !cancelled
        ) {
            coroutineContext.ensureActive()
            delay(options.reconnectStreamDelay.inWholeMilliseconds)
            return true
        } else {
            conditions[ClientCondition.WATCH_LOOP] = false
            return false
        }
    }

    private suspend fun onWatchDocumentStreamCanceled(document: Document) {
        if (document.getStatus() == ResourceStatus.Attached && status.value is Status.Activated) {
            document.publishEvent(
                Initialized(document.presences.value),
            )
            document.setOnlineClients(emptySet())
            document.publishEvent(StreamConnectionChanged.Disconnected)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun createPresenceWatchJob(attachment: Attachment<out Attachable>): Job {
        val presence = attachment.resource as Presence
        val presenceKey = presence.getKey()
        var latestStream: ServerOnlyStreamInterface<*, *>? = null
        return scope.launch(activationJob) {
            var shouldContinue = true
            while (shouldContinue) {
                ensureActive()
                latestStream.safeClose()

                val stream = withTimeoutOrNull(streamTimeout) {
                    service.watchPresence(
                        presenceKey.attachmentBasedRequestHeader,
                    ).also {
                        latestStream = it
                    }
                } ?: continue
                val streamJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    val channel = stream.responseChannel()
                    while (!stream.isReceiveClosed() &&
                        !channel.isClosedForReceive && shouldContinue
                    ) {
                        withTimeoutOrNull(streamTimeout) {
                            val receiveResult = channel.receiveCatching()
                            receiveResult.onSuccess {
                                handleWatchPresenceResponse(
                                    presence = presence,
                                    response = it,
                                )
                                shouldContinue = true
                            }.onFailure {
                                if (receiveResult.isClosed) {
                                    stream.safeClose()
                                    return@onFailure
                                }
                                shouldContinue =
                                    handleWatchPresenceStreamFailure(
                                        stream = stream,
                                        cause = it,
                                        cancelled = attachment.cancelled,
                                    )
                            }.onClosed {
                                handleWatchPresenceStreamFailure(
                                    stream = stream,
                                    cause = it
                                        ?: ClosedReceiveChannelException("Channel was closed"),
                                    cancelled = attachment.cancelled,
                                )
                            }
                        } ?: run {
                            handleWatchPresenceStreamFailure(
                                stream = stream,
                                cause = TimeoutException("channel timed out"),
                                cancelled = attachment.cancelled,
                            )
                            shouldContinue = true
                        }
                    }
                }
                stream.sendAndClose(
                    watchPresenceRequest {
                        clientId = requireClientId().value
                        this.presenceKey = presenceKey
                    },
                )
                streamJob.join()
            }
        }.also {
            it.invokeOnCompletion {
                scope.launch {
                    latestStream.safeClose()
                }
            }
        }
    }

    private fun handleWatchPresenceResponse(presence: Presence, response: WatchPresenceResponse) {
        if (response.hasInitialized()) {
            val count = response.initialized.count
            val seq = response.initialized.seq
            if (presence.updateCount(count, seq)) {
                presence.publish(
                    PresenceEvent.Initialized(count = count),
                )
            }
        } else if (response.hasEvent()) {
            val count = response.event.count
            val seq = response.event.seq
            if (presence.updateCount(count, seq)) {
                presence.publish(
                    PresenceEvent.Changed(count = count),
                )
            }
        }
    }

    /**
     * handleWatchStreamFailure() handles the failure of the watch stream.
     * return true if the stream should be reconnected, false otherwise.
     */
    private suspend fun handleWatchPresenceStreamFailure(
        stream: ServerOnlyStreamInterface<*, *>,
        cause: Throwable?,
        cancelled: Boolean,
    ): Boolean {
        stream.safeClose()

        cause?.let {
            sendWatchAttachmentResourceStreamException(
                tag = "Client.WatchPresence",
                t = it,
            )
        }

        val handleAuthenticationError =
            handleConnectException(cause as? ConnectException) { connectException ->
                if (errorCodeOf(connectException) == ErrUnauthenticated.codeString) {
                    shouldRefreshToken = true
                }
            }

        if (handleAuthenticationError && !cancelled) {
            coroutineContext.ensureActive()
            delay(options.reconnectStreamDelay.inWholeMilliseconds)
            return true
        } else {
            conditions[ClientCondition.WATCH_LOOP] = false
            return false
        }
    }

    private fun sendWatchAttachmentResourceStreamException(tag: String, t: Throwable) {
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

    /**
     * `has` checks if the given resource is attached to this client.
     * @param key - the key of the resource.
     * @returns true if the resource is attached to this client.
     */
    public fun has(key: String): Boolean {
        return attachments.containsKey(key)
    }

    /**
     * Attaches the given [Document] to this [Client].
     * It tells the server that this [Client] will synchronize the given [document].
     * @param initialPresence: is the initial presence of the client.
     * @param syncMode: defines the synchronization mode of the document.
     * @param schema: is the schema of the document. It is used to validate the document.
     */
    public fun attachDocument(
        document: Document,
        initialPresence: P = emptyMap(),
        syncMode: SyncMode = SyncMode.Realtime,
        schema: String? = null,
    ): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            val documentKey = document.getKey()
            checkYorkieError(
                document.getStatus() == ResourceStatus.Detached,
                YorkieException(ErrDocumentNotDetached, "document($documentKey is not detached"),
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
                    schema?.let {
                        schemaKey = it
                    }
                }
                val response = service.attachDocument(
                    request = request,
                    headers = documentKey.attachmentBasedRequestHeader,
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

                val maxSize = response.maxSizePerDocument
                if (maxSize > 0) {
                    document.setMaxSizePerDocument(maxSize)
                }

                if (response.schemaRulesCount > 0) {
                    document.setSchemaRules(response.schemaRulesList.fromSchemaRules())
                }

                val pack = response.changePack.toChangePack()
                document.applyChangePack(pack)

                if (document.getStatus() == ResourceStatus.Removed) {
                    return@async SUCCESS
                }
                document.applyStatus(ResourceStatus.Attached)
                attachments[documentKey] = Attachment(
                    resource = document,
                    resourceId = response.documentId,
                    syncMode = syncMode,
                )
                if (syncMode != SyncMode.Manual) {
                    runWatchLoop(documentKey)
                }
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
    @OptIn(DelicateCoroutinesApi::class)
    public fun detachDocument(
        document: Document,
        keepalive: Boolean = false,
    ): Deferred<OperationResult> {
        checkYorkieError(
            isActive,
            YorkieException(ErrClientNotActivated, "client is not active"),
        )

        val documentKey = document.getKey()
        val attachment = attachments[documentKey]
            ?: throw YorkieException(
                ErrDocumentNotAttached,
                "document($documentKey) is not attached",
            )

        val task = suspend suspend@{
            document.mutex.withLock {
                document.updateAsync { _, presence ->
                    presence.clear()
                }.await()

                val request = detachDocumentRequest {
                    clientId = requireClientId().value
                    changePack = document.createChangePack().toPBChangePack()
                    documentId = attachment.resourceId
                }
                val response = service.detachDocument(
                    request,
                    documentKey.attachmentBasedRequestHeader,
                ).getOrElse {
                    handleConnectException(it) { exception ->
                        if (errorCodeOf(exception) == ErrUnauthenticated.codeString) {
                            shouldRefreshToken = true
                        }
                        deactivateInternal()
                    }
                    return@suspend Result.failure(it)
                }
                val pack = response.changePack.toChangePack()
                document.applyChangePack(pack)
                if (document.getStatus() != ResourceStatus.Removed) {
                    document.applyStatus(ResourceStatus.Detached)
                    detachInternal(documentKey)
                }
            }
            SUCCESS
        }

        return if (keepalive) {
            GlobalScope.async(Dispatchers.IO) {
                withContext(NonCancellable) {
                    task()
                }
            }
        } else {
            scope.async {
                task()
            }
        }
    }

    /**
     * `attach` attaches the given presence counter to this client.
     * It tells the server that this client will track the presence count.
     *
     * @param presence The presence counter to attach.
     * @param isRealtime If true (default), starts watching for presence changes in realtime.
     *                   If false, uses manual sync mode where you must call [syncPresence] to
     *                   refresh the presence count.
     */
    public fun attachPresence(
        presence: Presence,
        isRealtime: Boolean? = null,
    ): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            checkYorkieError(
                presence.getStatus() == ResourceStatus.Detached,
                YorkieException(ErrDocumentNotDetached, "${presence.getKey()} is not detached"),
            )

            presence.setActor(requireClientId())

            presence.mutex.withLock {
                val presenceKey = presence.getKey()
                val request = attachPresenceRequest {
                    clientId = requireClientId().value
                    this.presenceKey = presenceKey
                }

                val response = service.attachPresence(
                    request,
                    presenceKey.attachmentBasedRequestHeader,
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

                presence.setPresenceId(response.presenceId)
                presence.updateCount(response.count, 0L)
                presence.applyStatus(ResourceStatus.Attached)

                val syncMode = if (isRealtime != false) {
                    SyncMode.Realtime
                } else {
                    SyncMode.Manual
                }

                attachments[presenceKey] = Attachment(
                    resource = presence,
                    resourceId = response.presenceId,
                    syncMode = syncMode,
                )

                // Only start watch loop for realtime mode
                if (syncMode == SyncMode.Realtime) {
                    runWatchLoop(presenceKey)
                }
            }
            SUCCESS
        }
    }

    /**
     * `detachPresence` detaches the given presence counter from this client.
     * It tells the server that this client will no longer track the presence count.
     */
    public fun detachPresence(presence: Presence): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            attachments[presence.getKey()]
                ?: throw YorkieException(
                    ErrDocumentNotAttached,
                    "${presence.getKey()} is not attached",
                )

            val presenceKey = presence.getKey()

            val request = detachPresenceRequest {
                clientId = requireClientId().value
                presence.getPresenceId()?.let {
                    presenceId = it
                }
                this.presenceKey = presenceKey
            }

            val response = service.detachPresence(
                request,
                presenceKey.attachmentBasedRequestHeader,
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

            presence.updateCount(response.count, 0L)
            presence.applyStatus(ResourceStatus.Detached)

            detachInternal(presenceKey)

            SUCCESS
        }
    }

    private fun detachInternal(key: String) {
        val attachment = attachments[key] ?: return
        attachment.cancelWatchJob()
        attachments.remove(key)
        mutexForAttachments.remove(key)
    }

    /**
     * Deactivates this [Client].
     *
     * @param keepalive 비활성화 요청을 앱이 종료되더라도 완료하도록 보장합니다. 페이지 언로드 또는 앱 종료 시에 사용합니다.
     */
    @OptIn(DelicateCoroutinesApi::class)
    public fun deactivateAsync(
        deactivateOptions: DeactivateOptions = DeactivateOptions(),
    ): Deferred<OperationResult> {
        if (!isActive) {
            return CompletableDeferred(SUCCESS)
        }

        val task = suspend {
            activationJob.cancelChildren()
            try {
                service.deactivateClient(
                    request = deactivateClientRequest {
                        clientId = requireClientId().value
                        deactivateOptions.synchronous?.let {
                            synchronous = it
                        }
                    },
                    headers = projectBasedRequestHeader,
                ).getOrThrow()

                deactivateInternal()
                SUCCESS
            } catch (e: ConnectException) {
                handleConnectException(e) { exception ->
                    if (errorCodeOf(exception) == ErrUnauthenticated.codeString) {
                        shouldRefreshToken = true
                    }
                    deactivateInternal()
                }
                Result.failure(e)
            }
        }

        return if (deactivateOptions.keepalive == true) {
            GlobalScope.async(Dispatchers.IO) {
                withContext(NonCancellable) {
                    task()
                }
            }
        } else {
            scope.async { task() }
        }
    }

    private suspend fun deactivateInternal() {
        attachments.values.forEach {
            detachInternal(it.resource.getKey())
            it.resource.applyStatus(ResourceStatus.Detached)
        }

        _status.emit(Status.Deactivated)
    }

    /**
     * Removes the given [document].
     */
    public fun removeDocument(document: Document): Deferred<OperationResult> {
        return scope.async {
            checkYorkieError(
                isActive,
                YorkieException(ErrClientNotActivated, "client is not active"),
            )

            document.mutex.withLock {
                val documentKey = document.getKey()
                val attachment = attachments[documentKey]
                    ?: throw YorkieException(
                        ErrDocumentNotAttached,
                        "document($documentKey) is not attached",
                    )

                val request = removeDocumentRequest {
                    clientId = requireClientId().value
                    changePack = document.createChangePack(forceRemove = true).toPBChangePack()
                    documentId = attachment.resourceId
                }
                val response = service.removeDocument(
                    request,
                    documentKey.attachmentBasedRequestHeader,
                ).getOrElse {
                    ensureActive()
                    return@async Result.failure(it)
                }
                val pack = response.changePack.toChangePack()
                document.applyChangePack(pack)
                detachInternal(documentKey)
            }
            SUCCESS
        }
    }

    public fun broadcast(
        key: String,
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

            val attachment = attachments[key] ?: throw YorkieException(
                ErrDocumentNotAttached,
                "$key is not attached",
            )
            val clientID = requireClientId()

            val request = broadcastRequest {
                clientId = clientID.value
                documentId = attachment.resourceId
                this.topic = topic
                this.payload = ByteString.copyFromUtf8(payload)
            }

            while (retryCount <= maxRetries) {
                try {
                    service.broadcast(
                        request,
                        key.attachmentBasedRequestHeader,
                    ).getOrElse {
                        throw it
                    }
                    return SUCCESS
                } catch (err: Exception) {
                    if (err is ConnectException &&
                        errorCodeOf(err) == ErrUnauthenticated.codeString
                    ) {
                        shouldRefreshToken = true
                        attachment.resource.publish(
                            event = AuthError(
                                errorMetadataOf(err)?.get("reason") ?: "AuthError",
                                Document.Event.AuthError.AuthErrorMethod.Broadcast,
                            ),
                        )
                    }
                    retryCount++
                    if (retryCount > maxRetries) {
                        logError(
                            "BROADCAST",
                            "Exceeded maximum retry attempts for topic $topic",
                        )
                        throw err
                    }

                    val retryInterval = exponentialBackoff(retryCount - 1)
                    logError(
                        "BROADCAST",
                        "Retry attempt $retryCount/$maxRetries " +
                            "for topic $topic after $retryInterval ms",
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

        val attachment = attachments[document.getKey()]
            ?: throw YorkieException(
                ErrDocumentNotAttached,
                "document(${document.getKey()}) is not attached",
            )

        val prevSyncMode = attachment.syncMode
        if (prevSyncMode == syncMode) {
            return
        }

        attachment.syncMode = syncMode

        if (syncMode == SyncMode.Manual) {
            attachment.cancelWatchJob()
            return
        }

        if (syncMode == SyncMode.Realtime) {
            attachment.changeEventReceived = true
        }

        if (prevSyncMode == SyncMode.Manual) {
            runWatchLoop(document.getKey())
        }
    }

    private suspend fun handleDocumentAuthenticationError(
        exception: Throwable?,
        document: Document,
        method: AuthError.AuthErrorMethod,
    ): Boolean {
        return handleConnectException(exception as? ConnectException) { connectException ->
            if (errorCodeOf(connectException) == ErrUnauthenticated.codeString) {
                shouldRefreshToken = true
                document.publish(
                    AuthError(
                        errorMetadataOf(connectException)?.get("reason") ?: "AuthError",
                        method,
                    ),
                )
            }
        }
    }

    override fun close() {
        scope.cancel()
        (dispatcher as? Closeable)?.close()
        unaryClient.dispatcher.executorService.shutdown()
        streamClient.dispatcher.executorService.shutdown()
    }

    private data class SyncResult(
        val attachment: Attachment<out Attachable>,
        val result: OperationResult,
    )

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
         * `metadata` is the metadata of the client. It is used to store additional
         * information about the client.
         */
        public val metadata: Map<String, String> = emptyMap(),
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
        /**
         * `presenceHeartbeatInterval` is the interval of the presence heartbeat.
         * The client sends a heartbeat to the server to refresh the presence TTL.
         * The default value is `30000`(ms).
         */
        public val presenceHeartbeatInterval: Duration = 30_000.milliseconds,
    )

    /**
     * `DeactivateOptions` are user-settable options used when deactivating clients.
     */
    public data class DeactivateOptions(
        /**
         * `keepalive` is used to enable the keepalive option when deactivating.
         * If true, the client will request deactivation immediately using `fetch`
         * with the `keepalive` option enabled. This is useful for ensuring the
         * deactivation request completes even if the page is being unloaded.
         */
        val keepalive: Boolean? = null,

        /**
         * `synchronous` is used to enable the synchronous option when deactivating.
         * If true, the server will wait for all pending operations to complete
         * before deactivating.
         */
        val synchronous: Boolean? = null,
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
