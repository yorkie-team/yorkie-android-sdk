package dev.yorkie.core

import android.content.Context
import com.google.protobuf.kotlin.toByteStringUtf8
import dev.yorkie.api.v1.DeactivateClientRequest
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchDocumentsResponse
import dev.yorkie.api.v1.YorkieServiceGrpcKt
import dev.yorkie.api.v1.activateClientRequest
import dev.yorkie.api.v1.attachDocumentRequest
import dev.yorkie.api.v1.client
import dev.yorkie.api.v1.detachDocumentRequest
import dev.yorkie.api.v1.pushPullRequest
import dev.yorkie.api.v1.watchDocumentsRequest
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.YorkieLogger
import io.grpc.CallOptions
import io.grpc.android.AndroidChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Client that can communicate with the server.
 * It has [Document]s and sends changes of the documents in local
 * to the server to synchronize with other replicas in remote.
 *
 * TODO(skhugh): need to decide how to handle exceptions on service calls.
 */
public class Client private constructor(
    context: Context,
    private val rpcAddress: String,
    private val usePlainText: Boolean,
    private val options: Options,
    private val eventStream: MutableSharedFlow<Event<*>>,
) : Flow<Client.Event<*>> by eventStream {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    private var clientStatus: ClientStatus = ClientStatus.Deactivated
    private var syncLoop: Job? = null
    private var watchLoop: Job? = null

    public val isActive: Boolean
        get() = clientStatus is ClientStatus.Activated

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
    public suspend fun activate() {
        if (isActive) {
            return
        }
        val activateResponse = service.activateClient(
            activateClientRequest {
                clientKey = options.key
            },
        )
        clientStatus = ClientStatus.Activated(
            // TODO(skhugh): fix to use converters
            ActorID(activateResponse.clientId.toStringUtf8()),
            activateResponse.clientKey,
        )
        runSyncLoop()
        runWatchLoop()
        eventStream.emit(Event.StatusChanged(clientStatus))
    }

    private fun runSyncLoop() {
        syncLoop?.cancel()
        syncLoop = scope.launch {
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
                    YorkieLogger.e("runSyncLoop", "sync failed with $it")
                    delay(options.reconnectStreamDelay.inWholeMilliseconds)
                }
            }
        }
    }

    private suspend fun syncInternal(doc: Document) {
        val request = pushPullRequest {
            // TODO(skhugh): fix to use converters
            clientId = requireClientId().id.toByteStringUtf8()

            val requestPack = doc.createChangePack()
            // TODO(skhugh): fix to use converters
//            changePack = requestPack.toChangePack()
        }

        val response = service.pushPull(request)
        // TODO(skhugh): fix to use converters
        val responsePack: ChangePack? = null // response.changePack.toChangePack()
        doc.applyChangePack(responsePack!!)
        eventStream.emit(Event.DocumentSynced(DocumentSyncResult.Synced))
    }

    private fun runWatchLoop() {
        watchLoop?.cancel()
        watchLoop = scope.launch {
            if (!isActive) {
                return@launch
            }
            val realTimeSyncDocKeys = attachments.filterValues { attachment ->
                attachment.isRealTimeSync
            }.map { (_, attachment) ->
                attachment.doc.key
            }.takeIf { it.isNotEmpty() } ?: return@launch

            val request = watchDocumentsRequest {
                // TODO(skhugh): fix to use converter
                client = client {
                    id = requireClientId().id.toByteStringUtf8()
                }
                documentKeys.addAll(realTimeSyncDocKeys)
            }

            service.watchDocuments(request)
                .retry {
                    eventStream.emit(
                        Event.StreamConnectionChanged(StreamConnectionStatus.Disconnected),
                    )
                    delay(options.reconnectStreamDelay.inWholeMilliseconds)
                    true
                }
                .collect {
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
        // TODO(skhugh): fix to use converter
        val publisher = watchEvent.publisher.id.toStringUtf8()
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
    public suspend fun attach(
        doc: Document,
        isManualSync: Boolean = false,
    ) {
        require(isActive) {
            "client is not active"
        }
        doc.setActor(requireClientId())

        val request = attachDocumentRequest {
            clientId = requireClientId().id.toByteStringUtf8()
            // TODO(skhugh): fix to use converter
            // changePack = doc.createChangePack()
        }
        val response = service.attachDocument(request)
        // val pack = response.changePack.toChangePack()
        // doc.applyChangePack(pack)
        attachments[doc.key] = Attachment(doc, !isManualSync)
        runWatchLoop()
    }

    /**
     * Detaches the given [doc] from this [Client]. It tells the
     * server that this client will no longer synchronize the given [Document].
     *
     * To collect garbage things like CRDT tombstones left on the [Document], all
     * the changes should be applied to other replicas before GC time. For this,
     * if the [doc] is no longer used by this [Client], it should be detached.
     */
    public suspend fun detach(doc: Document) {
        require(isActive) {
            "client is not active"
        }
        val request = detachDocumentRequest {
            clientId = requireClientId().id.toByteStringUtf8()
            //
        }
        val response = service.detachDocument(request)
        // val pack = response.changePack.toChangePack()
        // doc.applyChangePack(pack)
        attachments.remove(doc.key)
        runWatchLoop()
    }

    /**
     * deactivates this client.
     */
    public suspend fun deactivate() {
        if (!isActive) {
            return
        }
        val deactivateResponse = service.deactivateClient(
            DeactivateClientRequest.newBuilder()
                // TODO(skhugh): fix to use converters
                .setClientId(requireClientId().id.toByteStringUtf8())
                .build(),
        )
        clientStatus = ClientStatus.Deactivated
        eventStream.emit(Event.StatusChanged(clientStatus))
    }

    private fun requireClientId() = (clientStatus as ClientStatus.Activated).clientId

    public sealed class ClientStatus {
        public class Activated internal constructor(
            internal val clientId: ActorID,
            internal val clientKey: String,
        ) : ClientStatus()

        public object Deactivated : ClientStatus()
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

        public class StatusChanged(value: ClientStatus) : Event<ClientStatus>(value)

        public class DocumentChanged(value: List<String>) : Event<List<String>>(value)

        // TODO(skhugh): implement after presence
        public class PeersChanged(value: Any) : Event<Any>(value)

        public class StreamConnectionChanged(
            value: StreamConnectionStatus,
        ) : Event<StreamConnectionStatus>(value)

        public class DocumentSynced(value: DocumentSyncResult) : Event<DocumentSyncResult>(value)
    }
}
