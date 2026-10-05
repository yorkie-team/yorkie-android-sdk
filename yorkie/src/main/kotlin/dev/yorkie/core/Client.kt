package dev.yorkie.core

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.connectrpc.Code
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
import dev.yorkie.api.v1.AttachDocumentResponse
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
import dev.yorkie.document.RestoreResult
import dev.yorkie.document.change.Change
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.PresenceInfo
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.document.toDroppedChange
import dev.yorkie.presence.Channel
import dev.yorkie.presence.ChannelEvent
import dev.yorkie.presence.Presence
import dev.yorkie.util.Logger.Companion.log
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.OperationResult
import dev.yorkie.util.SUCCESS
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrAlreadyAttached
import dev.yorkie.util.YorkieException.Code.ErrClientNotActivated
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotAttached
import dev.yorkie.util.YorkieException.Code.ErrDocumentNotDetached
import dev.yorkie.util.YorkieException.Code.ErrEpochMismatch
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import dev.yorkie.util.YorkieException.Code.ErrInvalidServerSeq
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import dev.yorkie.api.v1.ChannelEvent.Type as PbChannelEventType

/**
 * Initializers for missing root keys when attaching a [Document].
 *
 * Each map key is authoritative: its initializer receives that key and is invoked only when the
 * server-provided root does not contain it. The absence guard checks only the mapped key itself —
 * an initializer is expected to write only that key; writes to other keys are not guarded against
 * and are not part of the documented contract. Initial values become ordinary Yorkie changes but
 * are history-exempt via `skipHistory`, so they never enter the undo/redo history. Concurrent
 * first attachments may both initialize a missing key and converge through normal CRDT conflict
 * resolution.
 *
 * The initializer is a `suspend JsonObject.(key: String) -> Unit` lambda, which is awkward to
 * construct from Java; Java consumers should prefer the no-`initialRoot` [attachDocument]
 * overload instead.
 */
public typealias InitialRoot = Map<String, suspend JsonObject.(key: String) -> Unit>

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

    // attachingDocs holds keys with an in-flight attach. attachments is only
    // populated after the attach round-trip resolves, so this set is needed
    // to reject a concurrent duplicate attach of the same key. Touched only
    // inside attachDocument's scope.async, which runs on the single client
    // dispatcher, so no additional synchronization is needed.
    private val attachingDocs = mutableSetOf<String>()

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

    // Per-store-key FIFO of pending persist writes (spec 025). Registered atomically via
    // [ConcurrentHashMap.compute] from either [Document.onLocalChange] (document dispatcher) or
    // the persist-after-sync call in syncInternal (client dispatcher); read from the caller's
    // thread by close()'s runBlocking drain, hence a concurrent map (round-6 QA LOW-1). Internal
    // (not private) so ClientPersistenceTest can assert the detach-time prune (spec 029 M2).
    @VisibleForTesting
    internal val persistQueues = ConcurrentHashMap<String, Job>()

    /**
     * Derives the [DocStore] key for [docKey]: `<apiKey>/<clientKey>/<docKey>`. Ported from JS
     * `client.ts` (`2291bf67`/#1338); `apiKey` may be null on Android (`options.apiKey ?: ""` —
     * determination, recorded in the build report).
     */
    private fun storeKey(docKey: String) = "${options.apiKey ?: ""}/${options.key}/$docKey"

    /**
     * Registers [attachment]'s next store write, chained after any already-enqueued write for the
     * same store key. Called synchronously off [Document.onLocalChange] (no suspension before this
     * runs — spec 029 B2) as well as from the post-sync site, so [persistQueues] is mutated
     * atomically via [ConcurrentHashMap.compute] rather than the read-then-write the old
     * `persistQueues[key] = job` risked under two concurrent callers.
     *
     * The snapshot ([Document.toBytes]) is taken INSIDE the chained job, after the previous write
     * for this key has completed, not eagerly before chaining: job N therefore reflects every local
     * change made up to the moment job N-1 finishes, so the order in which [enqueuePersist] is
     * called still equals the order [DocStore.save] is invoked in, and the LAST call to complete
     * stores the LAST state — same guarantee as the old eager-snapshot design, without the
     * suspension window in front of the registration. Errors are logged, never thrown (JS
     * `persistToStore`, `client.ts`).
     *
     * The job body runs on [Dispatchers.IO] under [NonCancellable] (spec 025 MEDIUM-1, amended
     * round 5 per a cross-judge HIGH finding): Kotlin's structured concurrency cancels a child
     * job immediately on `scope.cancel()`, which is not JS promise semantics — an in-flight
     * `store.save` for the last local edit must not be interrupted mid-write just because [close]
     * moved on. Running on [Dispatchers.IO] (rather than the client's own single-thread
     * [dispatcher]) matters specifically for [close]: that dispatcher is shut down right after the
     * bounded drain below gives up on a slow write, and a write still suspended at that point would
     * be permanently rejected on its next resumption if it depended on the now-closed dispatcher —
     * [NonCancellable] alone only suppresses cooperative cancellation checks, it does not protect a
     * suspended continuation from a dispatcher that refuses to run it at all. [drainPersist]/
     * [drainAllPersists] give a caller a bounded chance to OBSERVE completion before tearing down;
     * the write itself keeps running on [Dispatchers.IO] independently of that bound and of the
     * client dispatcher's lifecycle. A [Document.close] that races ahead of that observation forfeits
     * whatever snapshot a still-queued job would have taken: the document's own dispatcher is shut
     * down, so a job still waiting on [previous]'s join fails its own [Document.toBytes] call once it
     * finally runs — logged, not thrown, same as any other snapshot failure.
     */
    private fun enqueuePersist(attachment: Attachment<out Attachable>) {
        if (!attachment.persistsToStore) return
        val store = options.docStore ?: return
        val document = attachment.resource as? Document ?: return
        val key = storeKey(document.getKey())
        persistQueues.compute(key) { _, previous ->
            scope.launch(Dispatchers.IO) {
                withContext(NonCancellable) {
                    previous?.join()
                    val bytes = runCatching { document.toBytes() }
                        .getOrElse {
                            logError("PS", "persist snapshot $key failed", it)
                            return@withContext
                        }
                    runCatching { store.save(key, bytes) }
                        .onFailure { logError("PS", "persist $key failed", it) }
                }
            }
        }
    }

    /**
     * Waits for [key]'s persist chain to become quiescent, bounded to 5s (spec 025 MEDIUM-1).
     * Registration is synchronous with the local change that triggers it (spec 029 B2), so there is
     * no not-yet-registered write to poll for: the job currently at [key] (if any) is the whole
     * chain, and joining it is sufficient — a second poll can only find something new if a write was
     * enqueued concurrently while this suspended, which the `job === persistQueues[key]` recheck
     * below still covers. Called from [detachInternal] before it clears the local-change hook and
     * releases the lease, so a write enqueued for the last local edit is not silently dropped by the
     * caller moving on.
     */
    private suspend fun drainPersist(key: String) {
        withTimeoutOrNull(5_000) {
            while (true) {
                val job = persistQueues[key] ?: break
                job.join()
                if (persistQueues[key] === job) break
            }
        }
    }

    /**
     * [drainPersist] for every store key with a pending write. [close] runs this bounded (by its
     * own [withTimeoutOrNull] at the call site) before cancelling the client scope, so an abrupt
     * close still flushes the last edit instead of dropping it mid-write (spec 025 MEDIUM-1).
     */
    private suspend fun drainAllPersists() {
        while (true) {
            val current = persistQueues.toMap()
            if (current.isEmpty()) break
            current.values.forEach { it.join() }
            if (persistQueues.toMap() == current) break
        }
    }

    /**
     * Removes [docKey]'s envelope from the configured [DocStore], if any. Called only from the
     * three recovery paths (restore failure, tier-3 purge, epoch re-anchor) — never from teardown,
     * which is JS parity (`client.ts`, recorded determination). Failures are logged, never thrown.
     */
    private suspend fun removeFromStore(docKey: String) {
        val store = options.docStore ?: return
        runCatching { store.remove(storeKey(docKey)) }
            .onFailure { logDebug("PS", "store remove $docKey failed: ${it.message}") }
    }

    /**
     * Publishes a [Document.Event.LocalChangesDropped] for [changes] projected via
     * [Change.toDroppedChange], so an app can react to un-pushed local edits that were discarded
     * without reaching the server (JS `emitLocalChangesDropped`, `client.ts`).
     */
    private suspend fun emitLocalChangesDropped(
        document: Document,
        reason: Document.Event.Reason,
        changes: List<Change>,
    ) {
        document.publishEvent(
            Document.Event.LocalChangesDropped(reason, changes.map { it.toDroppedChange() }),
        )
    }

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
            // The stable actor is derived from the project and client key on the server;
            // a pre-0.7.20 server never sets actor_id, so the session client id is the
            // fallback (JS `client.ts`: `res.actorId || res.clientId`). A malformed
            // actor_id (e.g. a partial rollout / proxy bug) also falls back, logging a
            // warning rather than stamping local changes with an unusable actor.
            val sessionClientId = activateResponse.clientId
            val rawActor = activateResponse.actorId.ifEmpty { sessionClientId }
            val actor = if (isValidActorId(rawActor)) {
                rawActor
            } else {
                logDebug("AC", "invalid actor_id \"$rawActor\"; falling back to the session id")
                sessionClientId
            }
            _status.emit(Status.Activated(sessionClientId, actorId = actor))
            deactivating = false
            runSyncLoop()
            SUCCESS
        }
    }

    /**
     * Checks whether [value] is a syntactically valid 24-hex-character [ActorID] (the shape
     * `toActorID()`/`toByteString()` decode). Used only to guard a server-supplied `actor_id`
     * before stamping it into document changes; not a full round-trip decode.
     */
    private fun isValidActorId(value: String) = ActorIdRegex.matches(value)

    /**
     * Classifies [e] as the store-path attach recovery trigger (spec 025 HIGH-1): the server
     * compacted/purged the document since the persisted envelope was written, so a re-anchor
     * (not a propagated failure) is the correct response.
     *
     * Matches on the [YorkieException.Code.ErrEpochMismatch] / [ErrInvalidServerSeq]
     * `ErrorInfo` metadata `code` when present (the mock, and any server that attaches one).
     * The live yorkie 0.7.20 server does NOT attach an `ErrorInfo` detail for
     * `ErrInvalidServerSeq` on this path — verified empirically this round (a real
     * compacted-resume attach failure's [ConnectException.details] and
     * `unpackedDetails(ErrorInfo::class)` are both empty; the wire response is an 81-byte
     * bare `{"code":"invalid_argument","message":"..."}` JSON body with no room for one) — so
     * this also falls back to the connect status code plus the server's fixed message text for
     * that case.
     */
    private fun isCompactionReanchorError(e: ConnectException): Boolean {
        val code = errorCodeOf(e)
        if (code == ErrEpochMismatch.codeString || code == ErrInvalidServerSeq.codeString) {
            return true
        }
        return e.code == Code.INVALID_ARGUMENT &&
            e.message.orEmpty().contains(
                "checkpoint serverseq exceeds server state",
                ignoreCase = true,
            )
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
                        // Also reset for Realtime: gives the watch-silence pull
                        // fallback's own throttle a moving baseline (#351 phase
                        // 1) — otherwise lastHeartbeatTime stays 0 for realtime
                        // documents and the throttle is inert, i.e. a fallback
                        // pull storm every sync-loop tick once engaged. Harmless
                        // no-op for realtime documents with fallback disabled
                        // (their throttle clause never engages regardless).
                        if (attachment.syncMode == SyncMode.Polling ||
                            attachment.syncMode == SyncMode.Realtime
                        ) {
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
                        // An ack-only push advances the checkpoint and drops the
                        // pushed changes from localChanges without emitting any
                        // LocalChange/Snapshot event, so the event-driven persist
                        // subscription alone would leave a stale envelope in the
                        // store. Gated on persistsToStore like the other site.
                        if (attachment.persistsToStore) {
                            enqueuePersist(attachment)
                        }
                        attachment.resource.publish(
                            event = SyncStatusChanged.Synced,
                        )

                        // Reset the poll timer so a Polling document syncs once
                        // per interval, not on every sync-loop tick. #1243.
                        // Also reset for Realtime — see the matching comment above
                        // this method's first updateHeartbeatTime() call (#351
                        // phase 1 throttle baseline).
                        if (attachment.syncMode == SyncMode.Polling ||
                            attachment.syncMode == SyncMode.Realtime
                        ) {
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
                            // RefreshChannel carries no stable actor (channels are
                            // session-scoped, [C-1]); fall back to the session id,
                            // matching JS `client.ts` RefreshChannel lazy activation.
                            _status.emit(
                                Status.Activated(response.clientId, actorId = response.clientId),
                            )
                        }
                        if (isActive) {
                            // [C-1]: channels keep the session id as their actor
                            // (JS `refreshChannel` stamps `this.id`, never the
                            // stable actor) — do not change to requireActorId().
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
                                // Every frame of any kind proves the watch stream is alive —
                                // resets this attachment's silence clock, disengaging pull
                                // fallback if it had engaged (#351 phase 1).
                                attachment.markWatchResponseReceived()
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
                        // Declares the stable actor so the server keys watch peer
                        // ids and watched/unwatched events by the same actor
                        // stamped into presence changes ([C-1]: the document
                        // watch only — the channel watch below carries no
                        // stable actor, JS `client.ts` createChannelWatchStream).
                        actorId = requireActorId()
                    },
                )

                document.publish(StreamConnectionChanged.Connected)

                if (attachment.changeEventReceived != null) {
                    attachment.changeEventReceived = true
                }
                // Stream (re)connection is itself a liveness/revival signal — resets the
                // silence clock the same as any received frame would (#351 phase 1).
                attachment.markWatchResponseReceived()

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
                // Presences are keyed by the stable actor (JS applyWatchInit compares
                // against changeID.getActorID()), so the self-guard must use it too.
                val selfId = requireActorId()
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
            // Fire-and-forget: this runs on the watch reader coroutine for every
            // stream failure/close/timeout. An inline publishEvent here is an
            // unbuffered-flow emit that a stalled events collector can park
            // forever, wedging the reader so streamJob.join() never returns and
            // the watch loop never reconnects (#351 follow-up).
            document.publish(StreamConnectionChanged.Disconnected)
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
    ): Deferred<OperationResult> = attachDocument(
        document = document,
        initialPresence = initialPresence,
        syncMode = syncMode,
        schema = schema,
        disableGC = disableGC,
        disablePresence = disablePresence,
        initialRoot = emptyMap(),
    )

    /**
     * Attaches the given [Document] to this [Client] and initializes missing root keys.
     *
     * The authoritative server response is applied first. Each [initialRoot] initializer is then
     * invoked only if its map key is absent, and receives that key as its argument. All invoked
     * initializers form one atomic local change. This change runs as a history-exempt
     * `skipHistory` update, so it never enters the undo/redo history. Concurrent first
     * attachments are not globally serialized; their ordinary CRDT changes converge through
     * normal conflict resolution.
     *
     * The document is marked [ResourceStatus.Attached] and registered with this client BEFORE the
     * [initialRoot] initializers run, so a throwing initializer leaves the document attached and
     * detachable rather than rolled back; the returned deferred still resolves to failure, and
     * history is already cleared. A user edit made after the [ResourceStatus.Attached] event
     * while an initializer is still running keeps its undo entry, index-reconciled against the
     * initializer change. Document events emitted by the initializer change are always preceded
     * by the [ResourceStatus.Attached] [Document.Event.DocumentStatusChanged] event.
     *
     * @param initialPresence The initial presence of the client.
     * @param syncMode The synchronization mode of the document.
     * @param schema The schema used to validate the document.
     * @param disableGC declares that this attachment will not produce or consume
     * tombstones. The server skips minVV tracking and omits the response
     * VersionVector for this client. Use only with Counter or primitive
     * workloads where no client consumes tombstones; misuse on a document
     * that uses Tree, Text, or Array deletions leads to undefined GC behavior
     * on this client. Controls only the wire contract and is distinct from
     * any local-only [Document] GC pass.
     * @param disablePresence Whether this attachment opts out of presence.
     * @param initialRoot Initializers for root keys absent from the authoritative server response.
     */
    public fun attachDocument(
        document: Document,
        initialPresence: P = emptyMap(),
        syncMode: SyncMode = SyncMode.Realtime,
        schema: String? = null,
        disableGC: Boolean = false,
        disablePresence: Boolean = false,
        initialRoot: InitialRoot,
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

            // Reject a duplicate attach of the same key on this client. Without this
            // guard the request reaches the server, which reports the already-attached
            // key as a misleading ErrClientNotFound; the SDK then deactivates the whole
            // client. attachments covers the resolved case and attachingDocs covers a
            // concurrent in-flight attach.
            checkYorkieError(
                !attachments.containsKey(documentKey) && documentKey !in attachingDocs,
                YorkieException(ErrAlreadyAttached, "$documentKey is already attached"),
            )
            // Mark the attach in flight synchronously so a concurrent duplicate attach of
            // the same key is rejected by the guard above before it is enqueued. Cleared
            // in the finally.
            attachingDocs += documentKey

            try {
                document.mutex.withLock {
                    var sessionLockHandle: SessionLockHandle? = null
                    var persistsToStore = options.docStore != null
                    // I3: true only once an envelope was actually restored this attach (set in
                    // the attachOnce restore-success branch below) — narrower than
                    // `options.docStore != null`, which also covers a store configured but never
                    // successfully read this session (spec 029).
                    var restoredEnvelope = false
                    var registered = false
                    try {
                        // Actor-before-elements: setActor rewrites localChanges/changeID
                        // only, never root elements, so it must run before restoreFromBytes
                        // — whose actor guard compares against this value.
                        document.setActor(requireActorId())

                        // (1) Lease — store path only; contention fails fast (does not
                        // suspend waiting for the lock to free up).
                        if (options.docStore != null) {
                            val lockName = "yorkie-session:${storeKey(documentKey)}"
                            sessionLockHandle = options.sessionLock.acquire(lockName)
                                ?: throw YorkieException(
                                    ErrInvalidArgument,
                                    "document \"$documentKey\" is already open in another " +
                                        "session under offline persistence; only one active " +
                                        "session per document is allowed to avoid silent " +
                                        "edit loss",
                                )
                        }

                        // (2) attachOnce — restore + RPC + apply; retried once on an
                        // epoch re-anchor. A local suspend fun (not inline), so it
                        // cannot `return@async`; RPC failures propagate as thrown
                        // ConnectExceptions for the outer try/catch to classify.
                        suspend fun attachOnce(reanchor: Boolean): AttachDocumentResponse {
                            var restored = false
                            if (options.docStore != null && !reanchor) {
                                val bytes = try {
                                    options.docStore.load(storeKey(documentKey))
                                } catch (e: Throwable) {
                                    ensureActive()
                                    logDebug("AD", "store load failed; fresh attach: ${e.message}")
                                    // A store that could not be READ this session must not
                                    // be WRITTEN either (iOS review fix, adopted): the
                                    // unreadable envelope survives for a session that can.
                                    persistsToStore = false
                                    null
                                }
                                if (bytes != null) {
                                    try {
                                        // restoreFromBytes classifies by RestoreResult instead
                                        // of throwing on an actor mismatch (spec 029 I5/M1), so
                                        // this decodes bytes exactly once — the old design threw
                                        // either way and re-decoded the envelope a second time in
                                        // the catch below purely to tell a mismatch apart from a
                                        // corrupt envelope.
                                        when (val result = document.restoreFromBytes(bytes)) {
                                            is RestoreResult.Restored -> {
                                                // No re-stamp needed (spec 029 D1): the actor
                                                // guard is now strict (no initial-actor
                                                // exemption on either side, JS/iOS parity), so a
                                                // Restored result already carries the same
                                                // stable actor this client stamped before the
                                                // restore attempt (requireActorId(), above).
                                                restored = true
                                                restoredEnvelope = true
                                            }

                                            is RestoreResult.ActorMismatch -> {
                                                ensureActive()
                                                emitLocalChangesDropped(
                                                    document,
                                                    Document.Event.Reason.ActorMismatch,
                                                    result.pending,
                                                )
                                                removeFromStore(documentKey)
                                                // Fall through: restoreFromBytes is
                                                // all-or-nothing, so the document is
                                                // untouched; continue fresh.
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: YorkieException) {
                                        // Corrupt envelope only — fromBytes always throws
                                        // ErrInvalidArgument, never lets a protobuf-level or
                                        // other decode failure escape unwrapped.
                                        ensureActive()
                                        emitLocalChangesDropped(
                                            document,
                                            Document.Event.Reason.RestoreFailed,
                                            emptyList(),
                                        )
                                        removeFromStore(documentKey)
                                        // Fall through: fromBytes never built a usable
                                        // Document, so restoreFromBytes never touched this
                                        // document's fields; continue fresh.
                                    }
                                }
                            }

                            // (3) Presence seed AFTER restore, gated on !restored: a
                            // restored document already carries its own presence: seeding
                            // it again would append a spurious local change.
                            val resolvedDisablePresence =
                                disablePresence || document.isPresenceDisabled()
                            if (!resolvedDisablePresence && !restored) {
                                document.updateAsync { _, presence ->
                                    presence.put(initialPresence)
                                }.await()
                            }

                            // (4) Snapshot pre-attach state for the tier-3 guard below,
                            // before the RPC can mutate the document.
                            val persistedDocId = document.docId
                            val hadLocalState = restored && document.checkPoint.serverSeq > 0L
                            val persistedPending = if (restored) {
                                document.pendingChanges()
                            } else {
                                emptyList()
                            }

                            // (5) RPC — presents the restored checkpoint + epoch, if any.
                            val request = attachDocumentRequest {
                                clientId = requireClientId()
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
                            ).getOrThrow()

                            val maxSize = response.maxSizePerDocument
                            if (maxSize > 0) {
                                document.setMaxSizePerDocument(maxSize)
                            }
                            if (response.schemaRulesCount > 0) {
                                document.setSchemaRules(response.schemaRulesList.fromSchemaRules())
                            }
                            val pack = response.changePack.toChangePack()

                            // (6) Tier-3 silent-purge guard: a store-backed restore whose
                            // server response no longer matches the persisted state means
                            // the server-side document was purged or force-compacted out
                            // from under the stored envelope between sessions.
                            if (options.docStore != null && restored) {
                                val idChanged = persistedDocId != "" &&
                                    response.documentId != persistedDocId
                                val seqRegressed = hadLocalState &&
                                    pack.checkPoint.serverSeq == 0L
                                if (idChanged || seqRegressed) {
                                    logDebug(
                                        "AD",
                                        "server purged $documentKey; dropping persisted state",
                                    )
                                    emitLocalChangesDropped(
                                        document,
                                        Document.Event.Reason.DocumentPurged,
                                        persistedPending,
                                    )
                                    removeFromStore(documentKey)
                                    document.resetForReanchor()
                                    document.setActor(requireActorId())
                                    document.setDisableGC(disableGC)
                                    document.setDisablePresence(response.disablePresence)
                                    document.applyChangePack(pack)
                                    document.setDocId(response.documentId)
                                    return response
                                }
                            }

                            // (7) Normal apply.
                            document.setDisableGC(disableGC)
                            document.setDisablePresence(response.disablePresence)
                            document.applyChangePack(pack)
                            document.setDocId(response.documentId)
                            return response
                        }

                        val response = try {
                            attachOnce(reanchor = false)
                        } catch (e: ConnectException) {
                            ensureActive()
                            if (restoredEnvelope && isCompactionReanchorError(e)) {
                                // Gated on an envelope actually having been restored THIS
                                // attach (spec 029 I3), not merely on a store being configured:
                                // a store-load failure (persistsToStore = false, nothing
                                // restored) must not re-anchor — there is no persisted state to
                                // reconcile, so removeFromStore/resetForReanchor would discard
                                // nothing-but-still-contact the server for a pointless retry.
                                // restoredEnvelope implies options.docStore != null, so this is
                                // strictly narrower than the old gate, never broader.
                                //
                                // A stale-epoch (ErrEpochMismatch) OR a stale checkpoint
                                // serverSeq (ErrInvalidServerSeq) attach both mean the server
                                // compacted/purged the document since this envelope was written.
                                //
                                // Determination (round-2 QA HIGH-1, verified against yorkie
                                // 0.7.20 server/packs/pushpull.go:285-320): the server checks
                                // the seeded epoch FIRST and only returns ErrEpochMismatch
                                // once epochs already match; an envelope written before the
                                // client learned the epoch (or never compacted before this
                                // session) instead hits ErrInvalidServerSeq ("checkpoint
                                // serverSeq exceeds server state"). The JS SDK recovers on
                                // ErrEpochMismatch only and is left permanently stuck on this
                                // path; Android re-anchors on both codes (upstream note
                                // drafted, not filed).
                                //
                                // Never deactivates the client (matches the sync-loop
                                // ErrEpochMismatch handling — handleConnectException's error
                                // callback is not invoked for either code); without a restored
                                // envelope both codes propagate unchanged (today's behaviour,
                                // scenario 3).
                                logDebug(
                                    "AD",
                                    "stale epoch/checkpoint (${errorCodeOf(e)}) on resume; " +
                                        "re-anchoring $documentKey",
                                )
                                emitLocalChangesDropped(
                                    document,
                                    Document.Event.Reason.EpochReanchor,
                                    document.pendingChanges(),
                                )
                                removeFromStore(documentKey)
                                document.resetForReanchor()
                                document.setActor(requireActorId())
                                attachOnce(reanchor = true)
                            } else {
                                throw e
                            }
                        }

                        // Ordering (spec 009 — closes the PR #358 clearHistory window; JS SDK
                        // v0.7.16 reference at packages/sdk/src/client/client.ts:665-793 @
                        // 28a5a42e admits no interleaving because that block is synchronous, so
                        // no JS-observable case changes): single clearHistory() (wipes
                        // pre-attach/offline entries; runs before the Removed check for develop
                        // parity, so a reused Document instance whose server-side copy was
                        // removed cannot undo into an unsyncable state) → Removed early-return
                        // (releases the lease — this Attachment will never exist to own it) →
                        // applyStatus(Attached) → attachment registration (hands the lease off)
                        // → persist subscription → runWatchLoop → initialRoot
                        // updateAsync(skipHistory = true) (never enters history, so it needs no
                        // trailing cleanup) → return. History is already cleared before the
                        // initializer runs, so a user edit made after the Attached event while
                        // the initializer is still suspended keeps its undo entry instead of
                        // being silently wiped. On an initializer throw the document still stays
                        // Attached and registered (detachable, matching JS's no-rollback
                        // behavior; the lease is NOT released here — it belongs to the
                        // Attachment now, two-tier rule), and history is already cleared.
                        // Mirrors JS SDK PR #1238 for the history flush itself.
                        document.clearHistory()

                        if (document.getStatus() == ResourceStatus.Removed) {
                            // Pre-registration: no Attachment will ever exist to release
                            // this lease (JS `client.ts` mirrors this early return).
                            sessionLockHandle?.release()
                            return@async SUCCESS
                        }

                        document.applyStatus(ResourceStatus.Attached)
                        val attachment = Attachment(
                            resource = document,
                            resourceId = response.documentId,
                            syncMode = syncMode,
                            disableGC = disableGC,
                            disablePresence = response.disablePresence,
                            watchFallbackDelay = options.watchFallbackDelay.inWholeMilliseconds,
                        )
                        // The lease and the persist gate now belong to the attachment;
                        // detachInternal is the only remaining release site.
                        attachment.sessionLockHandle = sessionLockHandle
                        attachment.persistsToStore = options.docStore != null && persistsToStore
                        attachments[documentKey] = attachment
                        registered = true

                        // (8) Persist on every local change, content or presence-only (spec 029
                        // B2): the hook fires from inside updateAsync/undo-redo right after
                        // localChanges += change, with zero suspension before enqueuePersist
                        // registers the write — the old event-driven collector suspended in
                        // Document.toBytes() before it ever touched persistQueues, leaving a
                        // window where close()'s scope.cancel() could kill it mid-snapshot.
                        if (attachment.persistsToStore) {
                            document.onLocalChange = { enqueuePersist(attachment) }
                        }

                        // Manual and Polling are stream-less modes; only realtime modes
                        // open a watch stream. Mirrors JS SDK PR #1243.
                        if (syncMode != SyncMode.Manual && syncMode != SyncMode.Polling) {
                            runWatchLoop(documentKey)
                        }

                        val initialRootResult = try {
                            document.updateAsync(skipHistory = true) { root, _ ->
                                initialRoot.forEach { (key, initializer) ->
                                    if (key !in root.keys) {
                                        initializer(root, key)
                                    }
                                }
                            }.await()
                        } catch (t: Throwable) {
                            ensureActive()
                            Result.failure(t)
                        }
                        if (initialRootResult.isFailure) {
                            // Post-registration: the Attachment owns the lease now; do
                            // NOT release it here (two-tier rule) — detachInternal will.
                            return@async initialRootResult
                        }
                    } catch (e: Throwable) {
                        ensureActive()
                        (e as? ConnectException)?.let { exception ->
                            handleConnectException(exception) { ex ->
                                if (errorCodeOf(ex) == ErrUnauthenticated.codeString) {
                                    shouldRefreshToken = true
                                }
                                deactivateInternal()
                            }
                        }
                        return@async Result.failure(e)
                    } finally {
                        // Pre-registration failure (including a cancellation rethrown by
                        // ensureActive above): no Attachment exists to own the lease, so
                        // release it here. Idempotent, so a lease already released above
                        // (the Removed early return) is unaffected.
                        if (!registered) {
                            sessionLockHandle?.release()
                        }
                    }
                }
            } finally {
                attachingDocs -= documentKey
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

    private suspend fun detachInternal(key: String, drain: Boolean = true) {
        val attachment = attachments[key] ?: return
        attachment.cancelWatchJob()
        // MEDIUM-1: drain this attachment's persist chain (bounded to 5s) BEFORE
        // clearing the local-change hook below — a write already registered in
        // persistQueues for an edit made just before detach/deactivate/remove
        // must not be silently dropped. Suspend is required for the drain;
        // every caller (detachDocument, syncInternal's Removed path,
        // deactivateInternal's inline forEach, removeDocument) already runs on
        // a suspend context.
        // [drain] is false only from deactivateInternal, which already ran one
        // bounded drainAllPersists() across every attachment before its loop
        // (spec 029 M3): draining again per-document here would re-serialize
        // the exact N*5s stacking that single drain exists to avoid.
        if (drain && attachment.persistsToStore) {
            (attachment.resource as? Document)?.let { drainPersist(storeKey(it.getKey())) }
        }
        // Clear the local-change hook and release the session lease here — the
        // single choke point for detachDocument, syncInternal's Removed path,
        // deactivateInternal, and removeDocument. Both are non-suspending so
        // the NonCancellable/GlobalScope keepalive paths cannot skip them. No
        // store removal here (JS parity, recorded determination): the
        // persisted envelope is re-validated on the next resume by the actor
        // guard, epoch check, and tier-3 purge guard.
        (attachment.resource as? Document)?.onLocalChange = null
        // M2: prune this key's queue entry once its job has settled, so a
        // client that attaches and detaches many documents over its lifetime
        // does not accumulate one ConcurrentHashMap entry per ever-attached
        // document. A write still running (observed above via the drain, or
        // one that outlived its bound) stays chained so a re-attach's first
        // persist still waits behind it instead of racing it.
        (attachment.resource as? Document)?.let { doc ->
            persistQueues.computeIfPresent(storeKey(doc.getKey())) { _, job ->
                if (job.isCompleted) null else job
            }
        }
        attachment.sessionLockHandle?.release()
        attachment.sessionLockHandle = null
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
        // M3: one bounded drain across every attachment instead of detachInternal's
        // per-document 5s drain N times over (spec 029) — a client deactivating with
        // several store-backed documents used to block for up to N*5s serially.
        withTimeoutOrNull(5_000) { drainAllPersists() }
        attachments.values.forEach {
            detachInternal(it.resource.getKey(), drain = false)
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
     * The stable actor stamped into this client's document changes, or null while deactivated.
     * Equal to [requireClientId] against a pre-0.7.20 server, which never sends `actor_id`.
     */
    public val actorId: String?
        get() = (status.value as? Status.Activated)?.actorId

    /**
     * Returns the stable actor stamped into this client's document changes, throwing if the
     * client is not active. See [actorId].
     */
    public fun requireActorId(): String {
        if (status.value is Status.Deactivated) {
            throw YorkieException(ErrClientNotActivated, "client is not active")
        }
        return (status.value as Status.Activated).actorId
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

    /**
     * Closes this [Client] locally: releases its dispatcher and HTTP resources. This is an
     * abrupt teardown — it sends no detach/deactivate RPC and releases no session lease.
     * [detachDocument]/[deactivateAsync] before [close] remains the durable, server-acked path.
     *
     * An abrupt [close] still drains any in-flight/chained persist write, bounded to 5s, before
     * cancelling the client scope (spec 025 MEDIUM-1): Kotlin's structured-concurrency
     * cancellation is not JS promise semantics — a `store.save` for the last local edit is not
     * automatically awaited — so without this drain an edit made just before [close] could be
     * silently dropped. This 5s wait is a bounded OBSERVATION window, not the write's own
     * deadline: [enqueuePersist] runs the actual write on [Dispatchers.IO] (round 5 amendment), so
     * a write slower than 5s still completes on its own after [close] returns — it is just no
     * longer awaited by this call. Cancelling the client [scope] and closing the client's own
     * single-thread [dispatcher] below does not affect that write, because it never depended on
     * either.
     *
     * The drain runs only when [Options.docStore] is configured, so a client without a store
     * keeps the previous non-blocking [close]. With a store, [close] blocks the calling thread
     * for up to 5s: call it from a background thread or coroutine, not from a UI lifecycle
     * callback.
     */
    override fun close() {
        if (options.docStore != null) {
            runBlocking { withTimeoutOrNull(5_000) { drainAllPersists() } }
        }
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
         *
         * @property clientId The per-session id used for RPC row lookups (activate/deactivate,
         * attach/detach requests). Never used to key document changes.
         * @property actorId The stable actor stamped into document changes and declared on the
         * document watch stream. Equal to [clientId] against a pre-0.7.20 server, which never
         * sends `actor_id` (see [Client.activateAsync]).
         */
        public class Activated internal constructor(
            public val clientId: String,
            public val actorId: String = clientId,
        ) : Status

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
        /**
         * `watchFallbackDelay` is how long a realtime document's watch stream may stay
         * silent (no frame of any kind received) before that attachment starts pulling
         * on every sync-loop tick (throttled to [documentPollInterval]) as if it were
         * `Polling` — i.e. degrades toward polling semantics per-attachment until a
         * watch frame arrives again. This bounds staleness instead of the permanent
         * pull starvation a silently-dead watch stream otherwise causes (a dead watch
         * stream never flips `changeEventReceived`, so pull would never re-engage on
         * its own — see #351).
         *
         * Default is `10000`(ms), ON: healthy realtime streams deliver far more often
         * than every 10s, so this default avoids false engagement on a healthy-but-quiet
         * stream while bounding staleness to roughly `watchFallbackDelay +
         * documentPollInterval`; 10s is also comfortably under the server's 15s
         * ChannelSessionTTL, keeping recovery within a single session lifetime.
         *
         * Set to `Duration.INFINITE` to disable fallback entirely (a silent watch
         * stream then starves pull forever, matching pre-fallback behavior).
         *
         * Note: the fallback targets HALF-OPEN silence (stream object alive but
         * delivering nothing — the shape that never triggers the reconnect loop).
         * It runs inside the client's own sync loop, so it cannot help with hard
         * outages where that loop itself stops; those surface as RPC errors through
         * the existing retry/backoff path instead.
         */
        public val watchFallbackDelay: Duration = 10_000.milliseconds,
        /**
         * Backing store for offline local persistence. When set, [attachDocument] resumes a
         * previously [Document.toBytes]-persisted envelope before contacting the server, and the
         * document is persisted again on every local change, local presence change, and
         * successful sync. Unset (the default) disables persistence entirely — no load, no lease,
         * no writes. Ported from JS `client.ts`'s `store` option (`2291bf67`/#1338).
         */
        public val docStore: DocStore? = null,
        /**
         * Single-active-session guard used when [docStore] is set, to prevent two sessions from
         * concurrently resuming the same persisted document under the same stable actor (which
         * would mint colliding `clientSeq` values and silently lose edits). Defaults to
         * [NoopSessionLock]: unlike a browser with multiple tabs, an Android app is one process
         * per store by default, so the hazard this guards against is reachable only when [docStore]
         * is itself shared across processes. Ignored when [docStore] is unset. Ported from JS
         * `client.ts`'s `sessionLock` option; JS's browser-only `deactivateOnUnload` auto-default
         * has no Android analog (no page-unload event) and is not ported.
         */
        public val sessionLock: SessionLock = NoopSessionLock,
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

        // 24 lowercase hex characters — the shape ActorID.toActorID()/toByteString()
        // decode (12 bytes). Guards a server-supplied activate_client_response.actor_id
        // before it is stamped into document changes.
        private val ActorIdRegex = Regex("[0-9a-f]{24}")
    }
}
