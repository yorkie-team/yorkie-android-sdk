package dev.yorkie.core

import android.content.Context
import dev.yorkie.api.toActorID
import dev.yorkie.api.toByteString
import dev.yorkie.api.toChangePack
import dev.yorkie.api.toPBChangePack
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchDocumentsResponse
import dev.yorkie.api.v1.YorkieServiceGrpcKt
import dev.yorkie.api.v1.activateClientRequest
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.client
import dev.yorkie.api.v1.deactivateClientRequest
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.pushPullRequest
import dev.yorkie.api.v1.watchDocumentsRequest
import dev.yorkie.document.Document
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.YorkieLogger
import dev.yorkie.util.YorkieScope
import io.grpc.CallOptions
import io.grpc.StatusException
import io.grpc.android.AndroidChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
public class Client private constructor(
    context: Context,
    private val rpcAddress: String,
    private val usePlainText: Boolean,
    private val options: Options,
    private val eventStream: MutableSharedFlow<Event<*>>,
) : Flow<Client.Event<*>> by eventStream {
    private val applicationContext = context.applicationContext

    private val service by lazy {
        YorkieServiceGrpcKt.YorkieServiceCoroutineStub(
            AndroidChannelBuilder.forTarget(rpcAddress)
                .run { if (usePlainText) usePlaintext() else this }
                .context(applicationContext)
                .build(),
            callOptions = CallOptions.DEFAULT,
        ).run {
            val authInterceptor = options.authInterceptor()
            if (authInterceptor == null) this else withInterceptors(authInterceptor)
        }
    }

    private val attachments = mutableMapOf<String, Attachment>()

    private val _status = MutableStateFlow<Status>(Status.Deactivated)
    public val status = _status.asStateFlow()

    public val isActive: Boolean
        get() = status.value is Status.Activated

    private val _streamConnectionStatus = MutableStateFlow(StreamConnectionStatus.Disconnected)
    public val streamConnectionStatus = _streamConnectionStatus.asStateFlow()

    private var syncLoop: Job? = null
    private var watchLoop: Job? = null

    public constructor(
        context: Context,
        rpcAddress: String,
        usePlainText: Boolean = false,
        options: Options = Options.Default,
    ) : this(context, rpcAddress, usePlainText, options, MutableSharedFlow())

    /**
     * activates this client. That is, it register itself to the server
     * and receives a unique ID from the server. The given ID is used to
     * distinguish different clients.
     */
    public fun activateAsync(): Deferred<Boolean> {
        return YorkieScope.async {
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
        syncLoop = YorkieScope.launch {
            while (isActive) {
                val pendingSyncs = attachments.filterValues { attachment ->
                    attachment.isRealTimeSync &&
                        (attachment.doc.hasLocalChanges || attachment.remoteChangeEventReceived)
                }.map { (_, attachment) ->
                    attachment.remoteChangeEventReceived = false
                    async {
                        syncInternal(attachment.doc)
                    }
                }
                runCatching {
                    pendingSyncs.awaitAll()
                }.onSuccess {
                    eventStream.emit(Event.DocumentSynced(DocumentSyncResult.SyncFailed))
                    delay(options.syncLoopDuration.inWholeMilliseconds)
                }.onFailure {
                    if (it is CancellationException) {
                        throw it
                    }
                    YorkieLogger.e("runSyncLoop", "sync failed with $it")
                    delay(options.reconnectStreamDelay.inWholeMilliseconds)
                }
            }
        }
    }

    private suspend fun syncInternal(doc: Document) {
        val request = pushPullRequest {
            clientId = requireClientId().toByteString()
            changePack = doc.createChangePack().toPBChangePack()
        }
        val response = service.pushPull(request)
        val responsePack = response.changePack.toChangePack()
        doc.applyChangePack(responsePack)
        eventStream.emit(Event.DocumentSynced(DocumentSyncResult.Synced))
    }

    private fun runWatchLoop() {
        watchLoop?.cancel()
        watchLoop = YorkieScope.launch {
            if (!isActive) {
                return@launch
            }
            val realTimeSyncDocKeys = attachments.filterValues { attachment ->
                attachment.isRealTimeSync
            }.map { (_, attachment) ->
                attachment.doc.key
            }.takeIf { it.isNotEmpty() } ?: return@launch

            val request = watchDocumentsRequest {
                client = client {
                    id = requireClientId().toByteString()
                }
                documentKeys.addAll(realTimeSyncDocKeys)
            }

            service.watchDocuments(request)
                .retry {
                    _streamConnectionStatus.emit(StreamConnectionStatus.Disconnected)
                    delay(options.reconnectStreamDelay.inWholeMilliseconds)
                    true
                }
                .collect {
                    _streamConnectionStatus.emit(StreamConnectionStatus.Connected)
                    handleWatchDocumentsResponse(realTimeSyncDocKeys, it)
                }
        }
    }

    private suspend fun handleWatchDocumentsResponse(
        keys: List<String>,
        response: WatchDocumentsResponse,
    ) {
        if (response.hasInitialization()) {
            // TODO(skhugh): handle peers
        }
        val watchEvent = response.event
        val responseKeys = watchEvent.documentKeysList
        val publisher = watchEvent.publisher.id.toActorID()
        // TODO(skhugh): handle peers and presence

        if (watchEvent.type == DocEventType.DOC_EVENT_TYPE_DOCUMENTS_CHANGED) {
            eventStream.emit(Event.DocumentChanged(responseKeys))
        }
        // TODO(skhugh): handle peers changed
    }

    /**
     * Attaches the given document to this client.
     * It tells the server that this [Client] will synchronize the given [Document].
     */
    public fun attachAsync(
        doc: Document,
        isManualSync: Boolean = false,
    ): Deferred<Boolean> {
        return YorkieScope.async {
            require(isActive) {
                "client is not active"
            }
            doc.setActor(requireClientId())

            val request = attachDocumentRequest {
                clientId = requireClientId().toByteString()
                changePack = doc.createChangePack().toPBChangePack()
            }
            val response = try {
                service.attachDocument(request)
            } catch (e: StatusException) {
                YorkieLogger.e("Client.attach", e.stackTraceToString())
                return@async false
            }
            val pack = response.changePack.toChangePack()
            doc.applyChangePack(pack)
            attachments[doc.key] = Attachment(doc, !isManualSync)
            runWatchLoop()
            true
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
        return YorkieScope.async {
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
     * deactivates this client.
     */
    public fun deactivateAsync(): Deferred<Boolean> {
        return YorkieScope.async {
            if (!isActive) {
                return@async false
            }
            val deactivateResponse = try {
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

    private fun requireClientId() = (status.value as Status.Activated).clientId

    public sealed interface Status {
        public class Activated internal constructor(
            internal val clientId: ActorID,
            internal val clientKey: String,
        ) : Status

        public object Deactivated : Status
    }

    public enum class StreamConnectionStatus {
        Connected, Disconnected
    }

    public enum class DocumentSyncResult {
        Synced, SyncFailed
    }

    /**
     * user-settable options used when defining [Client].
     */
    public data class Options(
        /**
         * Client key used to identify the client.
         * If not set, a random key is generated.
         */
        val key: String = UUID.randomUUID().toString(),
//        val presence: Any
        /**
         * API key of the project used to identify the project.
         */
        val apiKey: String? = null,
        /**
         * Authentication token of this client used to identify the user of the client.
         */
        val token: String? = null,
        /**
         * Duration of the sync loop.
         * After each sync loop, the client waits for the duration to next sync.
         * The default value is `50`(ms).
         */
        val syncLoopDuration: Duration = 50.milliseconds,
        /**
         * Delay of the reconnect stream.
         * If the stream is disconnected, the client waits for the delay to reconnect the stream.
         * The default value is `1000`(ms).
         */
        val reconnectStreamDelay: Duration = 1_000.milliseconds,
    ) {

        companion object {
            internal val Default = Options()
        }
    }

    public sealed class Event<T>(public val value: T) {

        public class DocumentChanged(value: List<String>) : Event<List<String>>(value)

        // TODO(skhugh): implement after presence
        public class PeersChanged(value: Any) : Event<Any>(value)

        public class DocumentSynced(value: DocumentSyncResult) : Event<DocumentSyncResult>(value)
    }
}
