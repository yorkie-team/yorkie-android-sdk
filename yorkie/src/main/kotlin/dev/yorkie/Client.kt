package dev.yorkie

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import dev.yorkie.api.v1.yorkie.ActivateClientRequest
import dev.yorkie.api.v1.yorkie.DeactivateClientRequest
import dev.yorkie.api.v1.yorkie.YorkieServiceGrpcKt
import io.grpc.CallOptions
import io.grpc.android.AndroidChannelBuilder
import java.util.UUID

public class Client(
    context: Context,
    private val rpcAddress: String,
    private val usePlainText: Boolean,
) {
    private val applicationContext = context.applicationContext

    private val service by lazy {
        YorkieServiceGrpcKt.YorkieServiceCoroutineStub(
            AndroidChannelBuilder.forTarget(rpcAddress)
                .run { if (usePlainText) usePlaintext() else this }
                .context(applicationContext)
                .build(),
            callOptions = CallOptions.DEFAULT,
        )
    }

    private var clientStatus: ClientStatus = ClientStatus.Deactivated

    /**
     * activates this client. That is, it register itself to the server
     * and receives a unique ID from the server. The given ID is used to
     * distinguish different clients.
     */
    public suspend fun activate() {
        val activateResponse = service.activateClient(
            ActivateClientRequest.newBuilder()
                .setClientKey(UUID.randomUUID().toString())
                .build(),
        )
        clientStatus = ClientStatus.Activated(
            activateResponse.clientId,
            activateResponse.clientKey,
        )
        Log.d("yorkie", "client activated with key: ${activateResponse.clientKey}")
    }

    /**
     * deactivates this client.
     */
    public suspend fun deactivate() {
        val deactivateResponse = service.deactivateClient(
            DeactivateClientRequest.newBuilder()
                .setClientId(requireClientId())
                .build(),
        )
        clientStatus = ClientStatus.Deactivated
        Log.d("yorkie", "client deactivated ${deactivateResponse.clientId}")
    }

    private fun requireClientId() = (clientStatus as ClientStatus.Activated).clientId

    public sealed class ClientStatus {
        public class Activated(
            public val clientId: ByteString,
            public val clientKey: String,
        ) : ClientStatus()

        public object Deactivated : ClientStatus()
    }
}
