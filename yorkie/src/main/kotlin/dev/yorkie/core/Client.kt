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
import dev.yorkie.api.toRevisionSummary
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchResponse
import dev.yorkie.api.v1.YorkieServiceClient
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.broadcastRequest
import dev.yorkie.api.v1.channelDescriptor
import dev.yorkie.api.v1.createRevisionRequest
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.documentDescriptor
import dev.yorkie.api.v1.getRevisionRequest
import dev.yorkie.api.v1.listRevisionsRequest
import dev.yorkie.api.v1.peekChannelRequest
import dev.yorkie.api.v1.pushPullChangesRequest
import dev.yorkie.api.v1.refreshChannelRequest
import dev.yorkie.api.v1.removeDocumentRequest
import dev.yorkie.api.v1.resourceDescriptor
import dev.yorkie.api.v1.restoreRevisionRequest
import dev.yorkie.api.v1.watchRequest
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.AuthError
import dev.yorkie.document.Document.Event.AuthError.AuthErrorMethod.PushPull
import dev.yorkie.document.Document.Event.EpochMismatch
import dev.yorkie.document.Document.Event.PresenceChanged.MyPresence.Initialized
import dev.yorkie.document.Document.Event.PresenceChanged.Others
import dev.yorkie.document.Document.Event.StreamConnectionChanged
import dev.yorkie.document.Document.Event.SyncStatusChanged
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.presence.Channel
import dev.yorkie.presence.ChannelEvent
import dev.yorkie.presence.Presence
import dev.yorkie.util.Logger.Companion.log
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.OperationResult
import dev.yorkie.util.SUCCESS
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrClientNotActivated
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotAttached
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotDetached
import dev.yorkie.util.YorkieException.Code.ErrEpochMismatch
import dev.yorkie.util.YorkieException.Code.ErrSessionNotFound
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
import dev.yorkie.api.v1.ChannelEvent.Type as PbChannelEventType

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

    // Set immediately when deactivate is requested so the sync loop exits early and
    // suppresses errors from in-flight RPCs. Volatile because the keepalive deactivate
    // path runs on a different thread (GlobalScope + Dispatchers.IO).
    @Volatile
    @VisibleForTesting
    internal var deactivating = false
    private var syncLoopJob: Job? = null

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
            _status.emit(Status.Activated(activateResponse.clientId))
            deactivating = false
            runSyncLoop()
            SUCCESS
        }
    }

    /**
     * runSyncLoop() runs the sync loop. The sync loop pushes local changes to
     * the server and pulls remote changes from the server.
     */
    private fun runSyncLoop() {
        // Check-then-launch is race-free only because this is a non-suspending
        // fun and both callers (activateAsync, attachChannel) run on the
        // client's single-threaded dispatcher; keep it that way.
        if (syncLoopJob?.isActive == true) {
            return
        }
        conditions[ClientCondition.SYNC_LOOP] = true
        syncLoopJob = scope.launch(activationJob) {
            while (true) {
                // A channel-only client that has not activated yet keeps the
                // loop alive as long as it has an attachment: the first
                // RefreshChannel is what activates it.
                if ((!isActive && attachments.isEmpty()) || deactivating) {
                    conditions[ClientCondition.SYNC_LOOP] = false
                    return@launch
                }
                for ((_, attachment) in attachments) {
                    // Stop syncing if the client is being deactivated.
                    if (deactivating) {
                        break
                    }

                    val heartbeatInterval = options.channelHeartbeatInterval.inWholeMilliseconds
                    val pollInterval = options.documentPollInterval.inWholeMilliseconds
                    if (!attachment.needSync(heartbeatInterval, pollInterval)) {
                        continue
                    }

                    // Reset changeEventReceived for Document resources
                    if (attachment.changeEventReceived != null) {
                        attachment.changeEventReceived = false
                    }

                    val (attachment, result) = syncInternal(attachment, attachment.syncMode)
                    // Suppress sync errors from in-flight RPCs once deactivation started.
                    if (deactivating) {
                        conditions[ClientCondition.SYNC_LOOP] = false
                        return@launch
                    }
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
            // Channels may sync pre-activation: the first RefreshChannel
            // activates the client lazily. Documents still require activate.
            checkYorkieError(
                isActive || resource is Channel,
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

                        // Reset the poll timer up front so a Polling document
                        // syncs once per interval, not on every sync-loop tick.
                        // Done before the RPC so a failed push-pull does not
                        // retry every 50ms and drain battery / hammer the
                        // server during outages. #1243.
                        if (attachment.syncMode == SyncMode.Polling) {
                            attachment.updateHeartbeatTime()
                        }

                        val request = pushPullChangesRequest {
                            clientId = requireClientId()
                            changePack = resource.createChangePack().toPBChangePack()
                            documentId = attachment.resourceId
                            pushOnly = syncMode == SyncMode.RealtimePushOnly
                            disableGc = attachment.disableGC
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
                            (
                                currentSyncMode == SyncMode.RealtimePushOnly ||
                                    currentSyncMode == SyncMode.RealtimeSyncOff
                                )
                        ) {
                            return@runCatching
                        }
                        resource.applyChangePack(responsePack)
                        attachment.resource.publish(
                            event = SyncStatusChanged.Synced,
                        )

                        // Reset the poll timer so a Polling document syncs once
                        // per interval, not on every sync-loop tick. #1243.
                        if (attachment.syncMode == SyncMode.Polling) {
                            attachment.updateHeartbeatTime()
                        }

                        // NOTE(chacha912): If a document has been removed, watchStream should
                        // be disconnected to not receive an event for that document.
                        if (resource.getStatus() == ResourceStatus.Removed) {
                            detachInternal(documentKey)
                        }
                    }
                } else if (resource is Channel) {
                    resource.mutex.withLock {
                        // Stale-attachment guard: detachInternal removes the per-key
                        // mutex, so a loop iteration that already selected this
                        // attachment could mint a fresh mutex and run against a
                        // detached channel.
                        if (attachment.cancelled || attachment.detaching ||
                            attachments[resource.getKey()] !== attachment
                        ) {
                            return@runCatching
                        }
                        val isFirstCall = resource.getSessionId().isNullOrEmpty()
                        val request = refreshChannelRequest {
                            clientId = if (isActive) requireClientId() else ""
                            channelKey = resource.getKey()
                            resource.getSessionId()?.let {
                                sessionId = it
                            }
                            if (isFirstCall) {
                                // The first call activates lazily: it carries the
                                // client identity and receives the assigned ids.
                                clientKey = options.key
                                metadata.putAll(options.metadata)
                            }
                        }
                        val response = service.refreshChannel(
                            request,
                            resource.getKey().attachmentBasedRequestHeader,
                        ).getOrElse {
                            if (!isFirstCall && it is ConnectException &&
                                errorCodeOf(it) == ErrSessionNotFound.codeString
                            ) {
                                // Session reclaimed (TTL). Clear ids so the next
                                // heartbeat tick re-enters the first-call path and
                                // re-attaches transparently. Not applied on first
                                // calls: recovery there would clear already-empty
                                // ids and hot-retry on every loop tick.
                                resource.setSessionId(null)
                                attachment.resourceId = ""
                                attachment.updateHeartbeatTime()
                                return@runCatching
                            }
                            throw it
                        }
                        // Drop a stale response if the channel was detached while
                        // the RPC was in flight; applying it would resurrect the
                        // session the user just detached.
                        if (attachment.detaching ||
                            attachments[resource.getKey()] !== attachment
                        ) {
                            return@runCatching
                        }
                        if (response.clientId.isNotEmpty() && !isActive) {
                            _status.emit(Status.Activated(response.clientId))
                        }
                        if (isActive) {
                            resource.setActor(requireClientId())
                        }
                        if (response.sessionId.isNotEmpty()) {
                            resource.setSessionId(response.sessionId)
                            attachment.resourceId = response.sessionId
                            // Attached only once a real server session exists.
                            resource.applyStatus(ResourceStatus.Attached)
                        }
                        // Publish on value change, not on seq freshness like the watch
                        // path: refresh pins seq=0 which is always accepted, so the
                        // freshness check alone would fire on every refresh tick. #1247.
                        val previousSessionCount = resource.getSessionCount()
                        if (resource.updateSessionCount(response.sessionCount, 0L) &&
                            resource.getSessionCount() != previousSessionCount
                        ) {
                            resource.publish(
                                ChannelEvent.Changed(
                                    sessionCount = resource.getSessionCount(),
                                ),
                            )
                        }
                        attachment.updateHeartbeatTime()
                        // Realtime watch stream is deferred to here: the Watch RPC
                        // needs the client id, which the first refresh populates.
                        if (isFirstCall && attachment.syncMode == SyncMode.Realtime &&
                            attachment.watchJobHolder == null
                        ) {
                            runWatchLoop(resource.getKey())
                        }
                    }
                }
            }.onFailure {
                coroutineContext.ensureActive()

                // Suppressed during detach/deactivate teardown so callers do not
                // see a spurious error flash on the way out.
                if (resource is Channel && !attachment.detaching &&
                    attachments[resource.getKey()] === attachment
                ) {
                    resource.publish(ChannelEvent.SyncError(cause = it))
                }

                if (resource is Document) {
                    resource.publish(
                        event = SyncStatusChanged.SyncFailed(
                            cause = it,
                        ),
                    )

                    // NOTE: If the server returns ErrEpochMismatch, the document was
                    // compacted and this client must detach and reattach to recover.
                    if (it is ConnectException &&
                        errorCodeOf(it) == ErrEpochMismatch.codeString
                    ) {
                        resource.publish(
                            event = EpochMismatch(
                                method = EpochMismatch.EpochMismatchMethod.PushPull,
                            ),
                        )
                    }
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
     * Runs the watch loop for the given resource. The watch loop
     * listens to events from the server via the unified Watch RPC.
     */
    private fun runWatchLoop(key: String) {
        scope.launch(activationJob) {
            // Detached while this launch was pending; throwing here would
            // crash the process from an unhandled coroutine exception.
            val attachment = attachments[key] ?: run {
                logDebug("WD", "watch loop skipped, $key is not attached")
                return@launch
            }

            conditions[ClientCondition.WATCH_LOOP] = true

            attachment.watchJobHolder = WatchJobHolder(
                attachment.resource.getKey(),
                when (attachment.resource) {
                    is Document -> createDocumentWatchJob(attachment)
                    is Channel -> createChannelWatchJob(attachment)
                    else -> throw IllegalArgumentException("unknown attachment resource type")
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
                    service.watch(documentKey.attachmentBasedRequestHeader).also {
                        latestStream = it
                    }
                } ?: continue

                val streamJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    val responseChannel = stream.responseChannel()
                    while (!stream.isReceiveClosed() &&
                        !responseChannel.isClosedForReceive && shouldContinue
                    ) {
                        withTimeoutOrNull(streamTimeout) {
                            val receiveResult = responseChannel.receiveCatching()
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
                                shouldContinue = handleWatchDocumentStreamFailure(
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
                    watchRequest {
                        clientId = requireClientId()
                        resources += resourceDescriptor {
                            this.document = documentDescriptor {
                                documentId = attachment.resourceId
                            }
                        }
                    },
                )

                document.publish(StreamConnectionChanged.Connected)

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

    private suspend fun handleWatchDocumentResponse(documentKey: String, response: WatchResponse) {
        if (response.hasInitialization()) {
            val document = attachments[documentKey]?.resource as? Document ?: return
            for (ri in response.initialization.resourceInitsList) {
                if (!ri.hasDocumentInit()) continue
                val clientIDs = ri.documentInit.clientIdsList
                document.publishEvent(
                    Initialized(
                        document.allPresences.value.filterKeys { it in clientIDs }.asPresences(),
                    ),
                )
                document.setOnlineClients(clientIDs.toSet())
                val selfId = requireClientId()
                for (clientID in document.allPresences.value.keys) {
                    if (clientID != selfId && clientID !in clientIDs) {
                        document.clearPresence(clientID)
                    }
                }
            }
            return
        }

        if (!response.hasEvent()) return
        val watchEvent = response.event
        if (!watchEvent.hasDocEvent()) return

        val docWatchEvent = watchEvent.docEvent
        val eventType = checkNotNull(docWatchEvent.event.type)
        val attachment = attachments[documentKey] ?: return
        val document = attachment.resource as? Document ?: return
        val publisher = docWatchEvent.event.publisher

        when (eventType) {
            DocEventType.DOC_EVENT_TYPE_DOCUMENT_WATCHED -> {
                if (document.getOnlineClients()
                        .contains(publisher) && document.presences.value.contains(publisher)
                ) {
                    return
                }
                val presence = document.allPresences.value[publisher]
                if (presence != null) {
                    document.publishEvent(Others.Watched(PresenceInfo(publisher, presence)))
                }
                document.addOnlineClient(publisher)
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_UNWATCHED -> {
                val presence = document.presences.value[publisher] ?: return
                document.publishEvent(Others.Unwatched(PresenceInfo(publisher, presence)))
                document.removeOnlineClient(publisher)
                document.clearPresence(publisher)
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED -> {
                if (attachment.changeEventReceived != null) {
                    attachment.changeEventReceived = true
                }
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_BROADCAST -> {
                val topic = docWatchEvent.event.body.topic
                val payload = docWatchEvent.event.body.payload.toStringUtf8()
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
     * Handles a failure on the document watch stream.
     * Returns true if the stream should be reconnected, false otherwise.
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
            sendWatchAttachmentResourceStreamException(tag = "Client.Watch", t = it)
        }

        if (handleDocumentAuthenticationError(
                exception = cause,
                document = document,
                method = AuthError.AuthErrorMethod.Watch,
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
            document.publishEvent(Initialized(document.presences.value))
            document.setOnlineClients(emptySet())
            document.publishEvent(StreamConnectionChanged.Disconnected)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun createChannelWatchJob(attachment: Attachment<out Attachable>): Job {
        val channel = attachment.resource as Channel
        val channelKey = channel.getKey()
        var latestStream: ServerOnlyStreamInterface<*, *>? = null
        return scope.launch(activationJob) {
            var shouldContinue = true
            while (shouldContinue) {
                ensureActive()
                latestStream.safeClose()

                val stream = withTimeoutOrNull(streamTimeout) {
                    service.watch(channelKey.attachmentBasedRequestHeader).also {
                        latestStream = it
                    }
                } ?: continue
                val streamJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    val responseChannel = stream.responseChannel()
                    while (!stream.isReceiveClosed() &&
                        !responseChannel.isClosedForReceive && shouldContinue
                    ) {
                        withTimeoutOrNull(streamTimeout) {
                            val receiveResult = responseChannel.receiveCatching()
                            receiveResult.onSuccess {
                                handleWatchChannelResponse(channel = channel, response = it)
                                shouldContinue = true
                            }.onFailure {
                                if (receiveResult.isClosed) {
                                    stream.safeClose()
                                    return@onFailure
                                }
                                shouldContinue = handleWatchChannelStreamFailure(
                                    stream = stream,
                                    cause = it,
                                    cancelled = attachment.cancelled,
                                )
                            }.onClosed {
                                handleWatchChannelStreamFailure(
                                    stream = stream,
                                    cause = it
                                        ?: ClosedReceiveChannelException("Channel was closed"),
                                    cancelled = attachment.cancelled,
                                )
                            }
                        } ?: run {
                            handleWatchChannelStreamFailure(
                                stream = stream,
                                cause = TimeoutException("channel timed out"),
                                cancelled = attachment.cancelled,
                            )
                            shouldContinue = true
                        }
                    }
                }
                stream.sendAndClose(
                    watchRequest {
                        clientId = requireClientId()
                        resources += resourceDescriptor {
                            this.channel = channelDescriptor {
                                this.channelKey = channelKey
                            }
                        }
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

    private fun handleWatchChannelResponse(channel: Channel, response: WatchResponse) {
        if (response.hasInitialization()) {
            for (ri in response.initialization.resourceInitsList) {
                if (!ri.hasChannelInit()) continue
                val sessionCount = ri.channelInit.sessionCount
                val seq = ri.channelInit.seq
                if (channel.updateSessionCount(sessionCount, seq)) {
                    channel.publish(ChannelEvent.Initialized(sessionCount = sessionCount))
                }
            }
            return
        }

        if (!response.hasEvent()) return
        val watchEvent = response.event
        if (!watchEvent.hasChannelEvent()) return

        val event = watchEvent.channelEvent.event
        when (event.type) {
            PbChannelEventType.TYPE_PRESENCE -> {
                val sessionCount = event.sessionCount
                val seq = event.seq
                if (channel.updateSessionCount(sessionCount, seq)) {
                    channel.publish(ChannelEvent.Changed(sessionCount = sessionCount))
                }
            }

            PbChannelEventType.TYPE_BROADCAST -> {
                channel.publish(
                    ChannelEvent.Broadcast(
                        actorID = event.publisher.takeIf { it.isNotEmpty() },
                        topic = event.topic,
                        payload = event.payload.toStringUtf8(),
                    ),
                )
            }

            else -> {
                // nothing to do
            }
        }
    }

    /**
     * Handles a failure on the channel watch stream.
     * Returns true if the stream should be reconnected, false otherwise.
     */
    private suspend fun handleWatchChannelStreamFailure(
        stream: ServerOnlyStreamInterface<*, *>,
        cause: Throwable?,
        cancelled: Boolean,
    ): Boolean {
        stream.safeClose()

        cause?.let {
            sendWatchAttachmentResourceStreamException(tag = "Client.Watch", t = it)
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
     * @param disableGC: declares that this attachment will not produce or consume
     * tombstones. The server skips minVV tracking and omits the response
     * VersionVector for this client. Use only with Counter or primitive
     * workloads where no client consumes tombstones; misuse on a document
     * that uses Tree, Text, or Array deletions leads to undefined GC behavior
     * on this client. Controls only the wire contract and is distinct from
     * any local-only [Document] GC pass.
     * @param disablePresence: declares that this document does not produce,
     * consume, or store presence. When true, the initial presence is not
     * pushed and presence updates from [Document.updateAsync] are dropped.
     * Honored on first attach only; the server persists the value and
     * returns it on subsequent attaches.
     */
    public fun attachDocument(
        document: Document,
        initialPresence: P = emptyMap(),
        syncMode: SyncMode = SyncMode.Realtime,
        schema: String? = null,
        disableGC: Boolean = false,
        disablePresence: Boolean = false,
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
                // The local option wins; absent that, the document's seeded
                // value is used. The server is authoritative and overwrites
                // this via the attach response. Mirrors JS SDK PR #1285.
                val resolvedDisablePresence = disablePresence || document.isPresenceDisabled()
                if (!resolvedDisablePresence) {
                    document.updateAsync { _, presence ->
                        presence.put(initialPresence)
                    }.await()
                }

                val request = attachDocumentRequest {
                    clientId = clientID
                    changePack = document.createChangePack().toPBChangePack()
                    schema?.let {
                        schemaKey = it
                    }
                    disableGc = disableGC
                    this.disablePresence = resolvedDisablePresence
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
                // Record the opt-out decision before applying the attach response
                // so the first applyChangePack already routes remote changes
                // through the lamport-only sync path.
                document.setDisableGC(disableGC)
                document.setDisablePresence(response.disablePresence)
                document.applyChangePack(pack)

                // Clear undo/redo stacks so that initialRoot setup operations
                // are not reachable via undo. Mirrors JS SDK PR #1238.
                document.clearHistory()

                if (document.getStatus() == ResourceStatus.Removed) {
                    return@async SUCCESS
                }
                document.applyStatus(ResourceStatus.Attached)
                attachments[documentKey] = Attachment(
                    resource = document,
                    resourceId = response.documentId,
                    syncMode = syncMode,
                    disableGC = disableGC,
                    disablePresence = response.disablePresence,
                )
                // Manual and Polling are stream-less modes; only realtime modes
                // open a watch stream. Mirrors JS SDK PR #1243.
                if (syncMode != SyncMode.Manual && syncMode != SyncMode.Polling) {
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
                    clientId = requireClientId()
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
     * `attachChannel` attaches the given channel counter to this client.
     * It registers the channel locally; the server is notified by the first
     * RefreshChannel heartbeat, which creates the session and — for a client
     * that never called [activateAsync] — activates the client lazily.
     *
     * The returned [Deferred] completing only means local registration.
     * Server-side attach finishes asynchronously: observe
     * [ChannelEvent.Initialized]/[ChannelEvent.Changed] or [Channel.getSessionId]
     * for server state. Requires a Yorkie server >= 0.7.10.
     *
     * @param channel The channel counter to attach.
     * @param isRealtime If true (default), starts watching for channel changes in realtime.
     *                   If false, uses manual sync mode where you must call [syncAsync] to
     *                   refresh the channel count.
     */
    public fun attachChannel(
        channel: Channel,
        isRealtime: Boolean? = null,
    ): Deferred<OperationResult> {
        return scope.async {
            val channelKey = channel.getKey()
            // Status stays Detached until the first refresh returns a real
            // session id, so the attachments map is the double-attach guard.
            checkYorkieError(
                channel.getStatus() == ResourceStatus.Detached &&
                    !attachments.containsKey(channelKey),
                YorkieException(ErrDocumentNotDetached, "$channelKey is not detached"),
            )

            if (isActive) {
                channel.setActor(requireClientId())
            }

            channel.mutex.withLock {
                attachments[channelKey] = Attachment(
                    resource = channel,
                    resourceId = "",
                    syncMode = if (isRealtime != false) {
                        SyncMode.Realtime
                    } else {
                        SyncMode.Manual
                    },
                )
            }
            // Lazy activation entry point: the loop's first tick performs the
            // first-call RefreshChannel. The watch loop is deferred to that
            // tick as well, because the Watch RPC needs the client id.
            runSyncLoop()
            SUCCESS
        }
    }

    /**
     * `detachChannel` detaches the given channel counter from this client.
     * Cleanup is local-only: heartbeats stop and the server reclaims the
     * session via TTL. No RPC is sent.
     */
    public fun detachChannel(channel: Channel): Deferred<OperationResult> {
        return scope.async {
            val channelKey = channel.getKey()
            val attachment = attachments[channelKey]
                ?: throw YorkieException(
                    ErrDocumentNotAttached,
                    "$channelKey is not attached",
                )

            // Set before taking the mutex so an in-flight refresh resuming
            // from its network await drops its side effects instead of
            // resurrecting the session.
            attachment.detaching = true

            channel.mutex.withLock {
                // Clear the session id: the server-side session dies by TTL,
                // and a stale id on re-attach would force a guaranteed
                // ErrSessionNotFound round trip before recovery.
                channel.setSessionId(null)
                channel.applyStatus(ResourceStatus.Detached)
                channel.close()
                detachInternal(channelKey)
            }

            SUCCESS
        }
    }

    /**
     * `peekChannel` reads the current session count of a channel without creating
     * a session on the server. Use this when the caller only needs to display the
     * count (e.g. "N people writing") without contributing to it and without
     * receiving broadcasts.
     *
     * Unlike attaching, this does not occupy a session entry on the server, does
     * not generate heartbeat RPCs, and does not subscribe to channel events.
     * Polling is the caller's responsibility.
     *
     * @param channelKey The key of the channel to peek.
     * @return The current online session count of the channel.
     */
    public fun peekChannel(channelKey: String): Deferred<Result<Long>> {
        return scope.async {
            val request = peekChannelRequest {
                this.channelKey = channelKey
            }

            val response = service.peekChannel(
                request,
                channelKey.attachmentBasedRequestHeader,
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

            Result.success(response.sessionCount)
        }
    }

    @Deprecated(
        "Renamed to attachChannel",
        replaceWith = ReplaceWith("attachChannel(channel, isRealtime)"),
    )
    public fun attachPresence(presence: Presence, isRealtime: Boolean? = null) =
        attachChannel(presence, isRealtime)

    @Deprecated(
        "Renamed to detachChannel",
        replaceWith = ReplaceWith("detachChannel(channel)"),
    )
    public fun detachPresence(presence: Presence) = detachChannel(presence)

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
            // A channel-only client that never activated may still have a
            // running sync loop and attachments; tear them down locally so a
            // pending first-call refresh cannot activate the client after
            // deactivation. No RPC: the client has no server identity yet.
            if (attachments.isNotEmpty() || syncLoopJob?.isActive == true) {
                activationJob.cancelChildren()
                return scope.async {
                    deactivateInternal()
                    SUCCESS
                }
            }
            return CompletableDeferred(SUCCESS)
        }

        // Mark as deactivating synchronously, before the task is dispatched, so an
        // already-queued sync-loop iteration on the same dispatcher observes it and
        // stops before sending another RPC.
        deactivating = true

        val task = suspend {
            activationJob.cancelChildren()
            try {
                service.deactivateClient(
                    request = deactivateClientRequest {
                        clientId = requireClientId()
                        deactivateOptions.synchronous?.let {
                            synchronous = it
                        }
                    },
                    headers = projectBasedRequestHeader,
                ).getOrThrow()

                deactivateInternal()
                SUCCESS
            } catch (e: ConnectException) {
                deactivating = false
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
                    clientId = requireClientId()
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

    /**
     * Creates a revision snapshot for the given [document] with the given [label] and
     * optional [description]. The document must be attached to this client.
     */
    public fun createRevision(
        document: Document,
        label: String,
        description: String = "",
    ): Deferred<RevisionSummary> {
        return scope.async {
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

            val request = createRevisionRequest {
                clientId = requireClientId()
                documentId = attachment.resourceId
                this.label = label
                this.description = description
            }
            val response = service.createRevision(
                request,
                documentKey.attachmentBasedRequestHeader,
            ).getOrThrow()

            if (!response.hasRevision()) {
                throw YorkieException(
                    YorkieException.Code.ErrInvalidArgument,
                    "revision is not returned",
                )
            }

            logDebug("CR", "c:\"${options.key}\" creates revision d:\"$documentKey\" l:\"$label\"")
            response.revision.toRevisionSummary()
        }
    }

    /**
     * Lists revisions for the given [document]. The document must be attached.
     *
     * @param pageSize maximum number of revisions to return (default 10).
     * @param offset number of revisions to skip for pagination (default 0).
     * @param isForward when true, returns oldest-first; false (default) returns newest-first.
     */
    public fun listRevisions(
        document: Document,
        pageSize: Int = 10,
        offset: Int = 0,
        isForward: Boolean = false,
    ): Deferred<List<RevisionSummary>> {
        return scope.async {
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

            val request = listRevisionsRequest {
                clientId = requireClientId()
                documentId = attachment.resourceId
                this.pageSize = pageSize
                this.offset = offset
                this.isForward = isForward
            }
            val response = service.listRevisions(
                request,
                documentKey.attachmentBasedRequestHeader,
            ).getOrThrow()

            logDebug(
                "LR",
                "c:\"${options.key}\" lists revisions d:\"$documentKey\"" +
                    " count:${response.revisionsCount}",
            )
            response.revisionsList.map { it.toRevisionSummary() }
        }
    }

    /**
     * Retrieves the revision identified by [revisionId] for the given [document],
     * including its full snapshot. The document must be attached to this client.
     */
    public fun getRevision(document: Document, revisionId: String): Deferred<RevisionSummary> {
        return scope.async {
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

            val request = getRevisionRequest {
                clientId = requireClientId()
                documentId = attachment.resourceId
                this.revisionId = revisionId
            }
            val response = service.getRevision(
                request,
                documentKey.attachmentBasedRequestHeader,
            ).getOrThrow()

            if (!response.hasRevision()) {
                throw YorkieException(
                    YorkieException.Code.ErrInvalidArgument,
                    "revision is not returned",
                )
            }

            logDebug(
                "GR",
                "c:\"${options.key}\" gets revision d:\"$documentKey\" r:\"$revisionId\"",
            )
            response.revision.toRevisionSummary()
        }
    }

    /**
     * Restores the given [document] to the state captured by the revision with [revisionId].
     * The document must be attached to this client.
     */
    public fun restoreRevision(document: Document, revisionId: String): Deferred<OperationResult> {
        return scope.async {
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

            val request = restoreRevisionRequest {
                clientId = requireClientId()
                documentId = attachment.resourceId
                this.revisionId = revisionId
            }
            service.restoreRevision(
                request,
                documentKey.attachmentBasedRequestHeader,
            ).getOrThrow()

            logDebug(
                "RR",
                "c:\"${options.key}\" restores revision d:\"$documentKey\" r:\"$revisionId\"",
            )
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
                clientId = clientID
                channelKey = key
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
                        if (attachment.resource is Document) {
                            attachment.resource.publish(
                                event = AuthError(
                                    errorMetadataOf(err)?.get("reason") ?: "AuthError",
                                    Document.Event.AuthError.AuthErrorMethod.Broadcast,
                                ),
                            )
                        }
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

    public fun requireClientId(): String {
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

        // Manual and Polling are stream-less: no watch stream. The global sync
        // loop still drives Polling push-pull. Mirrors JS SDK PR #1243.
        if (syncMode == SyncMode.Manual || syncMode == SyncMode.Polling) {
            attachment.cancelWatchJob()
            return
        }

        if (syncMode == SyncMode.Realtime) {
            attachment.changeEventReceived = true
        }

        // Restart the watch stream only when leaving a stream-less mode.
        if (prevSyncMode == SyncMode.Manual || prevSyncMode == SyncMode.Polling) {
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
        public class Activated internal constructor(public val clientId: String) : Status

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

        /**
         * [Polling] runs the sync loop without opening a watch stream: local
         * changes are pushed and remote changes pulled on a fixed interval
         * ([Options.documentPollInterval]). Suited to low-frequency updates;
         * use [Realtime] for collaborative editing. Mirrors JS SDK PR #1243.
         */
        Polling(false),
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
         * `channelHeartbeatInterval` is the interval of the channel heartbeat.
         * The client sends a heartbeat to the server to refresh the channel TTL.
         * Applies to both Realtime and Manual/Polling channels.
         * The default value is `5000`(ms) — TTL/3 for the server's 15s
         * ChannelSessionTTL. Mirrors JS SDK v0.7.10.
         */
        public val channelHeartbeatInterval: Duration = 5_000.milliseconds,
        /**
         * `documentPollInterval` is the push-pull interval for documents attached
         * with [SyncMode.Polling]. Unused in other sync modes.
         * The default value is `3000`(ms). Mirrors JS SDK PR #1243.
         */
        public val documentPollInterval: Duration = 3_000.milliseconds,
    ) {
        @Deprecated(
            "Renamed to channelHeartbeatInterval",
            replaceWith = ReplaceWith("channelHeartbeatInterval"),
        )
        public val presenceHeartbeatInterval: Duration get() = channelHeartbeatInterval
    }

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
