package dev.yorkie.core

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.Headers
import com.connectrpc.ResponseMessage
import com.connectrpc.ServerOnlyStreamInterface
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import com.google.rpc.ErrorInfo
import dev.yorkie.api.snapshotToBytes
import dev.yorkie.api.toPBChange
import dev.yorkie.api.toPBTimeTicket
import dev.yorkie.api.toPBVersionVector
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.ActivateClientResponse
import dev.yorkie.api.v1.AttachChannelRequest
import dev.yorkie.api.v1.AttachChannelResponse
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.AttachDocumentResponse
import dev.yorkie.api.v1.BroadcastRequest
import dev.yorkie.api.v1.BroadcastResponse
import dev.yorkie.api.v1.CreateRevisionRequest
import dev.yorkie.api.v1.CreateRevisionResponse
import dev.yorkie.api.v1.DeactivateClientRequest
import dev.yorkie.api.v1.DeactivateClientResponse
import dev.yorkie.api.v1.DetachChannelRequest
import dev.yorkie.api.v1.DetachChannelResponse
import dev.yorkie.api.v1.DetachDocumentRequest
import dev.yorkie.api.v1.DetachDocumentResponse
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.GetRevisionRequest
import dev.yorkie.api.v1.GetRevisionResponse
import dev.yorkie.api.v1.ListRevisionsRequest
import dev.yorkie.api.v1.ListRevisionsResponse
import dev.yorkie.api.v1.OperationKt.remove
import dev.yorkie.api.v1.OperationKt.set
import dev.yorkie.api.v1.PeekChannelRequest
import dev.yorkie.api.v1.PeekChannelResponse
import dev.yorkie.api.v1.PushPullChangesRequest
import dev.yorkie.api.v1.PushPullChangesResponse
import dev.yorkie.api.v1.RefreshChannelRequest
import dev.yorkie.api.v1.RefreshChannelResponse
import dev.yorkie.api.v1.RemoveDocumentRequest
import dev.yorkie.api.v1.RemoveDocumentResponse
import dev.yorkie.api.v1.ResourceDescriptor
import dev.yorkie.api.v1.RestoreRevisionRequest
import dev.yorkie.api.v1.RestoreRevisionResponse
import dev.yorkie.api.v1.ValueType
import dev.yorkie.api.v1.WatchRequest
import dev.yorkie.api.v1.WatchResponse
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.api.v1.activateClientResponse
import dev.yorkie.api.v1.attachChannelResponse
import dev.yorkie.api.v1.attachDocumentResponse
import dev.yorkie.api.v1.broadcastResponse
import dev.yorkie.api.v1.change
import dev.yorkie.api.v1.changePack
import dev.yorkie.api.v1.channelEvent
import dev.yorkie.api.v1.channelInit
import dev.yorkie.api.v1.channelWatchEvent
import dev.yorkie.api.v1.checkpoint
import dev.yorkie.api.v1.createRevisionResponse
import dev.yorkie.api.v1.deactivateClientResponse
import dev.yorkie.api.v1.detachChannelResponse
import dev.yorkie.api.v1.detachDocumentResponse
import dev.yorkie.api.v1.docEvent
import dev.yorkie.api.v1.docWatchEvent
import dev.yorkie.api.v1.documentInit
import dev.yorkie.api.v1.getRevisionResponse
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.listRevisionsResponse
import dev.yorkie.api.v1.operation
import dev.yorkie.api.v1.peekChannelResponse
import dev.yorkie.api.v1.pushPullChangesResponse
import dev.yorkie.api.v1.refreshChannelResponse
import dev.yorkie.api.v1.removeDocumentResponse
import dev.yorkie.api.v1.resourceInit
import dev.yorkie.api.v1.restoreRevisionResponse
import dev.yorkie.api.v1.revisionSummary
import dev.yorkie.api.v1.watchEvent
import dev.yorkie.api.v1.watchInitialization
import dev.yorkie.api.v1.watchResponse
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrSessionNotFound
import dev.yorkie.util.YorkieException.Code.ErrUnauthenticated
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.yorkie.api.v1.ChannelEvent.Type as PbChannelEventType

class MockYorkieService(
    val customError: MutableMap<String, Code> = defaultError,
) : YorkieServiceClientInterface {

    var refreshChannelSessionCount = 0L
    var peekChannelSessionCount = 0L

    /** Fails the next non-first-call refresh with ErrSessionNotFound once. */
    var refreshChannelSessionNotFoundOnce = false

    /** Fails every refresh with a non-retryable generic error while true. */
    var refreshChannelFails = false

    /** Number of refreshChannel calls that carried an empty session_id. */
    var refreshChannelFirstCallCount = 0

    /** Suspends each refresh for this long, to simulate an in-flight RPC. */
    var refreshChannelDelayMs = 0L

    /**
     * Stable actor to include in [ActivateClientResponse.actor_id]. Null (the default) omits the
     * field, matching a pre-0.7.20 server — `requireActorId() == requireClientId()`. Set to
     * [TEST_STABLE_ACTOR_ID] (or any 24-hex string) to exercise the distinct-actor path.
     */
    var activateResponseActorId: String? = null

    /**
     * Single-shot: the next [attachDocument] call for this document key fails with
     * [YorkieException.Code.ErrEpochMismatch]; consumed after firing, so a retry attach succeeds.
     */
    val epochMismatchOnAttachOnceKeys = mutableSetOf<String>()

    /**
     * Single-shot: the next [attachDocument] call for this document key fails with
     * [YorkieException.Code.ErrInvalidServerSeq] in the real yorkie 0.7.20 server shape (Connect
     * `INVALID_ARGUMENT`, "checkpoint serverSeq exceeds server state") — round-2 QA HIGH-1/
     * MEDIUM-2: the server checks the seeded epoch FIRST and only returns `ErrEpochMismatch` once
     * epochs already match, so a never-yet-compacted (epoch-0) envelope hits this code instead.
     * Consumed after firing, so a retry attach succeeds.
     */
    val invalidServerSeqOnAttachOnceKeys = mutableSetOf<String>()

    /**
     * Document keys whose next attach fails with the REAL 0.7.20 wire shape of
     * `ErrInvalidServerSeq`: bare `INVALID_ARGUMENT` + fixed message, no `ErrorInfo`.
     */
    val invalidServerSeqBareOnAttachOnceKeys = mutableSetOf<String>()

    /** Replaces the watch-init `clientIds` list (e.g. `emptyList()` to omit the subscriber). */
    var watchInitClientIdsOverride: List<String>? = null

    /**
     * Document keys whose [attachDocument]/[pushPullChanges]/[detachDocument]/
     * [removeDocument] call fails ONCE with
     * [YorkieException.Code.ErrPresenceBaseUnavailable] (D4, spec 040) -- consumed after
     * firing, so `Client`'s own retry wrapper resends once and succeeds.
     */
    val presenceBaseUnavailableOnceKeys = mutableSetOf<String>()

    /**
     * Document keys whose [attachDocument]/[pushPullChanges]/[detachDocument]/
     * [removeDocument] SUCCESS response advertises the `presence-patch` capability
     * (`ChangePack.capabilities`, spec 040) -- opt-in, so every other existing test's
     * default response is unchanged.
     */
    val presencePatchCapableKeys = mutableSetOf<String>()

    /**
     * Forces [attachDocument]'s response `document_id` for this document key — simulates a
     * server-assigned id differing from a persisted one (tier-3 purge probe).
     */
    val attachDocumentIdOverride = mutableMapOf<String, String>()

    /**
     * Forces [attachDocument]'s response `changePack.checkpoint.server_seq` to 0 for this document
     * key (tier-3 purge probe).
     */
    val attachServerSeqResetKeys = mutableSetOf<String>()

    /** The `actor_id` most recently sent on a document/channel [WatchRequest], for assertions. */
    var lastDocumentWatchActorId: String? = null
    var lastChannelWatchActorId: String? = null

    /**
     * The `heartbeat_interval_ms` most recently sent on a document/channel [WatchRequest]
     * (`c1a03877`/#1483), for assertions -- the client's OWN request, distinct from
     * [documentWatchHeartbeatIntervalMsOverride] below, which is the server's reply.
     */
    var lastDocumentWatchHeartbeatIntervalMs: Long? = null
    var lastChannelWatchHeartbeatIntervalMs: Long? = null

    /**
     * Number of [watch] calls that opened a NEW document stream -- a reconnect (idle timeout
     * or otherwise) opens a fresh one, so this lets a test assert whether a reconnect happened
     * without inspecting `Client`'s own internals (#1472).
     */
    var documentWatchConnectCount = 0

    /**
     * Overrides [WatchInitialization.heartbeat_interval_ms] on the NEXT document watch
     * connection's init frame (every reconnect re-reads this; null omits the field, proto3's
     * default 0 — an older server, or one that advertises none, #1472).
     */
    var documentWatchHeartbeatIntervalMsOverride: Long? = null

    /**
     * When set, invoked with this connection's response channel right after the init frame
     * (and the existing 50ms settle delay) INSTEAD of the default CHANGED/WATCHED/UNWATCHED
     * script or [SILENT_WATCH_DOCUMENT_KEY]'s plain silence -- gives a test full control over
     * what follows init: periodic [WatchHeartbeat] frames, deliberate silence, or a mix,
     * without inventing a new document-key constant per scenario (#1472, `WatchIdleTest`).
     */
    var documentWatchFramePusher: (suspend (SendChannel<WatchResponse>) -> Unit)? = null

    /**
     * Channel-stream twin of [documentWatchConnectCount]/[documentWatchHeartbeatIntervalMsOverride]/
     * [documentWatchFramePusher] (M6, team-review RTCOLLABPLATFORM-789): a channel watch (e.g.
     * [dev.yorkie.core.Client.attachChannel]) reconnects on its own idle/heartbeat path, same as a
     * document watch, but had no equivalent seam to test it with.
     */
    var channelWatchConnectCount = 0
    var channelWatchHeartbeatIntervalMsOverride: Long? = null
    var channelWatchFramePusher: (suspend (SendChannel<WatchResponse>) -> Unit)? = null

    /**
     * Document keys whose [pushPullChanges] response is an ACK-ONLY pure push-ack: empty
     * `changes`, no `snapshot`, and a `checkpoint` that echoes the request's own checkpoint
     * (which [dev.yorkie.document.Document.createChangePack] already advanced by the pushed
     * changes' count — so echoing it back IS the correct ack, derived rather than hard-coded).
     * The otherwise-default response always carries one remote change
     * (`k2`) with no advanced checkpoint — that default is unchanged for every other key.
     */
    val ackOnlyPushPullKeys = mutableSetOf<String>()

    /**
     * Overrides [ackOnlyPushPullKeys]'s response `version_vector` for this document key --
     * models the server's reply to a push-only/sync-off request still carrying the
     * document's minimum synced version vector even though the reply itself carries no
     * changes and no snapshot (JS SDK `client.ts` `b34118b4`, yorkie-js-sdk#1393): applying
     * it would run `garbageCollect` with that vector regardless of what the reply contains.
     * Defaults to [VersionVector.INITIAL_VERSION_VECTOR] (an empty vector collects nothing)
     * when a key has no override. Self-clearing (removed on the ONE
     * [pushPullChanges] call that reads it) -- a test driving the real sync loop across
     * more than one tick (e.g. a mode change mid-flight) needs exactly one crafted reply,
     * not every tick re-arming it.
     */
    val ackOnlyVersionVectorOverride = mutableMapOf<String, VersionVector>()

    /**
     * Document keys whose [pushPullChanges] fails with [YorkieException.Code.ErrPermissionDenied]
     * (an authorization webhook denying a PushPull, D2) -- a live set, not a one-shot: the test
     * removes a key once it wants the next push-pull to succeed again (the webhook "relenting"),
     * so `Client`'s auth-error dedupe can be observed clearing on that transition too.
     */
    val permissionDeniedPushPullKeys = mutableSetOf<String>()

    /**
     * Document keys whose [pushPullChanges] fails with the given [YorkieException.Code]
     * (either [YorkieException.Code.ErrDocumentSizeExceedsLimit] or
     * [YorkieException.Code.ErrChangeTooLarge], #1462) at [Code.RESOURCE_EXHAUSTED] -- a live
     * map, not a one-shot, same shape as [permissionDeniedPushPullKeys]: the test removes a key
     * once it wants the next push-pull to succeed again (e.g. after discarding the offending
     * local changes via `Client.resyncAsync`), so the park/clear transition can be observed.
     */
    val writeRejectedPushPullKeys = mutableMapOf<String, YorkieException.Code>()

    /**
     * Document keys whose [pushPullChanges] fails with [Code.RESOURCE_EXHAUSTED] but NO Yorkie
     * code metadata at all -- models a transport-level quota unrelated to #1462's two codes,
     * which `handleConnectException` must keep retrying. A live set, same shape as
     * [permissionDeniedPushPullKeys].
     */
    val bareResourceExhaustedPushPullKeys = mutableSetOf<String>()

    /**
     * Document keys whose [pushPullChanges] response is a PULL that is also an ack: the
     * request's own checkpoint echoed back (as [ackOnlyPushPullKeys]) plus one remote change
     * setting a fresh `pull<N>` key — harmless to re-apply any number of times, unlike the
     * default response's remove of the element created at lamport 1.
     */
    val pullWithAckPushPullKeys = mutableSetOf<String>()
    private var pullCount = 0

    /**
     * Document keys whose [pushPullChanges] response is a SNAPSHOT that is also an ack: the
     * request's own checkpoint echoed back (as [ackOnlyPushPullKeys]) plus a minimal valid
     * snapshot (an empty [CrdtObject] rooted at [InitialTimeTicket], encoded via
     * [dev.yorkie.api.snapshotToBytes]) so [dev.yorkie.document.change.ChangePack.hasSnapshot] is
     * true and `hasChanges` is false — models the real server's answer to an explicit sync once
     * another client has pushed the document past the snapshot threshold (#1401, `bed81143`): in
     * push-only/sync-off this response must still be ACKED (clientSeq forwarded) without being
     * applied to the root.
     */
    val snapshotWithAckPushPullKeys = mutableSetOf<String>()

    /**
     * Document keys whose [pushPullChanges] response is a PULL-with-ack (as
     * [pullWithAckPushPullKeys]) that ALSO reports the document Removed (`isRemoved`) --
     * models the server telling a push-only/sync-off client, in the SAME round trip that
     * acks its pushed edit, that the document's row is gone (#1401, `bed81143`): the
     * removal flag reaches the document even though the accompanying peer change is
     * dropped.
     */
    val removedAckPushPullKeys = mutableSetOf<String>()

    /**
     * Document keys whose [pushPullChanges] response is a SNAPSHOT-with-ack (as
     * [snapshotWithAckPushPullKeys]) at a new compaction epoch -- models a dropped pack
     * that is also a compaction (#1401, `bed81143`): the epoch is metadata describing the
     * document itself, not the remote content being dropped, and must be adopted even
     * though the snapshot is not.
     */
    val epochSnapshotAckPushPullKeys = mutableSetOf<String>()

    /**
     * Document keys whose [detachDocument] response reports the document Removed
     * (`isRemoved`), the shape a detach racing a peer's remove takes.
     */
    val detachRemovedKeys = mutableSetOf<String>()

    /**
     * Fires once, inside [pushPullChanges] BEFORE the response is built, then clears itself —
     * simulates an edit minted on the document while a push is "in flight" (the #1355
     * counter-ahead-loss case).
     */
    var inFlightPushPullHook: (suspend () -> Unit)? = null

    override suspend fun activateClient(
        request: ActivateClientRequest,
        headers: Headers,
    ): ResponseMessage<ActivateClientResponse> {
        return ResponseMessage.Success(
            activateClientResponse {
                clientId = TEST_ACTOR_ID
                activateResponseActorId?.let { actorId = it }
            },
            emptyMap(),
            emptyMap(),
        )
    }

    override suspend fun deactivateClient(
        request: DeactivateClientRequest,
        headers: Headers,
    ): ResponseMessage<DeactivateClientResponse> {
        return ResponseMessage.Success(deactivateClientResponse { }, emptyMap(), emptyMap())
    }

    override suspend fun attachDocument(
        request: AttachDocumentRequest,
        headers: Headers,
    ): ResponseMessage<AttachDocumentResponse> {
        if (request.changePack.documentKey == ATTACH_ERROR_DOCUMENT_KEY) {
            return ResponseMessage.Failure(
                ConnectException(customError[ATTACH_ERROR_DOCUMENT_KEY]!!),
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey == ATTACH_DELAY_DOCUMENT_KEY) {
            // Simulates a slow attach round-trip so a concurrent second attach of the
            // same key is enqueued while the first is still in flight.
            delay(200L)
            return ResponseMessage.Success(
                attachDocumentResponse {
                    changePack = changePack {
                        documentKey = request.changePack.documentKey
                    }
                    documentId = request.changePack.documentKey
                },
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey == AUTH_ERROR_DOCUMENT_KEY) {
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", ErrUnauthenticated.codeString)
                .build()

            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns customError[AUTH_ERROR_DOCUMENT_KEY]!!
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in epochMismatchOnAttachOnceKeys) {
            epochMismatchOnAttachOnceKeys.remove(request.changePack.documentKey)
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrEpochMismatch.codeString)
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.FAILED_PRECONDITION
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in invalidServerSeqOnAttachOnceKeys) {
            invalidServerSeqOnAttachOnceKeys.remove(request.changePack.documentKey)
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrInvalidServerSeq.codeString)
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.INVALID_ARGUMENT
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in invalidServerSeqBareOnAttachOnceKeys) {
            invalidServerSeqBareOnAttachOnceKeys.remove(request.changePack.documentKey)
            // The real yorkie 0.7.20 server shape: a bare INVALID_ARGUMENT with the fixed
            // message and NO ErrorInfo detail (spec 025 round-2 HIGH-1).
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.INVALID_ARGUMENT
                every { message } returns "checkpoint serverSeq exceeds server state"
                every { unpackedDetails(ErrorInfo::class) } returns emptyList()
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in presenceBaseUnavailableOnceKeys) {
            presenceBaseUnavailableOnceKeys.remove(request.changePack.documentKey)
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrPresenceBaseUnavailable.codeString)
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.FAILED_PRECONDITION
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        val forcedDocumentId = attachDocumentIdOverride[request.changePack.documentKey]
        val forceSeqReset = request.changePack.documentKey in attachServerSeqResetKeys
        val resetCheckpoint = if (forceSeqReset) {
            checkpoint {
                serverSeq = 0
                clientSeq = 0
            }
        } else {
            null
        }
        return ResponseMessage.Success(
            attachDocumentResponse {
                changePack = changePack {
                    documentKey = request.changePack.documentKey
                    resetCheckpoint?.let { checkpoint = it }
                    changes.add(
                        Change(
                            ChangeID(0u, 0, TEST_ACTOR_ID, VersionVector.INITIAL_VERSION_VECTOR),
                            listOf(
                                SetOperation(
                                    "k1",
                                    CrdtPrimitive(4, InitialTimeTicket.copy(lamport = 1)),
                                    InitialTimeTicket,
                                    InitialTimeTicket,
                                ),
                            ),
                        ).toPBChange(),
                    )
                    if (request.changePack.documentKey in presencePatchCapableKeys) {
                        capabilities.add(ChangePack.PRESENCE_PATCH_CAPABILITY)
                    }
                }
                documentId = forcedDocumentId ?: changePack.documentKey
                // Echoes the request's disablePresence back, same as the real server
                // persisting and returning it (resyncAsync's re-attach, D4, is the first
                // caller that round-trips this instead of only ever reading the request
                // param it itself just passed down).
                disablePresence = request.disablePresence
            },
            emptyMap(),
            emptyMap(),
        )
    }

    override suspend fun detachDocument(
        request: DetachDocumentRequest,
        headers: Headers,
    ): ResponseMessage<DetachDocumentResponse> {
        if (request.changePack.documentKey == DETACH_ERROR_DOCUMENT_KEY) {
            return ResponseMessage.Failure(
                ConnectException(customError[DETACH_ERROR_DOCUMENT_KEY]!!),
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey in presenceBaseUnavailableOnceKeys) {
            presenceBaseUnavailableOnceKeys.remove(request.changePack.documentKey)
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrPresenceBaseUnavailable.codeString)
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.FAILED_PRECONDITION
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in detachRemovedKeys) {
            return ResponseMessage.Success(
                detachDocumentResponse { changePack = changePack { isRemoved = true } },
                emptyMap(),
                emptyMap(),
            )
        }
        return ResponseMessage.Success(
            detachDocumentResponse {
                if (request.changePack.documentKey in presencePatchCapableKeys) {
                    changePack = changePack {
                        capabilities.add(ChangePack.PRESENCE_PATCH_CAPABILITY)
                    }
                }
            },
            emptyMap(),
            emptyMap(),
        )
    }

    /**
     * Number of [pushPullChanges] calls seen per document key -- lets a test assert a parked
     * attachment (#1462) stops being re-sent every `syncLoopDuration` tick, while an unrelated
     * document keeps ticking normally.
     */
    // M7 (team-review, RTCOLLABPLATFORM-789): ConcurrentHashMap -- the sync loop's
    // document-keyed ticks and a test's concurrent read of this map (polling for a
    // call count) run on different dispatchers; a plain mutableMapOf is not safe for
    // that, even read-mostly.
    val pushPullChangesCallCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override suspend fun pushPullChanges(
        request: PushPullChangesRequest,
        headers: Headers,
    ): ResponseMessage<PushPullChangesResponse> {
        pushPullChangesCallCounts.merge(request.changePack.documentKey, 1, Int::plus)
        if (request.changePack.documentKey == WATCH_SYNC_ERROR_DOCUMENT_KEY) {
            return ResponseMessage.Failure(
                ConnectException(
                    customError[WATCH_SYNC_ERROR_DOCUMENT_KEY]!!,
                ),
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey == EPOCH_MISMATCH_DOCUMENT_KEY) {
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrEpochMismatch.codeString)
                .build()

            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns customError[EPOCH_MISMATCH_DOCUMENT_KEY]!!
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in permissionDeniedPushPullKeys) {
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrPermissionDenied.codeString)
                .putMetadata("reason", "write denied by webhook")
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.PERMISSION_DENIED
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        writeRejectedPushPullKeys[request.changePack.documentKey]?.let { rejectedCode ->
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", rejectedCode.codeString)
                .putMetadata("reason", "the change the client pushed is too large")
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.RESOURCE_EXHAUSTED
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in bareResourceExhaustedPushPullKeys) {
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.RESOURCE_EXHAUSTED
                every { unpackedDetails(ErrorInfo::class) } returns emptyList()
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (request.changePack.documentKey in presenceBaseUnavailableOnceKeys) {
            presenceBaseUnavailableOnceKeys.remove(request.changePack.documentKey)
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrPresenceBaseUnavailable.codeString)
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.FAILED_PRECONDITION
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        inFlightPushPullHook?.let { hook ->
            inFlightPushPullHook = null
            hook()
        }
        if (request.changePack.documentKey in ackOnlyPushPullKeys) {
            val versionVector = ackOnlyVersionVectorOverride.remove(request.changePack.documentKey)
                ?: VersionVector.INITIAL_VERSION_VECTOR
            return ResponseMessage.Success(
                pushPullChangesResponse {
                    changePack = changePack {
                        checkpoint = request.changePack.checkpoint
                        this.versionVector = versionVector.toPBVersionVector()
                        if (request.changePack.documentKey in presencePatchCapableKeys) {
                            capabilities.add(ChangePack.PRESENCE_PATCH_CAPABILITY)
                        }
                    }
                },
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey in snapshotWithAckPushPullKeys) {
            return ResponseMessage.Success(
                pushPullChangesResponse {
                    changePack = changePack {
                        checkpoint = request.changePack.checkpoint
                        snapshot = snapshotToBytes(CrdtObject(InitialTimeTicket), emptyMap())
                    }
                },
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey in pullWithAckPushPullKeys) {
            pullCount += 1
            return ResponseMessage.Success(
                pushPullChangesResponse {
                    changePack = changePack {
                        checkpoint = request.changePack.checkpoint
                        changes.add(
                            change { operations.add(createSetOperation("pull$pullCount")) },
                        )
                    }
                },
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey in removedAckPushPullKeys) {
            return ResponseMessage.Success(
                pushPullChangesResponse {
                    changePack = changePack {
                        checkpoint = request.changePack.checkpoint
                        changes.add(change { operations.add(createSetOperation("peer")) })
                        isRemoved = true
                    }
                },
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.changePack.documentKey in epochSnapshotAckPushPullKeys) {
            return ResponseMessage.Success(
                pushPullChangesResponse {
                    changePack = changePack {
                        checkpoint = request.changePack.checkpoint
                        snapshot = snapshotToBytes(CrdtObject(InitialTimeTicket), emptyMap())
                        epoch = 3L
                    }
                },
                emptyMap(),
                emptyMap(),
            )
        }
        return ResponseMessage.Success(
            pushPullChangesResponse {
                changePack = changePack {
                    changes.add(
                        change {
                            operations.add(createSetOperation())
                            operations.add(createRemoveOperation())
                        },
                    )
                }
            },
            emptyMap(),
            emptyMap(),
        )
    }

    private fun createSetOperation(setKey: String = "k2") = operation {
        set = set {
            key = setKey
            value = jSONElementSimple {
                type = ValueType.VALUE_TYPE_DOUBLE
                value = ByteBuffer.allocate(Double.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putDouble(100.0).array()
                    .toByteString()
            }
            parentCreatedAt = InitialTimeTicket.toPBTimeTicket()
            executedAt = InitialTimeTicket.copy(lamport = 2).toPBTimeTicket()
        }
    }

    private fun createRemoveOperation() = operation {
        remove = remove {
            parentCreatedAt = InitialTimeTicket.toPBTimeTicket()
            createdAt = InitialTimeTicket.copy(lamport = 1).toPBTimeTicket()
            executedAt = InitialTimeTicket.copy(lamport = 3).toPBTimeTicket()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun watch(
        headers: Headers,
    ): ServerOnlyStreamInterface<WatchRequest, WatchResponse> {
        return object : ServerOnlyStreamInterface<WatchRequest, WatchResponse> {
            private var responseChannel = Channel<WatchResponse>()

            override fun isClosed(): Boolean {
                return responseChannel.isClosedForSend
            }

            override fun isReceiveClosed(): Boolean {
                return responseChannel.isClosedForReceive
            }

            override suspend fun receiveClose() {
                responseChannel.close()
            }

            override fun responseChannel(): ReceiveChannel<WatchResponse> {
                return responseChannel.takeUnless { it.isClosedForReceive || it.isClosedForSend }
                    ?: Channel<WatchResponse>().also { responseChannel = it }
            }

            override fun responseHeaders(): Deferred<Headers> {
                return CompletableDeferred(emptyMap())
            }

            override fun responseTrailers(): Deferred<Headers> {
                return CompletableDeferred(emptyMap())
            }

            override suspend fun sendAndClose(input: WatchRequest): Result<Unit> {
                return runCatching {
                    val clientId = input.clientId
                    val isDocumentWatch = input.resourcesList.any {
                        it.resourceCase == ResourceDescriptor.ResourceCase.DOCUMENT
                    }
                    if (isDocumentWatch) {
                        lastDocumentWatchActorId = input.actorId
                        lastDocumentWatchHeartbeatIntervalMs = input.heartbeatIntervalMs
                    } else {
                        lastChannelWatchActorId = input.actorId
                        lastChannelWatchHeartbeatIntervalMs = input.heartbeatIntervalMs
                    }
                    CoroutineScope(Dispatchers.Default).launch {
                        if (responseChannel.isClosedForSend) return@launch
                        if (isDocumentWatch) {
                            handleDocumentWatch(input, clientId)
                        } else {
                            handleChannelWatch()
                        }
                    }
                }
            }

            private suspend fun handleDocumentWatch(input: WatchRequest, clientId: String) {
                val documentId = input.resourcesList
                    .firstOrNull { it.resourceCase == ResourceDescriptor.ResourceCase.DOCUMENT }
                    ?.document?.documentId ?: return
                documentWatchConnectCount += 1

                responseChannel.trySend(
                    watchResponse {
                        initialization = watchInitialization {
                            resourceInits.add(
                                resourceInit {
                                    documentInit = documentInit {
                                        this.documentId = documentId
                                        // The 0.7.20 server lists watchers by their
                                        // stable actor.
                                        clientIds.addAll(
                                            watchInitClientIdsOverride
                                                ?: listOf(activateResponseActorId ?: TEST_ACTOR_ID),
                                        )
                                    }
                                },
                            )
                            documentWatchHeartbeatIntervalMsOverride?.let {
                                heartbeatIntervalMs = it
                            }
                        }
                    },
                )
                delay(50)
                if (documentId == WATCH_SYNC_ERROR_DOCUMENT_KEY) {
                    responseChannel.close(
                        ConnectException(customError[WATCH_SYNC_ERROR_DOCUMENT_KEY]!!),
                    )
                    return
                }
                documentWatchFramePusher?.let { pusher ->
                    pusher(responseChannel)
                    return
                }
                if (documentId == SILENT_WATCH_DOCUMENT_KEY) {
                    // Init frame only, then silence forever (no error, no close, no more
                    // frames) — simulates #351's half-open watch stream for the pull
                    // fallback loop-level test, without waiting out NORMAL_DOCUMENT_KEY's
                    // full CHANGED/WATCHED/UNWATCHED script first.
                    return
                }
                responseChannel.trySend(
                    watchResponse {
                        event = watchEvent {
                            docEvent = docWatchEvent {
                                this.documentId = documentId
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED
                                    publisher = clientId
                                }
                            }
                        }
                    },
                )
                delay(1_000)
                responseChannel.trySend(
                    watchResponse {
                        event = watchEvent {
                            docEvent = docWatchEvent {
                                this.documentId = documentId
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_WATCHED
                                    publisher = clientId
                                }
                            }
                        }
                    },
                )
                delay(2_000)
                responseChannel.trySend(
                    watchResponse {
                        event = watchEvent {
                            docEvent = docWatchEvent {
                                this.documentId = documentId
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_UNWATCHED
                                    publisher = clientId
                                }
                            }
                        }
                    },
                )
            }

            private suspend fun handleChannelWatch() {
                channelWatchConnectCount += 1
                responseChannel.trySend(
                    watchResponse {
                        initialization = watchInitialization {
                            resourceInits.add(
                                resourceInit {
                                    channelInit = channelInit {}
                                },
                            )
                            channelWatchHeartbeatIntervalMsOverride?.let {
                                heartbeatIntervalMs = it
                            }
                        }
                    },
                )
                delay(50)
                channelWatchFramePusher?.let { pusher ->
                    pusher(responseChannel)
                    return
                }
                repeat(3) {
                    responseChannel.trySend(
                        watchResponse {
                            event = watchEvent {
                                channelEvent = channelWatchEvent {
                                    event = channelEvent {
                                        type = PbChannelEventType.TYPE_PRESENCE
                                    }
                                }
                            }
                        },
                    )
                    delay(1_000)
                }
                delay(500)
                responseChannel.trySend(
                    watchResponse {
                        event = watchEvent {
                            channelEvent = channelWatchEvent {
                                event = channelEvent {
                                    type = PbChannelEventType.TYPE_BROADCAST
                                    publisher = ""
                                    topic = "test-topic"
                                    payload = ByteString.copyFromUtf8("test-payload")
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    override suspend fun attachChannel(
        request: AttachChannelRequest,
        headers: Headers,
    ): ResponseMessage<AttachChannelResponse> {
        return ResponseMessage.Success(
            message = attachChannelResponse {},
            headers = emptyMap(),
            trailers = emptyMap(),
        )
    }

    override suspend fun detachChannel(
        request: DetachChannelRequest,
        headers: Headers,
    ): ResponseMessage<DetachChannelResponse> {
        return ResponseMessage.Success(
            message = detachChannelResponse {},
            headers = emptyMap(),
            trailers = emptyMap(),
        )
    }

    override suspend fun refreshChannel(
        request: RefreshChannelRequest,
        headers: Headers,
    ): ResponseMessage<RefreshChannelResponse> {
        if (refreshChannelDelayMs > 0L) {
            delay(refreshChannelDelayMs)
        }
        if (refreshChannelFails) {
            return ResponseMessage.Failure(
                cause = ConnectException(Code.FAILED_PRECONDITION),
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        if (refreshChannelSessionNotFoundOnce && request.sessionId.isNotEmpty()) {
            refreshChannelSessionNotFoundOnce = false
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", ErrSessionNotFound.codeString)
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.FAILED_PRECONDITION
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        val isFirstCall = request.sessionId.isEmpty()
        if (isFirstCall) {
            refreshChannelFirstCallCount++
        }
        return ResponseMessage.Success(
            message = refreshChannelResponse {
                sessionCount = refreshChannelSessionCount
                // The server assigns ids only on the first call.
                if (isFirstCall) {
                    clientId = TEST_ACTOR_ID
                    sessionId = MOCK_SESSION_ID
                }
            },
            headers = emptyMap(),
            trailers = emptyMap(),
        )
    }

    override suspend fun peekChannel(
        request: PeekChannelRequest,
        headers: Headers,
    ): ResponseMessage<PeekChannelResponse> {
        if (request.channelKey == AUTH_ERROR_DOCUMENT_KEY) {
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", ErrUnauthenticated.codeString)
                .build()

            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns customError[AUTH_ERROR_DOCUMENT_KEY]!!
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        return ResponseMessage.Success(
            message = peekChannelResponse {
                sessionCount = peekChannelSessionCount
            },
            headers = emptyMap(),
            trailers = emptyMap(),
        )
    }

    override suspend fun removeDocument(
        request: RemoveDocumentRequest,
        headers: Headers,
    ): ResponseMessage<RemoveDocumentResponse> {
        if (request.documentId == REMOVE_ERROR_DOCUMENT_KEY) {
            return ResponseMessage.Failure(
                ConnectException(customError[REMOVE_ERROR_DOCUMENT_KEY]!!),
                emptyMap(),
                emptyMap(),
            )
        }
        if (request.documentId in presenceBaseUnavailableOnceKeys) {
            presenceBaseUnavailableOnceKeys.remove(request.documentId)
            val errorInfo = ErrorInfo.newBuilder()
                .putMetadata("code", YorkieException.Code.ErrPresenceBaseUnavailable.codeString)
                .build()
            val connectException = mockk<ConnectException>(relaxed = true) {
                every { code } returns Code.FAILED_PRECONDITION
                every {
                    unpackedDetails(ErrorInfo::class)
                } returns listOf(errorInfo)
            }
            return ResponseMessage.Failure(
                cause = connectException,
                headers = emptyMap(),
                trailers = emptyMap(),
            )
        }
        return ResponseMessage.Success(
            removeDocumentResponse {
                changePack = changePack {
                    changes.add(
                        change {
                            operations.add(createSetOperation())
                            operations.add(createRemoveOperation())
                        },
                    )
                    isRemoved = true
                    if (request.documentId in presencePatchCapableKeys) {
                        capabilities.add(ChangePack.PRESENCE_PATCH_CAPABILITY)
                    }
                }
            },
            emptyMap(),
            emptyMap(),
        )
    }

    override suspend fun broadcast(
        request: BroadcastRequest,
        headers: Headers,
    ): ResponseMessage<BroadcastResponse> {
        return ResponseMessage.Success(broadcastResponse { }, emptyMap(), emptyMap())
    }

    override suspend fun createRevision(
        request: CreateRevisionRequest,
        headers: Headers,
    ): ResponseMessage<CreateRevisionResponse> {
        return ResponseMessage.Success(
            createRevisionResponse {
                revision = revisionSummary {
                    id = "test-revision-id"
                    label = request.label
                    description = request.description
                    snapshot = "{}"
                }
            },
            emptyMap(),
            emptyMap(),
        )
    }

    override suspend fun getRevision(
        request: GetRevisionRequest,
        headers: Headers,
    ): ResponseMessage<GetRevisionResponse> {
        return ResponseMessage.Success(
            getRevisionResponse {
                revision = revisionSummary {
                    id = request.revisionId
                    label = "test-label"
                    description = "test-description"
                    snapshot = "{}"
                }
            },
            emptyMap(),
            emptyMap(),
        )
    }

    override suspend fun listRevisions(
        request: ListRevisionsRequest,
        headers: Headers,
    ): ResponseMessage<ListRevisionsResponse> {
        return ResponseMessage.Success(
            listRevisionsResponse {},
            emptyMap(),
            emptyMap(),
        )
    }

    override suspend fun restoreRevision(
        request: RestoreRevisionRequest,
        headers: Headers,
    ): ResponseMessage<RestoreRevisionResponse> {
        return ResponseMessage.Success(
            restoreRevisionResponse {},
            emptyMap(),
            emptyMap(),
        )
    }

    companion object {
        internal const val TEST_KEY = "TEST"
        internal const val NORMAL_DOCUMENT_KEY = "NORMAL_DOCUMENT_KEY"
        internal const val SILENT_WATCH_DOCUMENT_KEY = "SILENT_WATCH_DOCUMENT_KEY"
        internal const val WATCH_SYNC_ERROR_DOCUMENT_KEY = "WATCH_SYNC_ERROR_DOCUMENT_KEY"
        internal const val ATTACH_ERROR_DOCUMENT_KEY = "ATTACH_ERROR_DOCUMENT_KEY"
        internal const val ATTACH_DELAY_DOCUMENT_KEY = "ATTACH_DELAY_DOCUMENT_KEY"
        internal const val DETACH_ERROR_DOCUMENT_KEY = "DETACH_ERROR_DOCUMENT_KEY"
        internal const val REMOVE_ERROR_DOCUMENT_KEY = "REMOVE_ERROR_DOCUMENT_KEY"
        internal const val AUTH_ERROR_DOCUMENT_KEY = "AUTH_ERROR_DOCUMENT_KEY"
        internal const val EPOCH_MISMATCH_DOCUMENT_KEY = "EPOCH_MISMATCH_DOCUMENT_KEY"
        internal val TEST_ACTOR_ID = "0000000000ffff0000000000"
        internal val TEST_STABLE_ACTOR_ID = "0000000000ffff0000000001"
        internal const val MOCK_SESSION_ID = "mock-session-id"
        internal const val TEST_USER_ID = "TEST_USER_ID"

        internal val defaultError: MutableMap<String, Code> = mutableMapOf(
            ATTACH_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            DETACH_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            REMOVE_ERROR_DOCUMENT_KEY to Code.UNAVAILABLE,
            WATCH_SYNC_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            AUTH_ERROR_DOCUMENT_KEY to Code.UNAUTHENTICATED,
            EPOCH_MISMATCH_DOCUMENT_KEY to Code.FAILED_PRECONDITION,
        )
    }
}
