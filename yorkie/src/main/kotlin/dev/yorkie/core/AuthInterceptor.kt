package dev.yorkie.core

import io.grpc.ClientInterceptor
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils

internal fun Client.Options.authInterceptor(): ClientInterceptor? {
    if (apiKey == null && token == null) {
        return null
    }
    val metadata = Metadata().apply {
        put("x-api-key".asMetadataKey(), apiKey)
        put("authorization".asMetadataKey(), token)
    }
    return MetadataUtils.newAttachHeadersInterceptor(metadata)
}

private fun String.asMetadataKey() = Metadata.Key.of(this, Metadata.ASCII_STRING_MARSHALLER)
