package dev.yorkie.api

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.v1.JSONElementKt.counter
import dev.yorkie.api.v1.JSONElementKt.jSONArray
import dev.yorkie.api.v1.JSONElementKt.jSONObject
import dev.yorkie.api.v1.JSONElementKt.primitive
import dev.yorkie.api.v1.JSONElementKt.text
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.movedAtOrNull
import dev.yorkie.api.v1.rGANode
import dev.yorkie.api.v1.rHTNode
import dev.yorkie.api.v1.removedAtOrNull
import dev.yorkie.api.v1.textNode
import dev.yorkie.api.v1.textNodeAttr
import dev.yorkie.api.v1.textNodeID
import dev.yorkie.api.v1.textNodePos
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtCounter.Companion.asCounterValue
import dev.yorkie.document.crdt.CrdtCounter.CounterType
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNode
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.crdt.RhtPQMap
import dev.yorkie.document.crdt.TextValue

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

internal fun ByteString.toCrdtObject(): CrdtObject {
    return PBJsonElement.parseFrom(this).jsonObject.toCrdtObject()
}

internal fun CrdtElement.toByteString(): ByteString {
    return when (this) {
        is CrdtObject -> toPBJsonObject().toByteString()
        is CrdtArray -> toPBJsonArray().toByteString()
        is CrdtText -> toPBText().toByteString()
        is CrdtPrimitive -> toPBPrimitive().toByteString()
        is CrdtCounter -> toPBCounter().toByteString()
        else -> throw IllegalArgumentException("unimplemented element $this")
    }
}

internal fun PBJsonElement.toCrdtElement(): CrdtElement {
    return when {
        hasJsonObject() -> jsonObject.toCrdtObject()
        hasJsonArray() -> jsonArray.toCrdtArray()
        hasText() -> text.toCrdtText()
        hasPrimitive() -> primitive.toCrdtPrimitive()
        hasCounter() -> counter.toCrdtCounter()
        else -> throw IllegalArgumentException("unimplemented element: $this")
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

private fun PBJsonArray.toCrdtArray(): CrdtArray {
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

internal fun PBText.toCrdtText(): CrdtText {
    val rgaTreeSplit = RgaTreeSplit<TextValue>()

    var prev = rgaTreeSplit.head
    nodesList.forEach { node ->
        val current = rgaTreeSplit.insertAfter(prev, node.toRgaTreeSplitNode())
        if (node.hasInsPrevId()) {
            current.setInsertionPrev(rgaTreeSplit.findNode(node.insPrevId.toRgaTreeSplitNodeID()))
        }
        prev = current
    }
    return CrdtText(
        rgaTreeSplit = rgaTreeSplit,
        createdAt = createdAt.toTimeTicket(),
        _removedAt = removedAtOrNull?.toTimeTicket(),
        _movedAt = movedAtOrNull?.toTimeTicket(),
    )
}

private fun PBPrimitive.toCrdtPrimitive(): CrdtPrimitive {
    return CrdtPrimitive(
        value = CrdtPrimitive.fromBytes(type.toPrimitiveType(), value),
        createdAt = createdAt.toTimeTicket(),
        _movedAt = movedAtOrNull?.toTimeTicket(),
        _removedAt = removedAtOrNull?.toTimeTicket(),
    )
}

private fun PBValueType.toPrimitiveType(): CrdtPrimitive.Type {
    return when (this) {
        PBValueType.VALUE_TYPE_NULL -> CrdtPrimitive.Type.Null
        PBValueType.VALUE_TYPE_BOOLEAN -> CrdtPrimitive.Type.Boolean
        PBValueType.VALUE_TYPE_INTEGER -> CrdtPrimitive.Type.Integer
        PBValueType.VALUE_TYPE_LONG -> CrdtPrimitive.Type.Long
        PBValueType.VALUE_TYPE_DOUBLE -> CrdtPrimitive.Type.Double
        PBValueType.VALUE_TYPE_STRING -> CrdtPrimitive.Type.String
        PBValueType.VALUE_TYPE_BYTES -> CrdtPrimitive.Type.Bytes
        PBValueType.VALUE_TYPE_DATE -> CrdtPrimitive.Type.Date
        else -> throw IllegalArgumentException("unimplemented type $this")
    }
}

private fun PBCounter.toCrdtCounter(): CrdtCounter {
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

private fun PBValueType.toCounterType(): CounterType {
    return when (this) {
        PBValueType.VALUE_TYPE_INTEGER_CNT -> CounterType.IntegerCnt
        PBValueType.VALUE_TYPE_LONG_CNT -> CounterType.LongCnt
        else -> throw IllegalArgumentException("unimplemented value type: $this")
    }
}

internal fun CrdtElement.toPBJsonElement(): PBJsonElement {
    return when (this) {
        is CrdtObject -> toPBJsonObject()
        is CrdtArray -> toPBJsonArray()
        is CrdtText -> toPBText()
        is CrdtPrimitive -> toPBPrimitive()
        is CrdtCounter -> toPBCounter()
        else -> throw IllegalArgumentException("unimplemented element: $this")
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

private fun RgaTreeList.toPBRgaNodes(): List<PBRgaNode> {
    return map {
        rGANode { element = it.value.toPBJsonElement() }
    }
}

internal fun CrdtText.toPBText(): PBJsonElement {
    val crdtText = this
    return jSONElement {
        text = text {
            createdAt = crdtText.createdAt.toPBTimeTicket()
            crdtText.movedAt?.let { movedAt = it.toPBTimeTicket() }
            crdtText.removedAt?.let { removedAt = it.toPBTimeTicket() }
            nodes.addAll(rgaTreeSplit.toPBTextNodes())
        }
    }
}

private fun RgaTreeSplit<TextValue>.toPBTextNodes(): List<PBTextNode> {
    return this@toPBTextNodes.map { node ->
        textNode {
            id = node.id.toPBTextNodeID()
            value = node.value.content
            node.removedAt?.let { removedAt = it.toPBTimeTicket() } ?: clearRemovedAt()
            node.value.attributesWithTimeTicket.forEach {
                attributes[it.key] = textNodeAttr {
                    key = it.key
                    value = it.value
                    updatedAt = it.executedAt.toPBTimeTicket()
                }
            }
        }
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

private fun RgaTreeSplitNodeID.toPBTextNodeID(): PBTextNodeID {
    return textNodeID {
        createdAt = this@toPBTextNodeID.createdAt.toPBTimeTicket()
        offset = this@toPBTextNodeID.offset
    }
}

private fun PBTextNodeID.toRgaTreeSplitNodeID(): RgaTreeSplitNodeID {
    return RgaTreeSplitNodeID(createdAt.toTimeTicket(), offset)
}

private fun PBTextNode.toRgaTreeSplitNode(): RgaTreeSplitNode<TextValue> {
    val textValue = TextValue(value).apply {
        attributesMap.forEach { (_, value) ->
            setAttribute(value.key, value.value, value.updatedAt.toTimeTicket())
        }
    }
    return RgaTreeSplitNode(id.toRgaTreeSplitNodeID(), textValue).apply {
        remove(this@toRgaTreeSplitNode.removedAtOrNull?.toTimeTicket())
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

private fun CrdtPrimitive.Type.toPBValueType(): PBValueType {
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

private fun CounterType.toPBCounterType(): PBValueType {
    return when (this) {
        CounterType.IntegerCnt -> PBValueType.VALUE_TYPE_INTEGER_CNT
        CounterType.LongCnt -> PBValueType.VALUE_TYPE_LONG_CNT
    }
}

internal fun PBJsonElementSimple.toCrdtElement(): CrdtElement {
    return when (type) {
        PBValueType.VALUE_TYPE_JSON_OBJECT -> CrdtObject(createdAt.toTimeTicket(), rht = RhtPQMap())
        PBValueType.VALUE_TYPE_JSON_ARRAY -> CrdtArray(createdAt.toTimeTicket())
        PBValueType.VALUE_TYPE_TEXT -> CrdtText(RgaTreeSplit(), createdAt.toTimeTicket())
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
        else -> throw IllegalArgumentException("unimplemented type $this")
    }
}

internal fun CrdtElement.toPBJsonElementSimple(): PBJsonElementSimple {
    val element = this@toPBJsonElementSimple
    return jSONElementSimple {
        createdAt = element.createdAt.toPBTimeTicket()
        when (element) {
            is CrdtObject -> type = PBValueType.VALUE_TYPE_JSON_OBJECT
            is CrdtArray -> type = PBValueType.VALUE_TYPE_JSON_ARRAY
            is CrdtText -> {
                type = PBValueType.VALUE_TYPE_TEXT
                createdAt = element.createdAt.toPBTimeTicket()
            }
            is CrdtPrimitive -> {
                type = element.type.toPBValueType()
                value = element.toBytes()
            }
            is CrdtCounter -> {
                type = element.type.toPBCounterType()
                value = element.toBytes().toByteString()
            }
            else -> throw IllegalArgumentException("unimplemented element: $element")
        }
    }
}
