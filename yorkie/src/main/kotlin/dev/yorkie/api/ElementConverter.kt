package dev.yorkie.api

import com.google.protobuf.ByteString
import dev.yorkie.api.v1.movedAtOrNull
import dev.yorkie.api.v1.removedAtOrNull
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtCounter.CounterType
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.PrimitiveType
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.crdt.RhtPQMap

typealias PBJsonElement = dev.yorkie.api.v1.JSONElement
typealias PBJsonElementSimple = dev.yorkie.api.v1.JSONElementSimple
typealias PBCounter = dev.yorkie.api.v1.JSONElement.Counter
typealias PBJsonArray = dev.yorkie.api.v1.JSONElement.JSONArray
typealias PBJsonObject = dev.yorkie.api.v1.JSONElement.JSONObject
typealias PBPrimitive = dev.yorkie.api.v1.JSONElement.Primitive
typealias PBValueType = dev.yorkie.api.v1.ValueType

internal fun ByteString.toCrdtObject(): CrdtObject {
    return PBJsonObject.parseFrom(this).toCrdtObject()
}

internal fun CrdtElement.toByteString(): ByteString {
    return toPBJsonElement().toByteString()
}

internal fun PBJsonElement.toCrdtElement(): CrdtElement {
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

internal fun PBJsonObject.toCrdtObject(): CrdtObject {
    val rht = RhtPQMap<CrdtElement>()
    nodesList.forEach { node ->
        rht[node.key] = node.element.toCrdtElement()
    }
    return CrdtObject(createdAt.toTimeTicket(), rht).also {
        it.movedAt = movedAtOrNull?.toTimeTicket()
        it.removedAt = removedAtOrNull?.toTimeTicket()
    }
}

internal fun PBJsonArray.toCrdtArray(): CrdtArray {
    val rgaTreeList = RgaTreeList()
    nodesList.forEach { node ->
        rgaTreeList.insert(node.element.toCrdtElement())
    }
    return CrdtArray(createdAt.toTimeTicket(), rgaTreeList).also {
        it.movedAt = movedAtOrNull?.toTimeTicket()
        it.removedAt = removedAtOrNull?.toTimeTicket()
    }
}

internal fun PBPrimitive.toCrdtPrimitive(): CrdtPrimitive {
    return CrdtPrimitive(
        CrdtPrimitive.fromBytes(type.toPrimitiveType(), value),
        createdAt.toTimeTicket(),
    ).also {
        it.movedAt = movedAtOrNull?.toTimeTicket()
        it.removedAt = removedAtOrNull?.toTimeTicket()
    }
}

internal fun PBValueType.toPrimitiveType(): PrimitiveType {
    return when (this) {
        PBValueType.VALUE_TYPE_NULL -> PrimitiveType.Null
        PBValueType.VALUE_TYPE_BOOLEAN -> PrimitiveType.Boolean
        PBValueType.VALUE_TYPE_INTEGER -> PrimitiveType.Integer
        PBValueType.VALUE_TYPE_LONG -> PrimitiveType.Long
        PBValueType.VALUE_TYPE_DOUBLE -> PrimitiveType.Double
        PBValueType.VALUE_TYPE_STRING -> PrimitiveType.String
        PBValueType.VALUE_TYPE_BYTES -> PrimitiveType.Bytes
        PBValueType.VALUE_TYPE_DATE -> PrimitiveType.Date
        else -> error("unimplemented type $this")
    }
}

internal fun PBCounter.toCrdtCounter(): CrdtCounter {
    TODO("implement after valueFromBytes function")
}

internal fun PBValueType.toCounterType(): CounterType {
    return when (this) {
        PBValueType.VALUE_TYPE_INTEGER_CNT -> CounterType.IntegerCnt
        PBValueType.VALUE_TYPE_LONG_CNT -> CounterType.LongCnt
        PBValueType.VALUE_TYPE_DOUBLE_CNT -> CounterType.DoubleCnt
        else -> error("unimplemented value type: $this")
    }
}

internal fun CrdtElement.toPBJsonElement(): PBJsonElement {
    TODO("")
}

internal fun PBJsonElementSimple.toCrdtElement(): CrdtElement {
    return when (type) {
        PBValueType.VALUE_TYPE_JSON_OBJECT -> CrdtObject(createdAt.toTimeTicket(), RhtPQMap())
        PBValueType.VALUE_TYPE_JSON_ARRAY -> CrdtArray(createdAt.toTimeTicket())
        PBValueType.VALUE_TYPE_TEXT -> TODO("not yet implemented")
        PBValueType.VALUE_TYPE_RICH_TEXT -> TODO("not yet implemented")
        PBValueType.VALUE_TYPE_NULL,
        PBValueType.VALUE_TYPE_BOOLEAN,
        PBValueType.VALUE_TYPE_INTEGER,
        PBValueType.VALUE_TYPE_LONG,
        PBValueType.VALUE_TYPE_DOUBLE,
        PBValueType.VALUE_TYPE_STRING,
        PBValueType.VALUE_TYPE_BYTES,
        PBValueType.VALUE_TYPE_DATE,
        -> CrdtPrimitive(
            CrdtPrimitive.fromBytes(type.toPrimitiveType(), value),
            createdAt.toTimeTicket(),
        )
        PBValueType.VALUE_TYPE_INTEGER_CNT,
        PBValueType.VALUE_TYPE_DOUBLE_CNT,
        PBValueType.VALUE_TYPE_LONG_CNT,
        -> TODO("implement after valueFromBytes function")
        else -> error("unimplemented type $this")
    }
}
