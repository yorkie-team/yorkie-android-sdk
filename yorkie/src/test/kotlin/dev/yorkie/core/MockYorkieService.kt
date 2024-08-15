package dev.yorkie.core

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.Headers
import com.connectrpc.ResponseMessage
import com.connectrpc.ServerOnlyStreamInterface
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.PBTimeTicket
import dev.yorkie.api.toPBChange
import dev.yorkie.api.toPBTimeTicket
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.ActivateClientResponse
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.AttachDocumentResponse
import dev.yorkie.api.v1.BroadcastRequest
import dev.yorkie.api.v1.BroadcastResponse
import dev.yorkie.api.v1.DeactivateClientRequest
import dev.yorkie.api.v1.DeactivateClientResponse
import dev.yorkie.api.v1.DetachDocumentRequest
import dev.yorkie.api.v1.DetachDocumentResponse
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.OperationKt.remove
import dev.yorkie.api.v1.OperationKt.set
import dev.yorkie.api.v1.PushPullChangesRequest
import dev.yorkie.api.v1.PushPullChangesResponse
import dev.yorkie.api.v1.RemoveDocumentRequest
import dev.yorkie.api.v1.RemoveDocumentResponse
import dev.yorkie.api.v1.ValueType
import dev.yorkie.api.v1.WatchDocumentRequest
import dev.yorkie.api.v1.WatchDocumentResponse
import dev.yorkie.api.v1.WatchDocumentResponseKt.initialization
import dev.yorkie.api.v1.YorkieServiceClientInterface
import dev.yorkie.api.v1.activateClientResponse
import dev.yorkie.api.v1.attachDocumentResponse
import dev.yorkie.api.v1.broadcastResponse
import dev.yorkie.api.v1.change
import dev.yorkie.api.v1.changePack
import dev.yorkie.api.v1.deactivateClientResponse
import dev.yorkie.api.v1.detachDocumentResponse
import dev.yorkie.api.v1.docEvent
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.operation
import dev.yorkie.api.v1.pushPullChangesResponse
import dev.yorkie.api.v1.removeDocumentResponse
import dev.yorkie.api.v1.watchDocumentResponse
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MockYorkieService(
    val customError: MutableMap<String, Code> = defaultError,
) : YorkieServiceClientInterface {

    override suspend fun activateClient(
        request: ActivateClientRequest,
        headers: Headers,
    ): ResponseMessage<ActivateClientResponse> {
        return ResponseMessage.Success(
            activateClientResponse {
                clientId = TEST_ACTOR_ID.value
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
        return ResponseMessage.Success(
            attachDocumentResponse {
                changePack = changePack {
                    documentKey = request.changePack.documentKey
                    changes.add(
                        Change(
                            ChangeID(0u, 0, TEST_ACTOR_ID),
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
                    minSyncedTicket = PBTimeTicket.getDefaultInstance()
                }
                documentId = changePack.documentKey
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
        return ResponseMessage.Success(detachDocumentResponse { }, emptyMap(), emptyMap())
    }

    override suspend fun pushPullChanges(
        request: PushPullChangesRequest,
        headers: Headers,
    ): ResponseMessage<PushPullChangesResponse> {
        if (request.changePack.documentKey == WATCH_SYNC_ERROR_DOCUMENT_KEY) {
            return ResponseMessage.Failure(
                ConnectException(customError[WATCH_SYNC_ERROR_DOCUMENT_KEY]!!),
                emptyMap(),
                emptyMap(),
            )
        }
        return ResponseMessage.Success(
            pushPullChangesResponse {
                changePack = changePack {
                    minSyncedTicket = InitialTimeTicket.toPBTimeTicket()
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

    private fun createSetOperation() = operation {
        set = set {
            key = "k2"
            value = jSONElementSimple {
                type = ValueType.VALUE_TYPE_DOUBLE
                value = ByteBuffer.allocate(Double.SIZE_BYTES)
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
    override suspend fun watchDocument(
        headers: Headers,
    ): ServerOnlyStreamInterface<WatchDocumentRequest, WatchDocumentResponse> {
        return object : ServerOnlyStreamInterface<WatchDocumentRequest, WatchDocumentResponse> {
            private var responseChannel = Channel<WatchDocumentResponse>()

            override fun isClosed(): Boolean {
                return responseChannel.isClosedForSend
            }

            override fun isReceiveClosed(): Boolean {
                return responseChannel.isClosedForReceive
            }

            override suspend fun receiveClose() {
                responseChannel.close()
            }

            override fun responseChannel(): ReceiveChannel<WatchDocumentResponse> {
                return responseChannel.takeUnless { it.isClosedForReceive || it.isClosedForSend }
                    ?: Channel<WatchDocumentResponse>().also { responseChannel = it }
            }

            override fun responseHeaders(): Deferred<Headers> {
                return CompletableDeferred(emptyMap())
            }

            override fun responseTrailers(): Deferred<Headers> {
                return CompletableDeferred(emptyMap())
            }

            override suspend fun sendAndClose(input: WatchDocumentRequest): Result<Unit> {
                return runCatching {
                    val key = input.documentId
                    val clientId = input.clientId
                    CoroutineScope(Dispatchers.Default).launch {
                        if (responseChannel.isClosedForSend) {
                            return@launch
                        }
                        responseChannel.trySend(
                            watchDocumentResponse {
                                initialization = initialization {
                                    clientIds.add(TEST_ACTOR_ID.value)
                                }
                            },
                        )
                        delay(1_000)
                        if (key == WATCH_SYNC_ERROR_DOCUMENT_KEY) {
                            responseChannel.close()
                            return@launch
                        }
                        responseChannel.trySend(
                            watchDocumentResponse {
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED
                                    publisher = clientId
                                }
                            },
                        )
                        delay(1_000)
                        responseChannel.trySend(
                            watchDocumentResponse {
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_WATCHED
                                    publisher = clientId
                                }
                            },
                        )
                        delay(2_000)
                        responseChannel.trySend(
                            watchDocumentResponse {
                                event = docEvent {
                                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_UNWATCHED
                                    publisher = clientId
                                }
                            },
                        )
                    }
                }
            }
        }
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
        return ResponseMessage.Success(
            removeDocumentResponse {
                changePack = changePack {
                    minSyncedTicket = InitialTimeTicket.toPBTimeTicket()
                    changes.add(
                        change {
                            operations.add(createSetOperation())
                            operations.add(createRemoveOperation())
                        },
                    )
                    isRemoved = true
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

    companion object {
        internal const val TEST_KEY = "TEST"
        internal const val NORMAL_DOCUMENT_KEY = "NORMAL_DOCUMENT_KEY"
        internal const val WATCH_SYNC_ERROR_DOCUMENT_KEY = "WATCH_SYNC_ERROR_DOCUMENT_KEY"
        internal const val ATTACH_ERROR_DOCUMENT_KEY = "ATTACH_ERROR_DOCUMENT_KEY"
        internal const val DETACH_ERROR_DOCUMENT_KEY = "DETACH_ERROR_DOCUMENT_KEY"
        internal const val REMOVE_ERROR_DOCUMENT_KEY = "REMOVE_ERROR_DOCUMENT_KEY"
        internal val TEST_ACTOR_ID = ActorID("0000000000ffff0000000000")

        internal val defaultError: MutableMap<String, Code> = mutableMapOf(
            ATTACH_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            DETACH_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            REMOVE_ERROR_DOCUMENT_KEY to Code.UNAVAILABLE,
            WATCH_SYNC_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
        )
    }
}
