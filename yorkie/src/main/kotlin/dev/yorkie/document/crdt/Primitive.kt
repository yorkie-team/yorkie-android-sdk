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
            // TODO support Date type
            else -> PrimitiveType.Null
        }
    }

}

/**
 * Primitive is a CRDT element that represents a primitive value.
 */
enum class PrimitiveType {
    Null, Boolean, Integer, Long, Double, String, Bytes, Date
}
