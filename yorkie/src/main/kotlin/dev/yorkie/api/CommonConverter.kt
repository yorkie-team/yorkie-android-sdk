package dev.yorkie.api

import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString

internal fun ByteString.toHexString(): String {
    return BaseEncoding.base16().lowerCase().encode(toByteArray())
}

internal fun String.toDecodedByteString(): ByteString {
    return BaseEncoding.base16().lowerCase().decode(this).toByteString()
}
