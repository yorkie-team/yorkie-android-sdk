package dev.yorkie.api

import com.google.protobuf.ByteString
import dev.yorkie.api.v1.JSONElement
import dev.yorkie.api.v1.JSONElement.Counter
import dev.yorkie.api.v1.JSONElement.JSONArray
import dev.yorkie.api.v1.JSONElement.JSONObject
import dev.yorkie.api.v1.JSONElement.Primitive
import dev.yorkie.api.v1.ValueType
import dev.yorkie.api.v1.movedAtOrNull
import dev.yorkie.api.v1.removedAtOrNull
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.PrimitiveType
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.crdt.RhtPQMap

internal fun ByteString.toCrdtObject(): CrdtObject {
    return JSONObject.parseFrom(this).toCrdtObject()
}

internal fun CrdtElement.toByteString(): ByteString {
    return toJSONElement().toByteString()
}

internal fun JSONElement.toCrdtElement(): CrdtElement {
    return when {
        hasJsonObject() -> jsonObject.toCrdtObject()
        hasJsonArray() -> jsonArray.toCrdtArray()
        hasPrimitive() -> primitive.toCrdtPrimitive()
        hasText() -> TODO("not yet implemented")
        hasRichText() -> TODO("not yet implemented")
        hasCounter() -> counter.toCrdtCounter()
        else -> error("unimplemented element: $this")
    }
}

internal fun JSONObject.toCrdtObject(): CrdtObject {
    val rht = RhtPQMap<CrdtElement>()
    nodesList.forEach { node ->
        rht[node.key] = node.element.toCrdtElement()
    }
    return CrdtObject(createdAt.toTimeTicket(), rht).also {
        it.movedAt = movedAtOrNull?.toTimeTicket()
        it.removedAt = removedAtOrNull?.toTimeTicket()
    }
}

internal fun JSONArray.toCrdtArray(): CrdtArray {
    val rgaTreeList = RgaTreeList()
    nodesList.forEach { node ->
        rgaTreeList.insert(node.element.toCrdtElement())
    }
    return CrdtArray(createdAt.toTimeTicket(), rgaTreeList).also {
        it.movedAt = movedAtOrNull?.toTimeTicket()
        it.removedAt = removedAtOrNull?.toTimeTicket()
    }
}

internal fun Primitive.toCrdtPrimitive(): CrdtPrimitive {
    return CrdtPrimitive(
        CrdtPrimitive.fromBytes(type.toPrimitiveType(), value),
        createdAt.toTimeTicket(),
    ).also {
        it.movedAt = movedAtOrNull?.toTimeTicket()
        it.removedAt = removedAtOrNull?.toTimeTicket()
    }
}

internal fun ValueType.toPrimitiveType(): PrimitiveType {
    return when (this) {
        ValueType.VALUE_TYPE_NULL -> PrimitiveType.Null
        ValueType.VALUE_TYPE_BOOLEAN -> PrimitiveType.Boolean
        ValueType.VALUE_TYPE_INTEGER -> PrimitiveType.Integer
        ValueType.VALUE_TYPE_LONG -> PrimitiveType.Long
        ValueType.VALUE_TYPE_DOUBLE -> PrimitiveType.Double
        ValueType.VALUE_TYPE_STRING -> PrimitiveType.String
        ValueType.VALUE_TYPE_BYTES -> PrimitiveType.Bytes
        ValueType.VALUE_TYPE_DATE -> PrimitiveType.Date
        else -> error("unimplemented type $this")
    }
}

internal fun Counter.toCrdtCounter(): CrdtCounter {
    TODO()
}

internal fun CrdtElement.toJSONElement(): JSONElement {
    TODO("")
}
