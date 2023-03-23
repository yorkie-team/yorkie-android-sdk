package dev.yorkie.core

import dev.yorkie.BuildConfig
import io.grpc.ClientInterceptor
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils

internal val UserAgentInterceptor = MetadataUtils.newAttachHeadersInterceptor(
    Metadata().apply {
        put("x-yorkie-user-agent".asMetadataKey(), "yorkie-android-sdk/${BuildConfig.VERSION_NAME}")
    },
)

internal fun Client.Options.authInterceptor(): ClientInterceptor? {
    if (apiKey == null && token == null) {
        return null
    }
    val metadata = Metadata().apply {
        if (apiKey != null) {
            put("x-api-key".asMetadataKey(), apiKey)
        }
        if (token != null) {
            put("authorization".asMetadataKey(), token)
        }
    }
    return MetadataUtils.newAttachHeadersInterceptor(metadata)
}

private fun String.asMetadataKey() = Metadata.Key.of(this, Metadata.ASCII_STRING_MARSHALLER)
