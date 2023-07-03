package dev.yorkie.api

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.v1.JSONElementKt.counter
import dev.yorkie.api.v1.JSONElementKt.jSONArray
import dev.yorkie.api.v1.JSONElementKt.jSONObject
import dev.yorkie.api.v1.JSONElementKt.primitive
import dev.yorkie.api.v1.JSONElementKt.text
import dev.yorkie.api.v1.JSONElementKt.tree
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.movedAtOrNull
import dev.yorkie.api.v1.nodeAttr
import dev.yorkie.api.v1.rGANode
import dev.yorkie.api.v1.rHTNode
import dev.yorkie.api.v1.removedAtOrNull
import dev.yorkie.api.v1.textNode
import dev.yorkie.api.v1.textNodeID
import dev.yorkie.api.v1.textNodePos
import dev.yorkie.api.v1.treeNode
import dev.yorkie.api.v1.treePos
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtCounter.Companion.asCounterValue
import dev.yorkie.document.crdt.CrdtCounter.CounterType
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNode
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.crdt.Rht
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode
import dev.yorkie.util.traverse

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
internal typealias PBTree = dev.yorkie.api.v1.JSONElement.Tree
internal typealias PBTreeNode = dev.yorkie.api.v1.TreeNode
internal typealias PBTreePos = dev.yorkie.api.v1.TreePos

internal fun ByteString.toCrdtObject(): CrdtObject {
    return PBJsonElement.parseFrom(this).jsonObject.toCrdtObject()
}

internal fun ByteString.toCrdtTree(): CrdtTree {
    return PBJsonElement.parseFrom(this).tree.toCrdtTree()
}

internal fun CrdtElement.toByteString(): ByteString {
    return when (this) {
        is CrdtObject -> toPBJsonObject()
        is CrdtArray -> toPBJsonArray()
        is CrdtText -> toPBText()
        is CrdtPrimitive -> toPBPrimitive()
        is CrdtCounter -> toPBCounter()
        is CrdtTree -> toPBTree()
        else -> throw IllegalArgumentException("unimplemented element $this")
    }.toByteString()
}

internal fun PBJsonElement.toCrdtElement(): CrdtElement {
    return when {
        hasJsonObject() -> jsonObject.toCrdtObject()
        hasJsonArray() -> jsonArray.toCrdtArray()
        hasText() -> text.toCrdtText()
        hasPrimitive() -> primitive.toCrdtPrimitive()
        hasCounter() -> counter.toCrdtCounter()
        hasTree() -> tree.toCrdtTree()
        else -> throw IllegalArgumentException("unimplemented element: $this")
    }
}

internal fun PBJsonObject.toCrdtObject(): CrdtObject {
    val rht = ElementRht<CrdtElement>()
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

internal fun PBTree.toCrdtTree(): CrdtTree {
    val root = checkNotNull(nodesList.toCrdtTreeRootNode())
    return CrdtTree(
        root,
        createdAt.toTimeTicket(),
        movedAtOrNull?.toTimeTicket(),
        removedAtOrNull?.toTimeTicket(),
    )
}

internal fun List<PBTreeNode>.toCrdtTreeRootNode(): CrdtTreeNode? {
    if (isEmpty()) {
        return null
    }
    val nodes = map { it.toCrdtTreeNode() }
    val root = nodes.first()
    for (i in nodes.size - 2 downTo 0) {
        val parentIndex = ((i + 1)..nodes.lastIndex).find {
            this[i].depth - 1 == this[it].depth
        } ?: continue
        nodes[parentIndex].prepend(nodes[i])
    }
    return CrdtTree(root, TimeTicket.InitialTimeTicket).root
}

internal fun PBTreeNode.toCrdtTreeNode(): CrdtTreeNode {
    val pos = pos.toCrdtTreePos()
    return CrdtTreeNode(
        pos,
        type,
        _value = value.takeIf { type == IndexTreeNode.DEFAULT_TEXT_TYPE },
        _attributes = Rht().also {
            attributesMap.forEach { (key, value) ->
                it.set(key, value.value, value.updatedAt.toTimeTicket())
            }
        },
    )
}

internal fun PBTreePos.toCrdtTreePos(): CrdtTreePos = CrdtTreePos(createdAt.toTimeTicket(), offset)

internal fun CrdtElement.toPBJsonElement(): PBJsonElement {
    return when (this) {
        is CrdtObject -> toPBJsonObject()
        is CrdtArray -> toPBJsonArray()
        is CrdtText -> toPBText()
        is CrdtPrimitive -> toPBPrimitive()
        is CrdtCounter -> toPBCounter()
        is CrdtTree -> toPBTree()
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

internal fun List<ElementRht.Node<CrdtElement>>.toPBRhtNodes(): List<PBRhtNode> {
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

private fun CrdtTreeNode.toPBTreeNodes(): List<PBTreeNode> {
    return buildList {
        traverse(this@toPBTreeNodes) { node, nodeDepth ->
            val pbTreeNode = treeNode {
                pos = node.pos.toPBTreePos()
                type = node.type
                if (node.isText) {
                    value = node.value
                }
                node.removedAt?.toPBTimeTicket()?.let {
                    removedAt = it
                }
                depth = nodeDepth
                attributes.putAll(
                    node.rhtNodes.associate {
                        it.key to nodeAttr {
                            value = it.value
                            updatedAt = it.executedAt.toPBTimeTicket()
                        }
                    },
                )
            }
            add(pbTreeNode)
        }
    }
}

private fun CrdtTreePos.toPBTreePos(): PBTreePos {
    val crdtTreePos = this
    return treePos {
        createdAt = crdtTreePos.createdAt.toPBTimeTicket()
        offset = crdtTreePos.offset
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
                attributes[it.key] = nodeAttr {
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
        attributesMap.forEach { (key, attr) ->
            setAttribute(key, attr.value, attr.updatedAt.toTimeTicket())
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

internal fun CrdtTree.toPBTree(): PBJsonElement {
    val crdtTree = this
    return jSONElement {
        tree = tree {
            nodes.addAll(crdtTree.root.toPBTreeNodes())
            createdAt = crdtTree.createdAt.toPBTimeTicket()
            crdtTree.movedAt?.toPBTimeTicket()?.let {
                movedAt = it
            }
            crdtTree.removedAt?.toPBTimeTicket()?.let {
                removedAt = it
            }
        }
    }
}

internal fun PBJsonElementSimple.toCrdtElement(): CrdtElement {
    return when (type) {
        PBValueType.VALUE_TYPE_JSON_OBJECT -> CrdtObject(
            createdAt.toTimeTicket(),
            rht = ElementRht(),
        )

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

        PBValueType.VALUE_TYPE_TREE -> value.toCrdtTree()

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
            is CrdtText -> type = PBValueType.VALUE_TYPE_TEXT
            is CrdtPrimitive -> {
                type = element.type.toPBValueType()
                value = element.toBytes()
            }

            is CrdtCounter -> {
                type = element.type.toPBCounterType()
                value = element.toBytes().toByteString()
            }

            is CrdtTree -> {
                type = PBValueType.VALUE_TYPE_TREE
                value = element.toPBTree().toByteString()
            }

            else -> throw IllegalArgumentException("unimplemented element: $element")
        }
    }
}
