package dev.yorkie.core

import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.PBTimeTicket
import dev.yorkie.api.toPBChange
import dev.yorkie.api.toPBTimeTicket
import dev.yorkie.api.v1.ActivateClientRequest
import dev.yorkie.api.v1.ActivateClientResponse
import dev.yorkie.api.v1.AttachDocumentRequest
import dev.yorkie.api.v1.AttachDocumentResponse
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
import dev.yorkie.api.v1.YorkieServiceGrpcKt
import dev.yorkie.api.v1.activateClientResponse
import dev.yorkie.api.v1.attachDocumentResponse
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
import io.grpc.Status
import io.grpc.StatusException
import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockYorkieService : YorkieServiceGrpcKt.YorkieServiceCoroutineImplBase() {

    override suspend fun activateClient(request: ActivateClientRequest): ActivateClientResponse {
        return activateClientResponse {
            clientId = TEST_ACTOR_ID.value
        }
    }

    override suspend fun deactivateClient(
        request: DeactivateClientRequest,
    ): DeactivateClientResponse {
        return deactivateClientResponse { }
    }

    override suspend fun attachDocument(request: AttachDocumentRequest): AttachDocumentResponse {
        if (request.changePack.documentKey == ATTACH_ERROR_DOCUMENT_KEY) {
            throw StatusException(Status.UNKNOWN)
        }
        return attachDocumentResponse {
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
        }
    }

    override suspend fun detachDocument(request: DetachDocumentRequest): DetachDocumentResponse {
        if (request.changePack.documentKey == DETACH_ERROR_DOCUMENT_KEY) {
            throw StatusException(Status.UNKNOWN)
        }
        return detachDocumentResponse { }
    }

    override suspend fun pushPullChanges(request: PushPullChangesRequest): PushPullChangesResponse {
        if (request.changePack.documentKey == WATCH_SYNC_ERROR_DOCUMENT_KEY) {
            throw StatusException(Status.UNAVAILABLE)
        }
        return pushPullChangesResponse {
            changePack = changePack {
                minSyncedTicket = InitialTimeTicket.toPBTimeTicket()
                changes.add(
                    change {
                        operations.add(createSetOperation())
                        operations.add(createRemoveOperation())
                    },
                )
            }
        }
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

    override fun watchDocument(request: WatchDocumentRequest): Flow<WatchDocumentResponse> {
        val key = request.documentId
        return flow {
            emit(
                watchDocumentResponse {
                    initialization = initialization {
                        clientIds.add(TEST_ACTOR_ID.value)
                    }
                },
            )
            delay(1_000)
            if (key == WATCH_SYNC_ERROR_DOCUMENT_KEY) {
                throw StatusException(Status.UNAVAILABLE)
            }
            emit(
                watchDocumentResponse {
                    event = docEvent {
                        type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED
                        publisher = request.clientId
                    }
                },
            )
            delay(1_000)
            emit(
                watchDocumentResponse {
                    event = docEvent {
                        type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_WATCHED
                        publisher = request.clientId
                    }
                },
            )
            delay(2_000)
            emit(
                watchDocumentResponse {
                    event = docEvent {
                        type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_UNWATCHED
                        publisher = request.clientId
                    }
                },
            )
        }
    }

    override suspend fun removeDocument(request: RemoveDocumentRequest): RemoveDocumentResponse {
        if (request.documentId == REMOVE_ERROR_DOCUMENT_KEY) {
            throw StatusException(Status.UNAVAILABLE)
        }
        return removeDocumentResponse {
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
        }
    }

    companion object {
        internal const val TEST_KEY = "TEST"
        internal const val NORMAL_DOCUMENT_KEY = "NORMAL_DOCUMENT_KEY"
        internal const val WATCH_SYNC_ERROR_DOCUMENT_KEY = "WATCH_SYNC_ERROR_DOCUMENT_KEY"
        internal const val ATTACH_ERROR_DOCUMENT_KEY = "ATTACH_ERROR_DOCUMENT_KEY"
        internal const val DETACH_ERROR_DOCUMENT_KEY = "DETACH_ERROR_DOCUMENT_KEY"
        internal const val REMOVE_ERROR_DOCUMENT_KEY = "REMOVE_ERROR_DOCUMENT_KEY"
        internal val TEST_ACTOR_ID = ActorID("0000000000ffff0000000000")
    }
}
