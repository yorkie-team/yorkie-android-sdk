package dev.yorkie.document.crdt

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import com.google.protobuf.kotlin.toByteStringUtf8
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date

/**
 * [CrdtPrimitive] presents a value of primitive type. Only values of type
 * included in [CrdtPrimitive] can be set to the document.
 */
@Suppress("DataClassPrivateConstructor")
internal data class CrdtPrimitive private constructor(
    private val _value: Any?,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtElement() {
    val value: Any? = _value.sanitized()

    val type = when (value) {
        is Boolean -> Type.Boolean
        is Int -> Type.Integer
        is Long -> Type.Long
        is Double -> Type.Double
        is String -> Type.String
        is ByteString -> Type.Bytes
        is Date -> Type.Date
        else -> Type.Null
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

    override fun getDataSize(): DataSize = DataSize(
        data = getValueSize(),
        meta = getMetaUsage(),
    )

    fun toBytes(): ByteString {
        return when (type) {
            Type.Null -> ByteString.EMPTY
            Type.Boolean -> byteArrayOf(if (value == true) 1 else 0).toByteString()
            Type.Integer -> {
                ByteBuffer.allocate(Int.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value as Int)
                    .array()
                    .toByteString()
            }

            Type.Long -> {
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value as Long)
                    .array()
                    .toByteString()
            }

            Type.Double -> {
                ByteBuffer.allocate(Double.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putDouble(value as Double)
                    .array()
                    .toByteString()
            }

            Type.String -> (value as String).toByteStringUtf8()
            Type.Bytes -> value as ByteString
            Type.Date -> {
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong((value as Date).time)
                    .array()
                    .toByteString()
            }
        }
    }

    /**
     * `getValueSize` returns the size of the value. The size is similar to
     * the size of primitives in JavaScript.
     */
    private fun getValueSize(): Int {
        return when (type) {
            Type.Null, Type.Long, Type.Double, Type.Date -> {
                8
            }

            Type.Boolean, Type.Integer -> {
                4
            }

            Type.String -> {
                (value as String).length * 2
            }

            Type.Bytes -> {
                (value as ByteString).size()
            }
        }
    }

    companion object {
        private val NUMERIC_TYPES = setOf(
            Type.Integer,
            Type.Long,
            Type.Double,
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

        fun fromBytes(type: Type, bytes: ByteString): Any? {
            fun ByteString.asByteBuffer() = ByteBuffer.wrap(
                toByteArray(),
            ).order(
                ByteOrder.LITTLE_ENDIAN,
            )

            return when (type) {
                Type.Null -> null
                Type.Boolean -> bytes.first().toInt() == 1
                Type.Integer -> bytes.asByteBuffer().int
                Type.Long -> bytes.asByteBuffer().long
                Type.Double -> bytes.asByteBuffer().double
                Type.String -> bytes.toStringUtf8()
                Type.Bytes -> bytes
                Type.Date -> Date(bytes.asByteBuffer().long)
            }
        }
    }

    internal enum class Type {
        Null,
        Boolean,
        Integer,
        Long,
        Double,
        String,
        Bytes,
        Date,
    }
}
