package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import java.util.Date

internal class Primitive(
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

    companion object {
        private val NUMERIC_TYPES = setOf(
            PrimitiveType.Integer,
            PrimitiveType.Long,
            PrimitiveType.Double,
        )

        /**
         * Creates a new instance of Primitive.
         */
        fun of(value: Any?, createdAt: TimeTicket): Primitive {
            return Primitive(value, createdAt)
        }
    }
}

/**
 * Primitive is a CRDT element that represents a primitive value.
 */
internal enum class PrimitiveType {
    Null, Boolean, Integer, Long, Double, String, Bytes, Date
}
