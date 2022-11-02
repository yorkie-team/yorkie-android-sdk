package dev.yorkie.document.json

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.PrimitiveType
import java.util.Date

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
                        PrimitiveType.String -> """"${escapeString(value as String)}""""
                        PrimitiveType.Bytes -> """"${(value as ByteArray).decodeToString()}""""
                        PrimitiveType.Date -> (value as Date).time.toString()
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
                    buffer.append(""""$key":""")
                    value.toJsonStringInternal(buffer)
                    if (key != last().first) {
                        buffer.append(",")
                    }
                }
                buffer.append("}")
            }
        }
    }
}
