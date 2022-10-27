package dev.yorkie.api

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.movedAtOrNull
import dev.yorkie.api.v1.rGANode
import dev.yorkie.api.v1.rHTNode
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

internal typealias PBJsonElement = dev.yorkie.api.v1.JSONElement
internal typealias PBJsonElementSimple = dev.yorkie.api.v1.JSONElementSimple
internal typealias PBCounter = dev.yorkie.api.v1.JSONElement.Counter
internal typealias PBJsonArray = dev.yorkie.api.v1.JSONElement.JSONArray
internal typealias PBJsonObject = dev.yorkie.api.v1.JSONElement.JSONObject
internal typealias PBPrimitive = dev.yorkie.api.v1.JSONElement.Primitive
internal typealias PBText = dev.yorkie.api.v1.JSONElement.Text
internal typealias PBValueType = dev.yorkie.api.v1.ValueType
internal typealias PBRhtNode = dev.yorkie.api.v1.RHTNode
internal typealias PBRgaNode = dev.yorkie.api.v1.RGANode
internal typealias PBTextNodeID = dev.yorkie.api.v1.TextNodeID
internal typealias PBTextNodePos = dev.yorkie.api.v1.TextNodePos
internal typealias PBTextNode = dev.yorkie.api.v1.TextNode

// TODO(7hong13): should implement toPBTextNodeID, toPBTextNodePos, toPBTextNodes, toPBText,
//  and RgaTreeSplit related functions later.

internal fun ByteString.toCrdtObject(): CrdtObject {
    return PBJsonObject.parseFrom(this).toCrdtObject()
}

// // TODO(7hong13): should check CrdtText, CrdtRichText
internal fun CrdtElement.toByteString(): ByteString {
    return when (this) {
        is CrdtObject -> toPBJsonObject().toByteString()
        is CrdtArray -> toPBJsonArray().toByteString()
        is CrdtPrimitive -> toPBPrimitive().toByteString()
        is CrdtCounter -> toPBCounter().toByteString()
        else -> error("unimplemented element $this")
    }
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

// TODO(7hong13): should check CrdtText
internal fun CrdtElement.toPBJsonObject(): PBJsonElement {
    return when (this) {
        is CrdtObject -> toPBJsonObject()
        is CrdtArray -> toPBJsonArray()
        is CrdtPrimitive -> toPBPrimitive()
        is CrdtCounter -> toPBCounter()
        else -> error("unimplemented element: $this")
    }
}

internal fun CrdtObject.toPBJsonObject(): PBJsonElement {
    val crdtObject = this
    return jSONElement {
        PBJsonObject.newBuilder().apply {
            createdAt = crdtObject.createdAt.toPBTimeTicket()
            crdtObject.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtObject.removedAt?.let { removedAt = it.toPBTimeTicket() }
            addAllNodes(rht.toPBRhtNodes())
        }.build().also {
            jsonObject = it
        }
    }
}

internal fun List<RhtPQMap.RhtPQMapNode<CrdtElement>>.toPBRhtNodes(): List<PBRhtNode> {
    return map {
        rHTNode {
            key = it.strKey
            element = it.value.toPBJsonObject()
        }
    }
}

internal fun CrdtArray.toPBJsonArray(): PBJsonElement {
    val crdtArray = this
    return jSONElement {
        PBJsonArray.newBuilder().apply {
            createdAt = crdtArray.createdAt.toPBTimeTicket()
            crdtArray.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtArray.removedAt?.let { removedAt = it.toPBTimeTicket() }
            addAllNodes(elements.toPBRgaNodes())
        }.build().also {
            jsonArray = it
        }
    }
}

internal fun RgaTreeList.toPBRgaNodes(): List<PBRgaNode> {
    return map {
        rGANode { element = it.value.toPBJsonObject() }
    }
}

internal fun CrdtPrimitive.toPBPrimitive(): PBJsonElement {
    val crdtPrimitive = this
    return jSONElement {
        PBPrimitive.newBuilder().apply {
            type = crdtPrimitive.type.toPBValueType()
            value = crdtPrimitive.toBytes().toByteString()
            createdAt = crdtPrimitive.createdAt.toPBTimeTicket()
            crdtPrimitive.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtPrimitive.removedAt?.let { removedAt = it.toPBTimeTicket() }
        }.build().also {
            primitive = it
        }
    }
}

internal fun PrimitiveType.toPBValueType(): PBValueType {
    return when (this) {
        PrimitiveType.Null -> PBValueType.VALUE_TYPE_NULL
        PrimitiveType.Boolean -> PBValueType.VALUE_TYPE_BOOLEAN
        PrimitiveType.Integer -> PBValueType.VALUE_TYPE_INTEGER
        PrimitiveType.Long -> PBValueType.VALUE_TYPE_LONG
        PrimitiveType.Double -> PBValueType.VALUE_TYPE_DOUBLE
        PrimitiveType.String -> PBValueType.VALUE_TYPE_STRING
        PrimitiveType.Bytes -> PBValueType.VALUE_TYPE_BYTES
        PrimitiveType.Date -> PBValueType.VALUE_TYPE_DATE
    }
}

internal fun CrdtCounter.toPBCounter(): PBJsonElement {
    val crdtCounter = this
    return jSONElement {
        PBCounter.newBuilder().apply {
            type = crdtCounter.type.toPBCounterType()
            value = crdtCounter.toBytes().toByteString()
            createdAt = crdtCounter.createdAt.toPBTimeTicket()
            crdtCounter.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtCounter.removedAt?.let { removedAt = it.toPBTimeTicket() }
        }.build().also {
            counter = it
        }
    }
}

internal fun CounterType.toPBCounterType(): PBValueType {
    return when (this) {
        CounterType.IntegerCnt -> PBValueType.VALUE_TYPE_INTEGER_CNT
        CounterType.LongCnt -> PBValueType.VALUE_TYPE_LONG_CNT
        CounterType.DoubleCnt -> PBValueType.VALUE_TYPE_DOUBLE_CNT
    }
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

// TODO("check CrdtText and CrdtRichText")
internal fun CrdtElement.toPBJsonElementSimple(): PBJsonElementSimple {
    val element = this@toPBJsonElementSimple
    return jSONElementSimple {
        createdAt = element.createdAt.toPBTimeTicket()
        when (element) {
            is CrdtObject -> type = PBValueType.VALUE_TYPE_JSON_OBJECT
            is CrdtArray -> type = PBValueType.VALUE_TYPE_JSON_ARRAY
            is CrdtPrimitive -> {
                type = element.type.toPBValueType()
                value = element.toBytes().toByteString()
            }
            is CrdtCounter -> {
                type = element.type.toPBCounterType()
                value = element.toBytes().toByteString()
            }
            else -> error("unimplemented element: $element")
        }
    }
}
