package dev.yorkie.document.crdt

import com.google.protobuf.ByteString
import dev.yorkie.document.json.escapeString
import dev.yorkie.document.time.TimeTicket
import java.nio.ByteBuffer
import java.util.Date

internal class CrdtPrimitive(
    val value: Any?,
    createdAtTime: TimeTicket,
) : CrdtElement(createdAtTime) {
    val type = when (value) {
        is Boolean -> PrimitiveType.Boolean
        is Int -> PrimitiveType.Integer
        is Long -> PrimitiveType.Long
        is Double -> PrimitiveType.Double
        is String -> PrimitiveType.String
        is ByteArray -> PrimitiveType.Bytes
        is Date -> PrimitiveType.Date
        else -> PrimitiveType.Null
    }

    val isNumericType = type in NUMERIC_TYPES

    /**
     * Returns the JSON encoding of this object.
     */
    override fun toJson(): String {
        return when (type) {
            PrimitiveType.String -> """"${escapeString(value as String)}""""
            PrimitiveType.Bytes -> """"${(value as ByteArray).decodeToString()}""""
            PrimitiveType.Date -> (value as Date).time.toString()
            else -> "$value"
        }
    }

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtElement {
        return CrdtPrimitive(value, createdAt).apply {
            movedAt = this.movedAt
        }
    }

    fun toBytes(): ByteArray {
        return when (type) {
            PrimitiveType.Null -> byteArrayOf()
            PrimitiveType.Boolean -> byteArrayOf(if (value == true) 1 else 0)
            PrimitiveType.Integer -> {
                ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value as Int).array()
            }
            PrimitiveType.Long -> {
                ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value as Long).array()
            }
            PrimitiveType.Double -> {
                ByteBuffer.allocate(Double.SIZE_BYTES).putDouble(value as Double).array()
            }
            PrimitiveType.String -> (value as String).toByteArray()
            PrimitiveType.Bytes -> value as ByteArray
            PrimitiveType.Date -> {
                ByteBuffer.allocate(Long.SIZE_BYTES).putLong((value as Date).time).array()
            }
        }
    }

    companion object {
        private val NUMERIC_TYPES = setOf(
            PrimitiveType.Integer,
            PrimitiveType.Long,
            PrimitiveType.Double,
        )

        fun fromBytes(type: PrimitiveType, bytes: ByteString): Any? {
            fun ByteString.asByteBuffer() = ByteBuffer.wrap(toByteArray())

            return when (type) {
                PrimitiveType.Null -> null
                PrimitiveType.Boolean -> bytes.first().toInt() == 1
                PrimitiveType.Integer -> bytes.asByteBuffer().int
                PrimitiveType.Long -> bytes.asByteBuffer().long
                PrimitiveType.Double -> bytes.asByteBuffer().double
                PrimitiveType.String -> bytes.toStringUtf8()
                PrimitiveType.Bytes -> bytes.toByteArray()
                PrimitiveType.Date -> Date(bytes.asByteBuffer().long)
            }
        }
    }
}

/**
 * Primitive is a CRDT element that represents a primitive value.
 */
internal enum class PrimitiveType {
    Null, Boolean, Integer, Long, Double, String, Bytes, Date
}
