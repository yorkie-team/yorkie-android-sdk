package dev.yorkie.api

import android.util.Base64
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.document.time.ActorID

internal fun ByteString.toActorID(): ActorID {
    return ActorID(bytesToHex(toByteArray()))
}

internal fun ActorID.toByteString(): ByteString {
    return hexToBytes(value.lowercase()).toByteString()
}

/**
 * Converts a Base64 string to ByteArray.
 */
fun base64ToByteArray(base64: String): ByteArray {
    return Base64.decode(base64, Base64.DEFAULT)
}

/**
 * Converts a hex string to ByteArray.
 */
fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(hex.length / 2) { i ->
        ((hex.substring(2 * i, 2 * i + 2)).toInt(16)).toByte()
    }
}

/**
 * `uint8ArrayToBase64` converts the given Uint8Array to base64 string.
 */
fun uint8ArrayToBase64(byteArray: ByteArray): String {
    return Base64.encodeToString(byteArray, Base64.NO_WRAP)
}

/**
 * Converts a ByteArray to hex string.
 */
fun bytesToHex(bytes: ByteArray?): String {
    if (bytes == null) return ""
    return bytes.joinToString("") { "%02x".format(it) }
}
