package dev.yorkie.core

import android.content.Context
import com.google.common.annotations.VisibleForTesting
import dev.yorkie.api.toActorID
import dev.yorkie.api.toByteString
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.api.toPBClient
import dev.yorkie.api.toPresence
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchDocumentsResponse
import dev.yorkie.api.v1.YorkieServiceGrpcKt
import dev.yorkie.api.v1.activateClientRequest
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.pushPullRequest
import dev.yorkie.api.v1.updatePresenceRequest
import dev.yorkie.api.v1.watchDocumentsRequest
import dev.yorkie.core.Client.DocumentSyncResult.SyncFailed
import dev.yorkie.core.Client.DocumentSyncResult.Synced
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.document.Document
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.YorkieLogger
import dev.yorkie.util.createSingleThreadDispatcher
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.StatusException
import io.grpc.android.AndroidChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel as EventChannel

/**
 * Client that can communicate with the server.
 * It has [Document]s and sends changes of the documents in local
 * to the server to synchronize with other replicas in remote.
 */
public class Client @VisibleForTesting internal constructor(
    private val channel: Channel,
    private val options: Options = Options(),
    private val eventStream: MutableSharedFlow<Event> = MutableSharedFlow(),
) : Flow<Client.Event> by eventStream {
    private val scope = CoroutineScope(
        SupervisorJob() +
            createSingleThreadDispatcher("Client(${options.key})"),
    )
    private val attachments = mutableMapOf<Document.Key, Attachment>()

    private val _status = MutableStateFlow<Status>(Status.Deactivated)
    public val status = _status.asStateFlow()

    public val isActive: Boolean
        get() = status.value is Status.Activated

    private val _streamConnectionStatus = MutableStateFlow(StreamConnectionStatus.Disconnected)
    public val streamConnectionStatus = _streamConnectionStatus.asStateFlow()

    private val _peerStatus = MutableStateFlow(emptyList<PeerStatus>())
    public val peerStatus = _peerStatus.asStateFlow()

    public var presenceInfo = options.presence ?: PresenceInfo(0, emptyMap())
        private set

    private val service by lazy {
        YorkieServiceGrpcKt.YorkieServiceCoroutineStub(channel, CallOptions.DEFAULT).run {
            val authInterceptor = options.authInterceptor()
            if (authInterceptor == null) this else withInterceptors(authInterceptor)
        }
    }

    private var syncLoop: Job? = null
    private var watchLoop: Job? = null
    private val presenceMapInitEvent = EventChannel<Boolean>(EventChannel.BUFFERED)

    public constructor(
        context: Context,
        rpcHost: String,
        rpcPort: Int,
        options: Options = Options(),
        usePlainText: Boolean = false,
    ) : this(
        channel = AndroidChannelBuilder.forAddress(rpcHost, rpcPort)
            .run { if (usePlainText) usePlaintext() else this }
            .context(context.applicationContext)
            .build(),
        options = options,
    )

    /**
     * Activates this [Client]. That is, it registers itself to the server
     * and receives a unique ID from the server. The given ID is used to
     * distinguish different clients.
     */
    public fun activateAsync(): Deferred<Boolean> {
        return scope.async {
            if (isActive) {
                return@async true
            }
            val activateResponse = try {
                service.activateClient(
                    activateClientRequest {
                        clientKey = options.key
                    },
                )
            } catch (e: StatusException) {
                YorkieLogger.e("Client.activate", e.stackTraceToString())
                return@async false
            }
            _status.emit(
                Status.Activated(
                    activateResponse.clientId.toActorID(),
                    activateResponse.clientKey,
                ),
            )
            runSyncLoop()
            runWatchLoop()
            true
        }
    }

    private fun runSyncLoop() {
        syncLoop?.cancel()
        syncLoop = scope.launch {
            launch {
                while (true) {
                    filterRealTimeSyncNeeded().asSyncFlow().collect { (document, result) ->
                        eventStream.emit(
                            if (result.isSuccess) {
                                DocumentSynced(Synced(document))
                            } else {
                                DocumentSynced(SyncFailed(document, result.exceptionOrNull()))
                            },
                        )
                    }
                    delay(options.syncLoopDuration.inWholeMilliseconds)
                }
            }
        }
    }

    private fun filterRealTimeSyncNeeded() = attachments.filterValues { attachment ->
        attachment.isRealTimeSync &&
            (attachment.document.hasLocalChanges || attachment.remoteChangeEventReceived)
    }.map { (_, attachment) ->
        attachment.remoteChangeEventReceived = false
        attachment.document
    }

    /**
     * Pushes local changes of the attached documents to the server and
     * receives changes of the remote replica from the server then apply them to local documents.
     */
    public fun syncAsync(): Deferred<Boolean> {
        return scope.async {
            var isAllSuccess = true
            attachments.map { (_, attachment) ->
                attachment.document
            }.asSyncFlow().collect { (document, result) ->
                eventStream.emit(
                    if (result.isSuccess) {
                        DocumentSynced(Synced(document))
                    } else {
                        isAllSuccess = false
                        DocumentSynced(SyncFailed(document, result.exceptionOrNull()))
                    },
                )
            }
            isAllSuccess
        }
    }

    private suspend fun List<Document>.asSyncFlow(): Flow<SyncResult> {
        return asFlow()
            .map { document ->
                SyncResult(
                    document,
                    runCatching {
                        val request = pushPullRequest {
                            clientId = requireClientId().toByteString()
                            changePack = document.createChangePack().toPBChangePack()
                        }
                        val response = service.pushPull(request)
                        val responsePack = response.changePack.toChangePack()
                        document.applyChangePack(responsePack)
                    },
                )
            }
    }

    private fun runWatchLoop() {
        watchLoop?.cancel()
        watchLoop = scope.launch {
            val realTimeSyncDocKeys = attachments.filterValues { attachment ->
                attachment.isRealTimeSync
            }.map { (_, attachment) ->
                attachment.document.key.value
            }.takeIf { it.isNotEmpty() } ?: run {
                presenceMapInitEvent.send(true)
                return@launch
            }

            val request = watchDocumentsRequest {
                client = toPBClient()
                documentKeys.addAll(realTimeSyncDocKeys)
            }

            service.watchDocuments(request)
                .retry {
                    _streamConnectionStatus.emit(StreamConnectionStatus.Disconnected)
                    delay(options.reconnectStreamDelay.inWholeMilliseconds)
                    presenceMapInitEvent.send(false)
                    true
                }
                .collect {
                    _streamConnectionStatus.emit(StreamConnectionStatus.Connected)
                    handleWatchDocumentsResponse(it)
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun handleWatchDocumentsResponse(response: WatchDocumentsResponse) {
        if (response.hasInitialization()) {
            response.initialization.peersMapByDocMap.forEach { (documentKey, peers) ->
                val attachment = attachments[Document.Key(documentKey)] ?: return@forEach
                peers.clientsList.forEach { peer ->
                    attachment.peerPresences[peer.id.toActorID()] = peer.presence.toPresence()
                }
            }
            emitPeerStatus()
            if (!presenceMapInitEvent.isClosedForSend) {
                presenceMapInitEvent.send(true)
            }
            return
        }
        val watchEvent = response.event
        val responseKeys = watchEvent.documentKeysList
        val publisher = watchEvent.publisher.id.toActorID()
        val presence = watchEvent.publisher.presence.toPresence()
        responseKeys.forEach { key ->
            val attachment = attachments[Document.Key(key)] ?: return@forEach
            val presences = attachment.peerPresences
            when (watchEvent.type ?: return@forEach) {
                DocEventType.DOC_EVENT_TYPE_DOCUMENTS_WATCHED -> {
                    presences[publisher] = presence
                }
                DocEventType.DOC_EVENT_TYPE_DOCUMENTS_UNWATCHED -> {
                    presences.remove(publisher)
                }
                DocEventType.DOC_EVENT_TYPE_DOCUMENTS_CHANGED -> {
                    attachment.remoteChangeEventReceived = true
                }
                DocEventType.DOC_EVENT_TYPE_PRESENCE_CHANGED -> {
                    if ((presences[publisher]?.clock ?: -1) < presence.clock) {
                        presences[publisher] = presence
                    }
                }
                DocEventType.UNRECOGNIZED -> {
                    // nothing to do
                }
            }
        }

        when (watchEvent.type ?: return) {
            DocEventType.DOC_EVENT_TYPE_DOCUMENTS_CHANGED -> {
                eventStream.emit(Event.DocumentsChanged(responseKeys.map(Document::Key)))
            }
            DocEventType.DOC_EVENT_TYPE_DOCUMENTS_WATCHED,
            DocEventType.DOC_EVENT_TYPE_DOCUMENTS_UNWATCHED,
            DocEventType.DOC_EVENT_TYPE_PRESENCE_CHANGED,
            -> {
                emitPeerStatus()
            }
            DocEventType.UNRECOGNIZED -> TODO()
        }
        if (!presenceMapInitEvent.isClosedForSend) {
            presenceMapInitEvent.send(true)
        }
    }

    private suspend fun emitPeerStatus() {
        _peerStatus.emit(
            attachments.flatMap { (documentKey, attachment) ->
                attachment.peerPresences.map { (actorID, presenceInfo) ->
                    PeerStatus(documentKey, actorID, presenceInfo)
                }
            },
        )
    }

    /**
     * Updates the [PresenceInfo] of this [Client].
     */
    public fun updatePresenceAsync(key: String, value: String): Deferred<Boolean> {
        return scope.async {
            if (!isActive) {
                return@async false
            }

            presenceInfo = presenceInfo.copy(
                clock = presenceInfo.clock + 1,
                data = presenceInfo.data + (key to value),
            )

            val documentKeys = attachments.filter {
                it.value.isRealTimeSync
            }.map { (key, attachment) ->
                attachment.peerPresences[requireClientId()] = presenceInfo
                key.value
            }.takeIf {
                it.isNotEmpty()
            } ?: return@async true

            emitPeerStatus()

            try {
                service.updatePresence(
                    updatePresenceRequest {
                        client = toPBClient()
                        this.documentKeys.addAll(documentKeys)
                    },
                )
                true
            } catch (e: StatusException) {
                YorkieLogger.e("Client.updatePresence", e.stackTraceToString())
                false
            }
        }
    }

    /**
     * Attaches the given [Document] to this [Client].
     * It tells the server that this [Client] will synchronize the given [document].
     */
    public fun attachAsync(
        document: Document,
        isManualSync: Boolean = false,
    ): Deferred<Boolean> {
        return scope.async {
            require(isActive) {
                "client is not active"
            }
            document.setActor(requireClientId())

            val request = attachDocumentRequest {
                clientId = requireClientId().toByteString()
                changePack = document.createChangePack().toPBChangePack()
            }
            val response = try {
                service.attachDocument(request)
            } catch (e: StatusException) {
                YorkieLogger.e("Client.attach", e.stackTraceToString())
                return@async false
            }
            val pack = response.changePack.toChangePack()
            document.applyChangePack(pack)
            attachments[document.key] = Attachment(document, !isManualSync)
            runWatchLoop()
            presenceMapInitEvent.receive()
        }
    }

    /**
     * Detaches the given [doc] from this [Client]. It tells the
     * server that this client will no longer synchronize the given [Document].
     *
     * To collect garbage things like CRDT tombstones left on the [Document], all
     * the changes should be applied to other replicas before GC time. For this,
     * if the [doc] is no longer used by this [Client], it should be detached.
     */
    public fun detachAsync(doc: Document): Deferred<Boolean> {
        return scope.async {
            require(isActive) {
                "client is not active"
            }
            val request = detachDocumentRequest {
                clientId = requireClientId().toByteString()
                changePack = doc.createChangePack().toPBChangePack()
            }
            val response = try {
                service.detachDocument(request)
            } catch (e: StatusException) {
                YorkieLogger.e("Client.detach", e.stackTraceToString())
                return@async false
            }
            val pack = response.changePack.toChangePack()
            doc.applyChangePack(pack)
            attachments.remove(doc.key)
            runWatchLoop()
            true
        }
    }

    /**
     * Deactivates this [Client].
     */
    public fun deactivateAsync(): Deferred<Boolean> {
        return scope.async {
            if (!isActive) {
                return@async false
            }
            syncLoop?.cancel()
            watchLoop?.cancel()
            _streamConnectionStatus.emit(StreamConnectionStatus.Disconnected)

            try {
                service.deactivateClient(
                    deactivateClientRequest {
                        clientId = requireClientId().toByteString()
                    },
                )
            } catch (e: StatusException) {
                YorkieLogger.e("Client.deactivate", e.stackTraceToString())
                return@async false
            }
            _status.emit(Status.Deactivated)
            true
        }
    }

    public fun requireClientId() = (status.value as Status.Activated).clientId

    private data class SyncResult(val document: Document, val result: Result<Unit>)

    /**
     * Represents the status of the client.
     */
    public sealed interface Status {
        /**
         * Means that the client is activated. If the client is activated,
         * all [Document]s of the client are ready to be used.
         */
        public class Activated internal constructor(
            public val clientId: ActorID,
            public val clientKey: String,
        ) : Status

        /**
         * Means that the client is not activated. It is the initial stastus of the client.
         * If the client is deactivated, all [Document]s of the client are also not used.
         */
        public object Deactivated : Status
    }

    /**
     * Represents whether the stream connection between the client
     * and the server is connected or not.
     */
    public enum class StreamConnectionStatus {
        Connected, Disconnected
    }

    /**
     * Represents the result of synchronizing the document with the server.
     */
    public sealed class DocumentSyncResult(public val document: Document) {
        /**
         * Type when [document] synced successfully.
         */
        public class Synced(document: Document) : DocumentSyncResult(document)

        /**
         * Type when [document] sync failed.
         */
        public class SyncFailed(
            document: Document,
            @Suppress("unused") public val cause: Throwable?,
        ) : DocumentSyncResult(document)
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
         * The presence information of this [Client].
         * If the [Client] attaches a [Document], the [PresenceInfo] is sent to the other peers
         * attached to the [Document].
         */
        public val presence: PresenceInfo? = null,
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

    /**
     * Represents the type of the events that the client can emit.
     * It can be delivered using [Client.collect].
     */
    public interface Event {
        /**
         * Means that the documents of the client has changed.
         */
        public class DocumentsChanged(public val documentKeys: List<Document.Key>) : Event

        /**
         * Means that the document has synced with the server.
         */
        public class DocumentSynced(public val result: DocumentSyncResult) : Event
    }
}
