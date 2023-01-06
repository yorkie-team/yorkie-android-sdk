package dev.yorkie.document.json

import com.google.protobuf.ByteString
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
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
                        CrdtPrimitive.Type.String -> """"${escapeString(value as String)}""""
                        CrdtPrimitive.Type.Bytes -> """"${(value as ByteString).toStringUtf8()}""""
                        CrdtPrimitive.Type.Date -> (value as Date).time.toString()
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
            is CrdtText -> {
                buffer.append("[")
                rgaTreeSplit.filterNot { it.isRemoved }
                    .joinToString(",") { it.value.toJson() }
                buffer.append("]")
            }
        }
    }
}
