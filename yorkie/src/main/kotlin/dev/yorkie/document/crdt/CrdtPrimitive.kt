package dev.yorkie.document.crdt

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import com.google.protobuf.kotlin.toByteStringUtf8
import dev.yorkie.document.time.TimeTicket
import java.nio.ByteBuffer
import java.util.Date

@Suppress("DataClassPrivateConstructor")
internal data class CrdtPrimitive private constructor(
    private val _value: Any?,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtElement() {
    val value: Any? = _value.sanitized()

    val type = when (value) {
        is Boolean -> PrimitiveType.Boolean
        is Int -> PrimitiveType.Integer
        is Long -> PrimitiveType.Long
        is Double -> PrimitiveType.Double
        is String -> PrimitiveType.String
        is ByteString -> PrimitiveType.Bytes
        is Date -> PrimitiveType.Date
        else -> PrimitiveType.Null
    }

    val isNumericType = type in NUMERIC_TYPES

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtElement {
        return when (value) {
            is ByteString -> {
                copy(_value = value.toByteArray().toByteString())
            }
            is Date -> {
                copy(_value = value.clone())
            }
            else -> copy()
        }
    }

    fun toBytes(): ByteString {
        return when (type) {
            PrimitiveType.Null -> ByteString.EMPTY
            PrimitiveType.Boolean -> byteArrayOf(if (value == true) 1 else 0).toByteString()
            PrimitiveType.Integer -> {
                ByteBuffer.allocate(Int.SIZE_BYTES)
                    .putInt(value as Int)
                    .array()
                    .toByteString()
            }
            PrimitiveType.Long -> {
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .putLong(value as Long)
                    .array()
                    .toByteString()
            }
            PrimitiveType.Double -> {
                ByteBuffer.allocate(Double.SIZE_BYTES)
                    .putDouble(value as Double)
                    .array()
                    .toByteString()
            }
            PrimitiveType.String -> (value as String).toByteStringUtf8()
            PrimitiveType.Bytes -> value as ByteString
            PrimitiveType.Date -> {
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .putLong((value as Date).time)
                    .array()
                    .toByteString()
            }
        }
    }

    companion object {
        private val NUMERIC_TYPES = setOf(
            PrimitiveType.Integer,
            PrimitiveType.Long,
            PrimitiveType.Double,
        )

        operator fun invoke(
            value: Any?,
            createdAt: TimeTicket,
            _movedAt: TimeTicket? = null,
            _removedAt: TimeTicket? = null,
        ) = CrdtPrimitive(value.sanitized(), createdAt, _movedAt, _removedAt)

        private fun Any?.sanitized(): Any? = when (this) {
            is Boolean -> this
            is Byte -> toInt()
            is Short -> toInt()
            is Int -> this
            is Long -> this
            is Number -> toDouble()
            is CharSequence -> toString()
            is ByteArray -> toByteString()
            is ByteString -> this
            is Date -> this
            else -> null
        }

        fun fromBytes(type: PrimitiveType, bytes: ByteString): Any? {
            fun ByteString.asByteBuffer() = ByteBuffer.wrap(toByteArray())

            return when (type) {
                PrimitiveType.Null -> null
                PrimitiveType.Boolean -> bytes.first().toInt() == 1
                PrimitiveType.Integer -> bytes.asByteBuffer().int
                PrimitiveType.Long -> bytes.asByteBuffer().long
                PrimitiveType.Double -> bytes.asByteBuffer().double
                PrimitiveType.String -> bytes.toStringUtf8()
                PrimitiveType.Bytes -> bytes
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
