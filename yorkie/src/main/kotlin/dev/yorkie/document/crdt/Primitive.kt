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

    override fun toJson(): String {
        return TODO("To be implemented when it's actually needed")
    }

    override fun toSortedJson(): String {
        return toJson()
    }

    override fun deepCopy(): CrdtElement {
        return of(value, createdAt).apply {
            movedAt = this.movedAt
        }
    }
}

/**
 * Primitive is a CRDT element that represents a primitive value.
 */
enum class PrimitiveType {
    Null, Boolean, Integer, Long, Double, String, Bytes, Date
}
