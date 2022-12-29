package dev.yorkie.api

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.v1.JSONElementKt.counter
import dev.yorkie.api.v1.JSONElementKt.jSONArray
import dev.yorkie.api.v1.JSONElementKt.jSONObject
import dev.yorkie.api.v1.JSONElementKt.primitive
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.movedAtOrNull
import dev.yorkie.api.v1.rGANode
import dev.yorkie.api.v1.rHTNode
import dev.yorkie.api.v1.removedAtOrNull
import dev.yorkie.api.v1.textNode
import dev.yorkie.api.v1.textNodeID
import dev.yorkie.api.v1.textNodePos
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtCounter.Companion.asCounterValue
import dev.yorkie.document.crdt.CrdtCounter.CounterType
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNode
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
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
    return PBJsonElement.parseFrom(this).jsonObject.toCrdtObject()
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
    return CrdtObject(
        createdAt = createdAt.toTimeTicket(),
        _movedAt = movedAtOrNull?.toTimeTicket(),
        _removedAt = removedAtOrNull?.toTimeTicket(),
        rht = rht,
    )
}

internal fun PBJsonArray.toCrdtArray(): CrdtArray {
    val rgaTreeList = RgaTreeList()
    nodesList.forEach { node ->
        rgaTreeList.insert(node.element.toCrdtElement())
    }
    return CrdtArray(
        createdAt = createdAt.toTimeTicket(),
        _movedAt = movedAtOrNull?.toTimeTicket(),
        _removedAt = removedAtOrNull?.toTimeTicket(),
        elements = rgaTreeList,
    )
}

internal fun PBPrimitive.toCrdtPrimitive(): CrdtPrimitive {
    return CrdtPrimitive(
        value = CrdtPrimitive.fromBytes(type.toPrimitiveType(), value),
        createdAt = createdAt.toTimeTicket(),
        _movedAt = movedAtOrNull?.toTimeTicket(),
        _removedAt = removedAtOrNull?.toTimeTicket(),
    )
}

internal fun PBValueType.toPrimitiveType(): CrdtPrimitive.Type {
    return when (this) {
        PBValueType.VALUE_TYPE_NULL -> CrdtPrimitive.Type.Null
        PBValueType.VALUE_TYPE_BOOLEAN -> CrdtPrimitive.Type.Boolean
        PBValueType.VALUE_TYPE_INTEGER -> CrdtPrimitive.Type.Integer
        PBValueType.VALUE_TYPE_LONG -> CrdtPrimitive.Type.Long
        PBValueType.VALUE_TYPE_DOUBLE -> CrdtPrimitive.Type.Double
        PBValueType.VALUE_TYPE_STRING -> CrdtPrimitive.Type.String
        PBValueType.VALUE_TYPE_BYTES -> CrdtPrimitive.Type.Bytes
        PBValueType.VALUE_TYPE_DATE -> CrdtPrimitive.Type.Date
        else -> error("unimplemented type $this")
    }
}

internal fun PBCounter.toCrdtCounter(): CrdtCounter {
    val type = type.toCounterType()
    return if (type == CounterType.IntegerCnt) {
        CrdtCounter(
            value = value.toByteArray().asCounterValue(type).toInt(),
            createdAt = createdAt.toTimeTicket(),
            _movedAt = movedAtOrNull?.toTimeTicket(),
            _removedAt = removedAtOrNull?.toTimeTicket(),
        )
    } else {
        CrdtCounter(
            value = value.toByteArray().asCounterValue(type).toLong(),
            createdAt = createdAt.toTimeTicket(),
            _movedAt = movedAtOrNull?.toTimeTicket(),
            _removedAt = removedAtOrNull?.toTimeTicket(),
        )
    }
}

internal fun PBValueType.toCounterType(): CounterType {
    return when (this) {
        PBValueType.VALUE_TYPE_INTEGER_CNT -> CounterType.IntegerCnt
        PBValueType.VALUE_TYPE_LONG_CNT -> CounterType.LongCnt
        else -> error("unimplemented value type: $this")
    }
}

// TODO(7hong13): should check CrdtText
internal fun CrdtElement.toPBJsonElement(): PBJsonElement {
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
        jsonObject = jSONObject {
            createdAt = crdtObject.createdAt.toPBTimeTicket()
            crdtObject.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtObject.removedAt?.let { removedAt = it.toPBTimeTicket() }
            nodes.addAll(memberNodes.toPBRhtNodes())
        }
    }
}

internal fun List<RhtPQMap.Node<CrdtElement>>.toPBRhtNodes(): List<PBRhtNode> {
    return map {
        rHTNode {
            key = it.strKey
            element = it.value.toPBJsonElement()
        }
    }
}

internal fun CrdtArray.toPBJsonArray(): PBJsonElement {
    val crdtArray = this
    return jSONElement {
        jsonArray = jSONArray {
            createdAt = crdtArray.createdAt.toPBTimeTicket()
            crdtArray.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtArray.removedAt?.let { removedAt = it.toPBTimeTicket() }
            nodes.addAll(elements.toPBRgaNodes())
        }
    }
}

internal fun RgaTreeList.toPBRgaNodes(): List<PBRgaNode> {
    return map {
        rGANode { element = it.value.toPBJsonElement() }
    }
}

internal fun RgaTreeSplitNodePos.toPBTextNodePos(): PBTextNodePos {
    return textNodePos {
        createdAt = this@toPBTextNodePos.id.createdAt.toPBTimeTicket()
        offset = this@toPBTextNodePos.id.offset
        relativeOffset = this@toPBTextNodePos.relativeOffSet
    }
}

internal fun PBTextNodePos.toRgaTreeSplitNodePos(): RgaTreeSplitNodePos {
    return RgaTreeSplitNodePos(
        id = RgaTreeSplitNodeID(createdAt.toTimeTicket(), offset),
        relativeOffSet = relativeOffset,
    )
}

internal fun RgaTreeSplitNodeID.toPBTextNodeID(): PBTextNodeID {
    return textNodeID {
        createdAt = this@toPBTextNodeID.createdAt.toPBTimeTicket()
        offset = this@toPBTextNodeID.offset
    }
}

internal fun PBTextNodeID.toRgaTreeSplitNodeID(): RgaTreeSplitNodeID {
    return RgaTreeSplitNodeID(createdAt.toTimeTicket(), offset)
}

internal fun RgaTreeSplit<String>.toPBTextNodes(): List<PBTextNode> {
    return buildList {
        this@toPBTextNodes.forEach { node ->
            add(
                textNode {
                    id = node.id.toPBTextNodeID()
                    value = node.value
                    node.removedAt?.let { removedAt = it.toPBTimeTicket() }
                },
            )
        }
    }
}

internal fun PBTextNode.toRgaReeSplitNode(): RgaTreeSplitNode<String> {
    return RgaTreeSplitNode(id.toRgaTreeSplitNodeID(), value).apply {
        remove(this@toRgaReeSplitNode.removedAt.toTimeTicket())
    }
}

internal fun CrdtPrimitive.toPBPrimitive(): PBJsonElement {
    val crdtPrimitive = this
    return jSONElement {
        primitive = primitive {
            type = crdtPrimitive.type.toPBValueType()
            value = crdtPrimitive.toBytes()
            createdAt = crdtPrimitive.createdAt.toPBTimeTicket()
            crdtPrimitive.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtPrimitive.removedAt?.let { removedAt = it.toPBTimeTicket() }
        }
    }
}

internal fun CrdtPrimitive.Type.toPBValueType(): PBValueType {
    return when (this) {
        CrdtPrimitive.Type.Null -> PBValueType.VALUE_TYPE_NULL
        CrdtPrimitive.Type.Boolean -> PBValueType.VALUE_TYPE_BOOLEAN
        CrdtPrimitive.Type.Integer -> PBValueType.VALUE_TYPE_INTEGER
        CrdtPrimitive.Type.Long -> PBValueType.VALUE_TYPE_LONG
        CrdtPrimitive.Type.Double -> PBValueType.VALUE_TYPE_DOUBLE
        CrdtPrimitive.Type.String -> PBValueType.VALUE_TYPE_STRING
        CrdtPrimitive.Type.Bytes -> PBValueType.VALUE_TYPE_BYTES
        CrdtPrimitive.Type.Date -> PBValueType.VALUE_TYPE_DATE
    }
}

internal fun CrdtCounter.toPBCounter(): PBJsonElement {
    val crdtCounter = this
    return jSONElement {
        counter = counter {
            type = crdtCounter.type.toPBCounterType()
            value = crdtCounter.toBytes().toByteString()
            createdAt = crdtCounter.createdAt.toPBTimeTicket()
            crdtCounter.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtCounter.removedAt?.let { removedAt = it.toPBTimeTicket() }
        }
    }
}

internal fun CounterType.toPBCounterType(): PBValueType {
    return when (this) {
        CounterType.IntegerCnt -> PBValueType.VALUE_TYPE_INTEGER_CNT
        CounterType.LongCnt -> PBValueType.VALUE_TYPE_LONG_CNT
    }
}

internal fun PBJsonElementSimple.toCrdtElement(): CrdtElement {
    return when (type) {
        PBValueType.VALUE_TYPE_JSON_OBJECT -> CrdtObject(createdAt.toTimeTicket(), rht = RhtPQMap())
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
        PBValueType.VALUE_TYPE_INTEGER_CNT -> CrdtCounter(
            value.toByteArray().asCounterValue(type.toCounterType()).toInt(),
            createdAt.toTimeTicket(),
        )
        PBValueType.VALUE_TYPE_LONG_CNT -> CrdtCounter(
            value.toByteArray().asCounterValue(type.toCounterType()).toLong(),
            createdAt.toTimeTicket(),
        )
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
                value = element.toBytes()
            }
            is CrdtCounter -> {
                type = element.type.toPBCounterType()
                value = element.toBytes().toByteString()
            }
            else -> error("unimplemented element: $element")
        }
    }
}
