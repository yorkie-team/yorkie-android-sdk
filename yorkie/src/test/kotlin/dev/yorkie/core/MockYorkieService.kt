package dev.yorkie.core

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.Headers
import com.connectrpc.ResponseMessage
import com.connectrpc.ServerOnlyStreamInterface
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import com.google.rpc.ErrorInfo
import dev.yorkie.api.toPBChange
import dev.yorkie.api.toPBTimeTicket
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
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.VersionVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.yorkie.api.v1.ChannelEvent.Type as PbChannelEventType

class MockYorkieService(
    val customError: MutableMap<String, Code> = defaultError,
) : YorkieServiceClientInterface {

    override suspend fun activateClient(
        request: ActivateClientRequest,
        headers: Headers,
    ): ResponseMessage<ActivateClientResponse> {
        return ResponseMessage.Success(
            activateClientResponse {
                clientId = TEST_ACTOR_ID
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
        return ResponseMessage.Success(
            attachDocumentResponse {
                changePack = changePack {
                    documentKey = request.changePack.documentKey
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
                ConnectException(
                    customError[WATCH_SYNC_ERROR_DOCUMENT_KEY]!!,
                ),
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

    private fun createSetOperation() = operation {
        set = set {
            key = "k2"
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

                responseChannel.trySend(
                    watchResponse {
                        initialization = watchInitialization {
                            resourceInits.add(
                                resourceInit {
                                    documentInit = documentInit {
                                        this.documentId = documentId
                                        clientIds.add(TEST_ACTOR_ID)
                                    }
                                },
                            )
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
                responseChannel.trySend(
                    watchResponse {
                        initialization = watchInitialization {
                            resourceInits.add(
                                resourceInit {
                                    channelInit = channelInit {}
                                },
                            )
                        }
                    },
                )
                delay(50)
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
        return ResponseMessage.Success(
            message = refreshChannelResponse {},
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
        internal const val WATCH_SYNC_ERROR_DOCUMENT_KEY = "WATCH_SYNC_ERROR_DOCUMENT_KEY"
        internal const val ATTACH_ERROR_DOCUMENT_KEY = "ATTACH_ERROR_DOCUMENT_KEY"
        internal const val DETACH_ERROR_DOCUMENT_KEY = "DETACH_ERROR_DOCUMENT_KEY"
        internal const val REMOVE_ERROR_DOCUMENT_KEY = "REMOVE_ERROR_DOCUMENT_KEY"
        internal const val AUTH_ERROR_DOCUMENT_KEY = "AUTH_ERROR_DOCUMENT_KEY"
        internal val TEST_ACTOR_ID = "0000000000ffff0000000000"
        internal const val TEST_USER_ID = "TEST_USER_ID"

        internal val defaultError: MutableMap<String, Code> = mutableMapOf(
            ATTACH_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            DETACH_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            REMOVE_ERROR_DOCUMENT_KEY to Code.UNAVAILABLE,
            WATCH_SYNC_ERROR_DOCUMENT_KEY to Code.UNKNOWN,
            AUTH_ERROR_DOCUMENT_KEY to Code.UNAUTHENTICATED,
        )
    }
}
