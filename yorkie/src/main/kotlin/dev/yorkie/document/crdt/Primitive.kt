package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

internal class Primitive(
    val value: Any?,
    val createdAtTime: TimeTicket,
) : CrdtElement(createdAtTime) {
    var type: PrimitiveType? = null

    init {
        type = when (value) {
            is Boolean -> PrimitiveType.Boolean
            is Int -> PrimitiveType.Integer
            is Long -> PrimitiveType.Long
            is Double -> PrimitiveType.Double
            is String -> PrimitiveType.String
            is ByteArray -> PrimitiveType.Bytes
            // TODO(daeyounglnc): support Date type
            else -> PrimitiveType.Null
        }
    }

    companion object {
        /**
         * Creates a new instance of Primitive.
         */
        fun of(value: Any?, createdAt: TimeTicket): Primitive {
            return Primitive(value, createdAt)
        }
    }

    // TODO(7hong13): implement toJson, toSortedJson, deepCopy
    override fun toJson(): String {
        return ""
    }

    override fun toSortedJson(): String {
        return ""
    }

    override fun deepCopy(): CrdtElement {
        return Primitive(1, TimeTicket.InitialTimeTicket)
    }
}

/**
 * Primitive is a CRDT element that represents a primitive value.
 */
enum class PrimitiveType {
    Null, Boolean, Integer, Long, Double, String, Bytes, Date
}
