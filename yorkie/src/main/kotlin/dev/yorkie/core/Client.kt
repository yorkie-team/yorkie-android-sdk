package dev.yorkie.core

import android.content.Context
import com.google.common.annotations.VisibleForTesting
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchDocumentResponse
import dev.yorkie.api.v1.YorkieServiceGrpcKt.YorkieServiceCoroutineStub
import dev.yorkie.api.v1.activateClientRequest
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.pushPullChangesRequest
import dev.yorkie.api.v1.removeDocumentRequest
import dev.yorkie.api.v1.watchDocumentRequest
import dev.yorkie.core.Client.DocumentSyncResult.SyncFailed
import dev.yorkie.core.Client.DocumentSyncResult.Synced
import dev.yorkie.core.Client.Event.DocumentSynced
import dev.yorkie.core.Presences.Companion.UninitializedPresences
import dev.yorkie.core.Presences.Companion.asPresences
import dev.yorkie.document.Document
import dev.yorkie.document.Document.DocumentStatus
import dev.yorkie.document.Document.Event.PresenceChange
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.YorkieLogger
import dev.yorkie.util.createSingleThreadDispatcher
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.StatusException
import io.grpc.android.AndroidChannelBuilder
import java.io.Closeable
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
    private val channel: Channel,
    private val options: Options = Options(),
    private val dispatcher: CoroutineDispatcher,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
        dispatcher: CoroutineDispatcher = createSingleThreadDispatcher("Client(${options.key})"),
        buildChannel: (AndroidChannelBuilder) -> AndroidChannelBuilder = { it },
    ) : this(
        channel = AndroidChannelBuilder.forAddress(rpcHost, rpcPort)
            .let(buildChannel)
            .context(context.applicationContext)
            .build(),
        options = options,
        dispatcher = dispatcher,
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
            _status.emit(Status.Activated(ActorID(activateResponse.clientId)))
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
                            clientId = requireClientId().value
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
                    clientId = requireClientId().value
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
            val document = attachments.value[documentKey]?.document ?: return
            val clientIDs = response.initialization.clientIdsList.map { ActorID(it) }
            document.onlineClients.value += clientIDs

            val presences = document.presences.first { it.keys.containsAll(clientIDs) }
            document.publish(PresenceChange.MyPresence.Initialized(presences.asPresences()))
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
                document.onlineClients.value += publisher
                if (publisher in document.allPresences.value) {
                    val presence = document.presences.first { publisher in it }[publisher] ?: return
                    document.publish(
                        PresenceChange.Others.Watched(PresenceInfo(publisher, presence)),
                    )
                }
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_UNWATCHED -> {
                // NOTE(chacha912): There is no presence,
                // when PresenceChange(clear) is applied before unwatching. In that case,
                // the 'unwatched' event is triggered while handling the PresenceChange.
                val presence = document.presences.value[publisher] ?: return
                document.onlineClients.value -= publisher
                document.publish(PresenceChange.Others.Unwatched(PresenceInfo(publisher, presence)))
            }

            DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED -> {
                attachments.value += documentKey to attachment.copy(
                    remoteChangeEventReceived = true,
                )
                eventStream.emit(Event.DocumentChanged(listOf(documentKey)))
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
            document.updateAsync { _, presence ->
                presence.put(initialPresence)
            }.await()

            val request = attachDocumentRequest {
                clientId = requireClientId().value
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

            document.updateAsync { _, presence ->
                presence.clear()
            }.await()

            val request = detachDocumentRequest {
                clientId = requireClientId().value
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
                        clientId = requireClientId().value
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
                clientId = requireClientId().value
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

    private suspend fun waitForInitialization(documentKey: Document.Key) {
        val attachment = attachments.value[documentKey] ?: return
        if (attachment.isRealTimeSync) {
            attachment.document.presences.first { it != UninitializedPresences }
        }
    }

    public fun requireClientId() = (status.value as Status.Activated).clientId

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
        attachments.value += document.key to attachment.copy(
            isRealTimeSync = isRealTimeSync,
            remoteChangeEventReceived = isRealTimeSync || attachment.remoteChangeEventReceived,
        )
    }

    /**
     * Pauses the synchronization of remote changes,
     * allowing only local changes to be applied.
     */
    public fun pauseRemoteChanges(document: Document) {
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

    override fun close() {
        scope.cancel()
        (dispatcher as? Closeable)?.close()
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
        public class Activated internal constructor(public val clientId: ActorID) : Status

        /**
         * Means that the client is not activated. It is the initial stastus of the client.
         * If the client is deactivated, all [Document]s of the client are also not used.
         */
        public data object Deactivated : Status
    }

    /**
     * Represents whether the stream connection between the client
     * and the server is connected or not.
     */
    public enum class StreamConnectionStatus {
        Connected,
        Disconnected,
    }

    /**
     * [SyncMode] is the mode of synchronization. It is used to determine
     * whether to push and pull changes in PushPullChanges API.
     */
    public enum class SyncMode {
        PushPull,
        PushOnly,
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
        public class DocumentChanged(public val documentKeys: List<Document.Key>) : Event

        /**
         * Means that the document has synced with the server.
         */
        public class DocumentSynced(public val result: DocumentSyncResult) : Event
    }
}
