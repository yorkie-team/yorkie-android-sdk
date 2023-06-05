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
import dev.yorkie.api.v1.WatchDocumentResponse
import dev.yorkie.api.v1.YorkieServiceGrpcKt.YorkieServiceCoroutineStub
import dev.yorkie.api.v1.activateClientRequest
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.pushPullChangesRequest
import dev.yorkie.api.v1.removeDocumentRequest
import dev.yorkie.api.v1.updatePresenceRequest
import dev.yorkie.api.v1.watchDocumentRequest
import dev.yorkie.core.Attachment.Companion.UninitializedPresences
import dev.yorkie.core.Client.DocumentSyncResult.SyncFailed
import dev.yorkie.core.Client.DocumentSyncResult.Synced
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.Client.Event.PeersChanged
import dev.yorkie.core.Client.PeersChangedResult.Initialized
import dev.yorkie.core.Client.PeersChangedResult.PresenceChanged
import dev.yorkie.core.Client.PeersChangedResult.Unwatched
import dev.yorkie.core.Client.PeersChangedResult.Watched
import dev.yorkie.core.Peers.Companion.asPeers
import dev.yorkie.document.Document
import dev.yorkie.document.Document.DocumentStatus
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.YorkieLogger
import dev.yorkie.util.createSingleThreadDispatcher
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.StatusException
import io.grpc.android.AndroidChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Client that can communicate with the server.
 * It has [Document]s and sends changes of the documents in local
 * to the server to synchronize with other replicas in remote.
 */
public class Client @VisibleForTesting internal constructor(
    private val channel: Channel,
    private val options: Options = Options(),
) {
    private val scope = CoroutineScope(
        SupervisorJob() +
            createSingleThreadDispatcher("Client(${options.key})"),
    )
    private val activationJob = SupervisorJob()

    private val eventStream = MutableSharedFlow<Event>()
    val events = eventStream.asSharedFlow()

    private val attachments = MutableStateFlow<Map<Document.Key, Attachment>>(emptyMap())

    private val _status = MutableStateFlow<Status>(Status.Deactivated)
    public val status = _status.asStateFlow()

    public val isActive: Boolean
        get() = status.value is Status.Activated

    private val _streamConnectionStatus = MutableStateFlow(StreamConnectionStatus.Disconnected)
    public val streamConnectionStatus = _streamConnectionStatus.asStateFlow()

    private val _peerStatus = MutableStateFlow(emptyMap<Document.Key, Peers>())
    public val peerStatus = _peerStatus.asStateFlow()

    public var presenceInfo = options.presence ?: PresenceInfo(0, emptyMap())
        private set

    private val service by lazy {
        YorkieServiceCoroutineStub(channel, CallOptions.DEFAULT).withInterceptors(
            *listOfNotNull(
                UserAgentInterceptor,
                options.authInterceptor(),
            ).toTypedArray(),
        )
    }

    private val projectBasedRequestHeader = Metadata().apply {
        put("x-shard-key".asMetadataKey(), options.apiKey.orEmpty())
    }

    private fun documentBasedRequestHeader(documentKey: Document.Key) = Metadata().apply {
        put("x-shard-key".asMetadataKey(), "${options.apiKey.orEmpty()}/${documentKey.value}")
    }

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
                    projectBasedRequestHeader,
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
        scope.launch(activationJob) {
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

    private fun filterRealTimeSyncNeeded() = attachments.value.filterValues { attachment ->
        attachment.isRealTimeSync &&
            (attachment.document.hasLocalChanges || attachment.remoteChangeEventReceived)
    }.map { (key, attachment) ->
        attachments.value += key to attachment.copy(remoteChangeEventReceived = false)
        attachment
    }

    /**
     * Pushes local changes of the attached documents to the server and
     * receives changes of the remote replica from the server then apply them to local documents.
     */
    public fun syncAsync(
        document: Document? = null,
        syncMode: SyncMode = SyncMode.PushPull,
    ): Deferred<Boolean> {
        return scope.async {
            var isAllSuccess = true
            val attachments = document?.let {
                listOf(
                    attachments.value[it.key]?.copy(syncMode = syncMode)
                        ?: throw IllegalArgumentException("document is not attached"),
                )
            } ?: attachments.value.values
            attachments.asSyncFlow().collect { (document, result) ->
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

    private suspend fun Collection<Attachment>.asSyncFlow(): Flow<SyncResult> {
        return asFlow()
            .map { attachment ->
                val (document, documentID, _, syncMode) = attachment
                SyncResult(
                    document,
                    runCatching {
                        val request = pushPullChangesRequest {
                            clientId = requireClientId().toByteString()
                            changePack = document.createChangePack().toPBChangePack()
                            documentId = documentID
                            pushOnly = syncMode == SyncMode.PushOnly
                        }
                        val response = service.pushPullChanges(
                            request,
                            documentBasedRequestHeader(document.key),
                        )
                        val responsePack = response.changePack.toChangePack()
                        // NOTE(7hong13, chacha912, hackerwins): If syncLoop already executed with
                        // PushPull, ignore the response when the syncMode is PushOnly.
                        if (responsePack.hasChanges && syncMode == SyncMode.PushOnly) {
                            return@runCatching
                        }

                        document.applyChangePack(responsePack)
                        if (document.status == DocumentStatus.Removed) {
                            attachments.value -= document.key
                        }
                    },
                )
            }
    }

    private fun runWatchLoop() {
        scope.launch(activationJob) {
            attachments.map {
                it.filterValues(Attachment::isRealTimeSync)
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

    private suspend fun createWatchJob(attachment: Attachment): Job {
        return scope.launch(activationJob) {
            service.watchDocument(
                watchDocumentRequest {
                    client = toPBClient()
                    documentId = attachment.documentID
                },
                documentBasedRequestHeader(attachment.document.key),
            ).retry {
                _streamConnectionStatus.emit(StreamConnectionStatus.Disconnected)
                delay(options.reconnectStreamDelay.inWholeMilliseconds)
                true
            }.collect {
                _streamConnectionStatus.emit(StreamConnectionStatus.Connected)
                handleWatchDocumentsResponse(attachment.document.key, it)
            }
        }
    }

    private suspend fun handleWatchDocumentsResponse(
        documentKey: Document.Key,
        response: WatchDocumentResponse,
    ) {
        if (response.hasInitialization()) {
            val pbPeers = response.initialization.peersList
            val attachment = attachments.value[documentKey] ?: return
            if (attachment.peerPresences == UninitializedPresences) {
                attachments.value += documentKey to attachment.copy(peerPresences = Peers())
            }
            val peers = pbPeers.associate { peer ->
                peer.id.toActorID() to peer.presence.toPresence()
            }.asPeers()
            val newAttachment = attachments.value
                .getValue(documentKey)
                .copy(peerPresences = peers)
            attachments.value += documentKey to newAttachment

            val changedPeers = mapOf(
                documentKey to attachments.value[documentKey]?.peerPresences.orEmpty().asPeers(),
            )
            eventStream.emit(PeersChanged(Initialized(changedPeers)))
            emitPeerStatus()
            return
        }
        val watchEvent = response.event
        val eventType = checkNotNull(watchEvent.type)
        // only single key will be received since 0.3.1 server.
        val attachment = attachments.value[documentKey] ?: return
        val publisher = watchEvent.publisher.id.toActorID()
        val presence = watchEvent.publisher.presence.toPresence()
        val presences = attachment.peerPresences
        when (eventType) {
            DocEventType.DOC_EVENT_TYPE_DOCUMENTS_WATCHED -> {
                val newPeers = presences + (publisher to presence)
                attachments.value += documentKey to attachment.copy(peerPresences = newPeers)
                eventStream.emit(
                    PeersChanged(
                        Watched(documentKey to newPeers.filterKeys { it == publisher }.asPeers()),
                    ),
                )
                emitPeerStatus()
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENTS_UNWATCHED -> {
                val removedPresence = presences[publisher]
                removedPresence?.let {
                    val newPeers = presences - publisher
                    attachments.value += documentKey to attachment.copy(peerPresences = newPeers)
                    eventStream.emit(
                        PeersChanged(
                            Unwatched(documentKey to (publisher to it).asPeers()),
                        ),
                    )
                }
                emitPeerStatus()
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENTS_CHANGED -> {
                attachments.value += documentKey to attachment.copy(
                    remoteChangeEventReceived = true,
                )
                eventStream.emit(Event.DocumentsChanged(listOf(documentKey)))
            }

            DocEventType.DOC_EVENT_TYPE_PRESENCE_CHANGED -> {
                if ((presences[publisher]?.clock ?: -1) < presence.clock) {
                    val newPeers = presences + (publisher to presence)
                    attachments.value += documentKey to attachment.copy(peerPresences = newPeers)
                }
                eventStream.emit(
                    PeersChanged(
                        PresenceChanged(documentKey to (publisher to presence).asPeers()),
                    ),
                )
                emitPeerStatus()
            }

            DocEventType.UNRECOGNIZED -> {
                // nothing to do
            }
        }
    }

    private suspend fun emitPeerStatus() {
        _peerStatus.emit(
            attachments.value.toList().associate { (documentKey, attachment) ->
                documentKey to attachment.peerPresences.asPeers()
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

            fun realTimeAttachments() = attachments.value.filter { it.value.isRealTimeSync }

            realTimeAttachments().forEach {
                waitForInitialization(it.key)
            }

            presenceInfo = presenceInfo.copy(
                clock = presenceInfo.clock + 1,
                data = presenceInfo.data + (key to value),
            )

            realTimeAttachments().takeUnless { it.isEmpty() }
                ?.forEach { (key, attachment) ->
                    try {
                        service.updatePresence(
                            updatePresenceRequest {
                                client = toPBClient()
                                documentId = attachment.documentID
                            },
                            documentBasedRequestHeader(key),
                        )
                    } catch (e: StatusException) {
                        YorkieLogger.e("Client.updatePresence", e.stackTraceToString())
                        return@async false
                    }
                    val newPeers = attachment.peerPresences + (requireClientId() to presenceInfo)
                    attachments.value += key to attachment.copy(peerPresences = newPeers)
                } ?: return@async true
            emitPeerStatus()
            true
        }
    }

    /**
     * Attaches the given [Document] to this [Client].
     * It tells the server that this [Client] will synchronize the given [document].
     */
    public fun attachAsync(
        document: Document,
        isRealTimeSync: Boolean = true,
    ): Deferred<Boolean> {
        return scope.async {
            check(isActive) {
                "client is not active"
            }
            require(document.status == DocumentStatus.Detached) {
                "document is not detached"
            }
            document.setActor(requireClientId())

            val request = attachDocumentRequest {
                clientId = requireClientId().toByteString()
                changePack = document.createChangePack().toPBChangePack()
            }
            val response = try {
                service.attachDocument(
                    request,
                    documentBasedRequestHeader(document.key),
                )
            } catch (e: StatusException) {
                YorkieLogger.e("Client.attach", e.stackTraceToString())
                return@async false
            }
            val pack = response.changePack.toChangePack()
            document.applyChangePack(pack)

            if (document.status == DocumentStatus.Removed) {
                return@async true
            }

            document.status = DocumentStatus.Attached
            attachments.value += document.key to Attachment(
                document,
                response.documentId,
                isRealTimeSync,
            )
            waitForInitialization(document.key)
            true
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
    public fun detachAsync(document: Document): Deferred<Boolean> {
        return scope.async {
            check(isActive) {
                "client is not active"
            }
            val attachment = attachments.value[document.key]
                ?: throw IllegalArgumentException("document is not attached")
            val request = detachDocumentRequest {
                clientId = requireClientId().toByteString()
                changePack = document.createChangePack().toPBChangePack()
                documentId = attachment.documentID
            }
            val response = try {
                service.detachDocument(
                    request,
                    documentBasedRequestHeader(document.key),
                )
            } catch (e: StatusException) {
                YorkieLogger.e("Client.detach", e.stackTraceToString())
                return@async false
            }
            val pack = response.changePack.toChangePack()
            document.applyChangePack(pack)
            if (document.status != DocumentStatus.Removed) {
                document.status = DocumentStatus.Detached
                attachments.value -= document.key
            }
            true
        }
    }

    /**
     * Deactivates this [Client].
     */
    public fun deactivateAsync(): Deferred<Boolean> {
        return scope.async {
            if (!isActive) {
                return@async true
            }
            activationJob.cancelChildren()
            _streamConnectionStatus.emit(StreamConnectionStatus.Disconnected)

            try {
                service.deactivateClient(
                    deactivateClientRequest {
                        clientId = requireClientId().toByteString()
                    },
                    projectBasedRequestHeader,
                )
            } catch (e: StatusException) {
                YorkieLogger.e("Client.deactivate", e.stackTraceToString())
                return@async false
            }
            _status.emit(Status.Deactivated)
            true
        }
    }

    /**
     * Removes the given [document].
     */
    public fun removeAsync(document: Document): Deferred<Boolean> {
        return scope.async {
            check(isActive) {
                "client is not active"
            }
            val attachment = attachments.value[document.key]
                ?: throw IllegalArgumentException("document is not attached")

            val request = removeDocumentRequest {
                clientId = requireClientId().toByteString()
                changePack = document.createChangePack(forceRemove = true).toPBChangePack()
                documentId = attachment.documentID
            }
            val response = try {
                service.removeDocument(
                    request,
                    documentBasedRequestHeader(document.key),
                )
            } catch (e: StatusException) {
                YorkieLogger.e("Client.remove", e.stackTraceToString())
                return@async false
            }
            val pack = response.changePack.toChangePack()
            document.applyChangePack(pack)
            attachments.value -= document.key
            true
        }
    }

    public fun requireClientId() = (status.value as Status.Activated).clientId

    private suspend fun waitForInitialization(documentKey: Document.Key) {
        attachments.first { attachments ->
            with(attachments[documentKey]) {
                this == null || !isRealTimeSync || peerPresences != UninitializedPresences
            }
        }
    }

    /**
     * Pauses the realtime synchronization of the given [document].
     */
    public fun pause(document: Document) {
        changeRealTimeSync(document, false)
    }

    /**
     * Resumes the realtime synchronization of the given [document].
     */
    public fun resume(document: Document) {
        changeRealTimeSync(document, true)
    }

    private fun changeRealTimeSync(document: Document, isRealTimeSync: Boolean) {
        check(isActive) {
            "client is not active"
        }
        val attachment = attachments.value[document.key]
            ?: throw IllegalArgumentException("document is not attached")
        attachments.value += document.key to attachment.copy(isRealTimeSync = isRealTimeSync)
    }

    /**
     * Pauses the synchronization of remote changes,
     * allowing only local changes to be applied.
     */
    public fun pauseRemoteChange(document: Document) {
        changeSyncMode(document, SyncMode.PushOnly)
    }

    /**
     * Resumes the synchronization of remote changes,
     * allowing both local and remote changes to be applied.
     */
    public fun resumeRemoteChanges(document: Document) {
        changeSyncMode(document, SyncMode.PushPull)
    }

    /**
     * Changes the sync mode of the [document].
     */
    private fun changeSyncMode(document: Document, syncMode: SyncMode) {
        check(isActive) {
            "client is not active"
        }
        val attachment = attachments.value[document.key]
            ?: throw IllegalArgumentException("document is not attached")
        attachments.value += document.key to if (syncMode == SyncMode.PushPull) {
            attachment.copy(syncMode = syncMode, remoteChangeEventReceived = true)
        } else {
            attachment.copy(syncMode = syncMode)
        }
    }

    private data class SyncResult(val document: Document, val result: Result<Unit>)

    private class WatchJobHolder(val documentID: String, val job: Job)

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
     * [SyncMode] is the mode of synchronization. It is used to determine
     * whether to push and pull changes in PushPullChanges API.
     */
    public enum class SyncMode {
        PushPull, PushOnly
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

    public sealed class PeersChangedResult(
        public val changedPeers: Map<Document.Key, Peers>,
    ) {

        public class Initialized(
            changedPeers: Map<Document.Key, Peers>,
        ) : PeersChangedResult(changedPeers)

        public class PresenceChanged(
            changedPeers: Map<Document.Key, Peers>,
        ) : PeersChangedResult(changedPeers) {

            public constructor(changedPeer: Pair<Document.Key, Peers>) : this(mapOf(changedPeer))
        }

        public class Watched(
            changedPeers: Map<Document.Key, Peers>,
        ) : PeersChangedResult(changedPeers) {

            public constructor(changedPeer: Pair<Document.Key, Peers>) : this(mapOf(changedPeer))
        }

        public class Unwatched(
            changedPeers: Map<Document.Key, Peers>,
        ) : PeersChangedResult(changedPeers) {

            public constructor(changedPeer: Pair<Document.Key, Peers>) : this(mapOf(changedPeer))
        }
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
     * It can be delivered by [Client.events].
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

        /**
         * Means that there is a change in peers.
         */
        public class PeersChanged(public val result: PeersChangedResult) : Event
    }
}
