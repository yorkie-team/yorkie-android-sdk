package dev.yorkie.core

import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.PBTimeTicket
import dev.yorkie.api.toByteString
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
import dev.yorkie.api.v1.PushPullRequest
import dev.yorkie.api.v1.PushPullResponse
import dev.yorkie.api.v1.UpdatePresenceRequest
import dev.yorkie.api.v1.UpdatePresenceResponse
import dev.yorkie.api.v1.ValueType
import dev.yorkie.api.v1.WatchDocumentsRequest
import dev.yorkie.api.v1.WatchDocumentsResponse
import dev.yorkie.api.v1.WatchDocumentsResponseKt.initialization
import dev.yorkie.api.v1.YorkieServiceGrpc
import dev.yorkie.api.v1.activateClientResponse
import dev.yorkie.api.v1.attachDocumentResponse
import dev.yorkie.api.v1.change
import dev.yorkie.api.v1.changePack
import dev.yorkie.api.v1.client
import dev.yorkie.api.v1.clients
import dev.yorkie.api.v1.deactivateClientResponse
import dev.yorkie.api.v1.detachDocumentResponse
import dev.yorkie.api.v1.docEvent
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.operation
import dev.yorkie.api.v1.presence
import dev.yorkie.api.v1.pushPullResponse
import dev.yorkie.api.v1.watchDocumentsResponse
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.RhtPQMap
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import java.nio.ByteBuffer

class MockYorkieService : YorkieServiceGrpc.YorkieServiceImplBase() {

    override fun activateClient(
        request: ActivateClientRequest,
        responseObserver: StreamObserver<ActivateClientResponse>,
    ) {
        responseObserver.onNext(
            activateClientResponse {
                clientId = TEST_ACTOR_ID.toByteString()
                clientKey = request.clientKey
            },
        )
        responseObserver.onCompleted()
    }

    override fun deactivateClient(
        request: DeactivateClientRequest,
        responseObserver: StreamObserver<DeactivateClientResponse>,
    ) {
        responseObserver.onNext(
            deactivateClientResponse {
                clientId = request.clientId
            },
        )
        responseObserver.onCompleted()
    }

    override fun attachDocument(
        request: AttachDocumentRequest,
        responseObserver: StreamObserver<AttachDocumentResponse>,
    ) {
        if (request.changePack.documentKey == ATTACH_ERROR_DOCUMENT_KEY) {
            responseObserver.onError(StatusException(Status.UNKNOWN))
            return
        }
        responseObserver.onNext(
            attachDocumentResponse {
                clientId = request.clientId
                changePack = changePack {
                    documentKey = request.changePack.documentKey
                    snapshot = CrdtObject(
                        InitialTimeTicket,
                        rht = RhtPQMap<CrdtElement>().apply {
                            set(
                                "k1",
                                CrdtPrimitive(4, InitialTimeTicket.copy(lamport = 1)),
                            )
                        },
                    ).toByteString()
                    minSyncedTicket = PBTimeTicket.getDefaultInstance()
                }
            },
        )
        responseObserver.onCompleted()
    }

    override fun detachDocument(
        request: DetachDocumentRequest,
        responseObserver: StreamObserver<DetachDocumentResponse>,
    ) {
        if (request.changePack.documentKey == DETACH_ERROR_DOCUMENT_KEY) {
            responseObserver.onError(StatusException(Status.UNKNOWN))
            return
        }
        responseObserver.onNext(
            detachDocumentResponse {
                clientKey = TEST_KEY
            },
        )
        responseObserver.onCompleted()
    }

    override fun pushPull(
        request: PushPullRequest,
        responseObserver: StreamObserver<PushPullResponse>,
    ) {
        if (request.changePack.documentKey == WATCH_SYNC_ERROR_DOCUMENT_KEY) {
            responseObserver.onError(StatusException(Status.UNAVAILABLE))
            return
        }
        responseObserver.onNext(
            pushPullResponse {
                clientId = request.clientId
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
        )
        responseObserver.onCompleted()
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

    override fun watchDocuments(
        request: WatchDocumentsRequest,
        responseObserver: StreamObserver<WatchDocumentsResponse>,
    ) {
        if (request.documentKeysList.contains(WATCH_SYNC_ERROR_DOCUMENT_KEY)) {
            responseObserver.onError(StatusException(Status.UNAVAILABLE))
            return
        }
        responseObserver.onNext(
            watchDocumentsResponse {
                when {
                    request.documentKeysList.size == 1 &&
                        request.documentKeysList.contains(SLOW_INITIALIZATION_DOCUMENT_KEY) -> {
                        Thread.sleep(5_000)
                        initialization = initialization {
                            peersMapByDoc[SLOW_INITIALIZATION_DOCUMENT_KEY] = clients {
                                clients.add(
                                    client {
                                        id = request.client.id
                                        presence = presence {
                                            clock = 1
                                            data["k1"] = "v1"
                                        }
                                    },
                                )
                            }
                        }
                    }
                    request.documentKeysList.contains(INITIALIZATION_DOCUMENT_KEY) -> {
                        initialization = initialization {
                            peersMapByDoc[INITIALIZATION_DOCUMENT_KEY] = clients {
                                clients.add(
                                    client {
                                        id = request.client.id
                                        presence = presence {
                                            clock = 1
                                            data["k1"] = "v1"
                                        }
                                    },
                                )
                            }
                            if (request.documentKeysList.contains(SLOW_INITIALIZATION_DOCUMENT_KEY)) {
                                peersMapByDoc[SLOW_INITIALIZATION_DOCUMENT_KEY] = clients {
                                    clients.add(
                                        client {
                                            id = request.client.id
                                            presence = presence {
                                                clock = 1
                                                data["k1"] = "v1"
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        event = docEvent {
                            type = DocEventType.DOC_EVENT_TYPE_DOCUMENTS_CHANGED
                            publisher = request.client
                            documentKeys.addAll(request.documentKeysList)
                        }
                    }
                }
            },
        )
        responseObserver.onCompleted()
    }

    override fun updatePresence(
        request: UpdatePresenceRequest,
        responseObserver: StreamObserver<UpdatePresenceResponse>,
    ) {
        if (request.documentKeysList.contains(UPDATE_PRESENCE_ERROR_DOCUMENT_KEY)) {
            responseObserver.onError(StatusException(Status.UNAVAILABLE))
            return
        }
        responseObserver.onNext(UpdatePresenceResponse.getDefaultInstance())
        responseObserver.onCompleted()
    }

    companion object {
        internal const val TEST_KEY = "TEST"
        internal const val NORMAL_DOCUMENT_KEY = "NORMAL_DOCUMENT_KEY"
        internal const val WATCH_SYNC_ERROR_DOCUMENT_KEY = "WATCH_SYNC_ERROR_DOCUMENT_KEY"
        internal const val ATTACH_ERROR_DOCUMENT_KEY = "ATTACH_ERROR_DOCUMENT_KEY"
        internal const val DETACH_ERROR_DOCUMENT_KEY = "DETACH_ERROR_DOCUMENT_KEY"
        internal const val UPDATE_PRESENCE_ERROR_DOCUMENT_KEY = "UPDATE_PRESENCE_ERROR_DOCUMENT_KEY"
        internal const val INITIALIZATION_DOCUMENT_KEY = "INITIALIZATION_DOCUMENT_KEY"
        internal const val SLOW_INITIALIZATION_DOCUMENT_KEY = "SLOW_INITIALIZATION_DOCUMENT_KEY"
        internal val TEST_ACTOR_ID = ActorID("0000000000FFFF0000000000")
    }
}
