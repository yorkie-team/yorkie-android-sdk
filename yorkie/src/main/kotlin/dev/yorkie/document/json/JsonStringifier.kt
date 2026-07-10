package dev.yorkie.document.json

import com.google.protobuf.ByteString
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object JsonStringifier {

    fun CrdtElement.toJsonString(): String {
        val buffer = StringBuilder()
        toJsonStringInternal(buffer)
        return buffer.toString()
    }

    private fun CrdtElement.toJsonStringInternal(buffer: StringBuilder) {
        when (this) {
            is CrdtPrimitive -> {
                buffer.append(
                    when (type) {
                        CrdtPrimitive.Type.String -> """"${escapeString(value as String)}""""
                        CrdtPrimitive.Type.Bytes ->
                            """"${encodeBase64((value as ByteString).toByteArray())}""""
                        CrdtPrimitive.Type.Date ->
                            """"${isoDateFormat().format(value as Date)}""""
                        else -> "$value"
                    },
                )
            }

            is CrdtCounter -> {
                buffer.append("$value")
            }

            is CrdtArray -> {
                buffer.append("[")
                forEach {
                    it.toJsonStringInternal(buffer)
                    if (it != last()) {
                        buffer.append(",")
                    }
                }
                buffer.append("]")
            }

            is CrdtObject -> {
                buffer.append("{")
                forEach { (key, value) ->
                    buffer.append(""""${escapeString(key)}":""")
                    value.toJsonStringInternal(buffer)
                    if (key != last().first) {
                        buffer.append(",")
                    }
                }
                buffer.append("}")
            }

            is CrdtText -> {
                buffer.append(
                    "[${
                        rgaTreeSplit.filterNot { it.isRemoved }
                            .joinToString(",") { it.value.toJson() }
                    }]",
                )
            }

            is CrdtTree -> {
                buffer.append(rootTreeNode)
            }
        }
    }

    /**
     * Returns an ISO 8601 formatter (UTC, millisecond precision) matching the JS SDK's
     * `Date.toISOString()`. A fresh instance is returned per call because [SimpleDateFormat]
     * is not thread-safe.
     */
    private fun isoDateFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /**
     * Encodes the given bytes as a standard, padded Base64 string, matching the JS SDK's
     * `btoa(...)`. Implemented in pure Kotlin so it works in JVM unit tests and on every
     * supported Android API level ([android.util.Base64] is unavailable off-device and
     * [java.util.Base64] requires API 26 while the SDK targets API 24).
     */
    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            builder.append(alphabet[triple shr 18 and 0x3F])
            builder.append(alphabet[triple shr 12 and 0x3F])
            builder.append(if (i + 1 < bytes.size) alphabet[triple shr 6 and 0x3F] else '=')
            builder.append(if (i + 2 < bytes.size) alphabet[triple and 0x3F] else '=')
            i += 3
        }
        return builder.toString()
    }
}
