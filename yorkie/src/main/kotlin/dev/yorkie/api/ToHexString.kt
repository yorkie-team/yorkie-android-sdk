package dev.yorkie.api

import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString

internal fun ByteString.toHexString(): String {
    return BaseEncoding.base16().lowerCase().encode(toByteArray())
}
