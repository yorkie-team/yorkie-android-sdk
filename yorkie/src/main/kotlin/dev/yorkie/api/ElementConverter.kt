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
typealias PBText = dev.yorkie.api.v1.JSONElement.Text
typealias PBValueType = dev.yorkie.api.v1.ValueType
typealias PBRhtNode = dev.yorkie.api.v1.RHTNode
typealias PBRgaNode = dev.yorkie.api.v1.RGANode
typealias PBTextNodeID = dev.yorkie.api.v1.TextNodeID
typealias PBTextNodePos = dev.yorkie.api.v1.TextNodePos
typealias PBTextNode = dev.yorkie.api.v1.TextNode

// TODO(7hong13): should implement toPBTextNodeID, toPBTextNodePos, toPBTextNodes, toPBText,
//  and RgaTreeSplit related functions later.

internal fun ByteString.toCrdtObject(): CrdtObject {
    return PBJsonObject.parseFrom(this).toCrdtObject()
}

internal fun CrdtElement.toByteString(): ByteString {
    return toPBJsonObject().toByteString()
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
    val pbObject = PBJsonObject.newBuilder().apply {
        createdAt = this@toPBJsonObject.createdAt.toPBTimeTicket()
        movedAt = this@toPBJsonObject.movedAt?.toPBTimeTicket()
        removedAt = this@toPBJsonObject.removedAt?.toPBTimeTicket()
        rht.toPBRhtNodes().forEachIndexed { index, rhtNode ->
            setNodes(index, rhtNode)
        }
    }.build()
    return PBJsonElement.newBuilder().apply { jsonObject = pbObject }.build()
}

internal fun CrdtArray.toPBJsonArray(): PBJsonElement {
    val pbArray = PBJsonArray.newBuilder().apply {
        createdAt = this@toPBJsonArray.createdAt.toPBTimeTicket()
        movedAt = this@toPBJsonArray.movedAt?.toPBTimeTicket()
        removedAt = this@toPBJsonArray.removedAt?.toPBTimeTicket()
        elements.toPBRgaNodes().forEachIndexed { index, rgaNode ->
            setNodes(index, rgaNode)
        }
    }.build()
    return PBJsonElement.newBuilder().apply { jsonArray = pbArray }.build()
}

internal fun CrdtPrimitive.toPBPrimitive(): PBJsonElement {
    val pbPrimitive = PBPrimitive.newBuilder().apply {
        type = this@toPBPrimitive.type.toPBValueType()
        value = this@toPBPrimitive.toByteString()
        createdAt = this@toPBPrimitive.createdAt.toPBTimeTicket()
        movedAt = this@toPBPrimitive.movedAt?.toPBTimeTicket()
        removedAt = this@toPBPrimitive.removedAt?.toPBTimeTicket()
    }.build()
    return PBJsonElement.newBuilder().apply { primitive = pbPrimitive }.build()
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
    val pbCounter = PBCounter.newBuilder().apply {
        type = this@toPBCounter.type.toPBCounterType()
        value = this@toPBCounter.toByteString()
        createdAt = this@toPBCounter.createdAt.toPBTimeTicket()
        movedAt = this@toPBCounter.movedAt?.toPBTimeTicket()
        removedAt = this@toPBCounter.removedAt?.toPBTimeTicket()
    }.build()
    return PBJsonElement.newBuilder().apply { counter = pbCounter }.build()
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
    return PBJsonElementSimple.newBuilder().apply {
        createdAt = this@toPBJsonElementSimple.createdAt.toPBTimeTicket()
        when (val element = this@toPBJsonElementSimple) {
            is CrdtObject -> type = PBValueType.VALUE_TYPE_JSON_OBJECT
            is CrdtArray -> type = PBValueType.VALUE_TYPE_JSON_ARRAY
            is CrdtPrimitive -> {
                type = element.type.toPBValueType()
                value = element.toByteString()
            }
            is CrdtCounter -> {
                type = element.type.toPBCounterType()
                value = element.toByteString()
            }
            else -> error("unimplemented element: $element")
        }
    }.build()
}

internal fun List<RhtPQMap.RhtPQMapNode<CrdtElement>>.toPBRhtNodes(): List<PBRhtNode> {
    return map {
        PBRhtNode.newBuilder().apply {
            key = it.strKey
            element = it.value.toPBJsonObject()
        }.build()
    }
}

internal fun RgaTreeList.toPBRgaNodes(): List<PBRgaNode> {
    return map {
        PBRgaNode.newBuilder().apply {
            element = it.value.toPBJsonObject()
        }.build()
    }
}
