package dev.yorkie.api

import dev.yorkie.api.v1.OperationKt.add
import dev.yorkie.api.v1.OperationKt.arraySet
import dev.yorkie.api.v1.OperationKt.edit
import dev.yorkie.api.v1.OperationKt.increase
import dev.yorkie.api.v1.OperationKt.move
import dev.yorkie.api.v1.OperationKt.remove
import dev.yorkie.api.v1.OperationKt.set
import dev.yorkie.api.v1.OperationKt.style
import dev.yorkie.api.v1.OperationKt.treeEdit
import dev.yorkie.api.v1.OperationKt.treeStyle
import dev.yorkie.api.v1.operation
import dev.yorkie.document.crdt.RestoreSpan
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.RestoreMode
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import dev.yorkie.util.YorkieException.Code.ErrUnimplemented
import dev.yorkie.api.v1.RestoreMode as PbRestoreMode
import dev.yorkie.api.v1.RestoreSpan as PbRestoreSpan
import dev.yorkie.api.v1.TreeRestoreSpan as PbTreeRestoreSpan
import dev.yorkie.api.v1.restoreSpan as pbRestoreSpan
import dev.yorkie.api.v1.treeRestoreSpan as pbTreeRestoreSpan

internal typealias PBOperation = dev.yorkie.api.v1.Operation

internal fun List<PBOperation>.toOperations(): List<Operation> {
    return mapNotNull {
        when {
            it.hasSet() -> SetOperation(
                key = it.set.key,
                value = it.set.value.toCrdtElement(),
                parentCreatedAt = it.set.parentCreatedAt.toTimeTicket(),
                executedAt = it.set.executedAt.toTimeTicket(),
            )

            it.hasAdd() -> AddOperation(
                parentCreatedAt = it.add.parentCreatedAt.toTimeTicket(),
                prevCreatedAt = it.add.prevCreatedAt.toTimeTicket(),
                value = it.add.value.toCrdtElement(),
                executedAt = it.add.executedAt.toTimeTicket(),
            )

            it.hasMove() -> MoveOperation(
                parentCreatedAt = it.move.parentCreatedAt.toTimeTicket(),
                prevCreatedAt = it.move.prevCreatedAt.toTimeTicket(),
                createdAt = it.move.createdAt.toTimeTicket(),
                executedAt = it.move.executedAt.toTimeTicket(),
            )

            it.hasRemove() -> RemoveOperation(
                parentCreatedAt = it.remove.parentCreatedAt.toTimeTicket(),
                createdAt = it.remove.createdAt.toTimeTicket(),
                executedAt = it.remove.executedAt.toTimeTicket(),
            )

            it.hasIncrease() -> IncreaseOperation(
                parentCreatedAt = it.increase.parentCreatedAt.toTimeTicket(),
                executedAt = it.increase.executedAt.toTimeTicket(),
                value = it.increase.value.toCrdtElement(),
                actor = it.increase.actor,
            )

            it.hasEdit() -> {
                val executedAt = it.edit.executedAt.toTimeTicket()
                val hasRestorePayload = it.edit.restoreSpansList.isNotEmpty() ||
                    it.edit.retombstoneSpansList.isNotEmpty()
                val restoreSpans = it.edit.restoreSpansList.takeIf { hasRestorePayload }
                    ?.map { span -> span.toRestoreSpan(executedAt) }
                val retombstoneSpans = it.edit.retombstoneSpansList.takeIf { hasRestorePayload }
                    ?.map { span -> span.toRestoreSpan(executedAt) }
                val restoreMode = if (hasRestorePayload) {
                    if (it.edit.restoreMode == PbRestoreMode.RESTORE_MODE_RETOMBSTONE) {
                        RestoreMode.Retombstone
                    } else {
                        RestoreMode.Restore
                    }
                } else {
                    null
                }
                EditOperation(
                    fromPos = it.edit.from.toRgaTreeSplitNodePos(),
                    toPos = it.edit.to.toRgaTreeSplitNodePos(),
                    parentCreatedAt = it.edit.parentCreatedAt.toTimeTicket(),
                    executedAt = executedAt,
                    content = it.edit.content,
                    attributes = it.edit.attributesMap.takeUnless { attrs -> attrs.isEmpty() }
                        ?: mapOf(),
                    // undoFromOffset/undoToOffset stay at their NOT_AN_UNDO_OP
                    // default: a decoded remote restore op applies by
                    // identity and is not reconciled locally.
                    restoreSpans = restoreSpans,
                    restoreMode = restoreMode,
                    retombstoneSpans = retombstoneSpans,
                )
            }

            it.hasStyle() -> StyleOperation(
                fromPos = it.style.from.toRgaTreeSplitNodePos(),
                toPos = it.style.to.toRgaTreeSplitNodePos(),
                attributes = it.style.attributesMap,
                parentCreatedAt = it.style.parentCreatedAt.toTimeTicket(),
                executedAt = it.style.executedAt.toTimeTicket(),
                attributesToRemove = it.style.attributesToRemoveList,
            )

            it.hasTreeEdit() -> {
                val hasRestorePayload = it.treeEdit.restoreSpansList.isNotEmpty() ||
                    it.treeEdit.retombstoneSpansList.isNotEmpty()
                val treeRestoreSpans = it.treeEdit.restoreSpansList.takeIf { hasRestorePayload }
                    ?.map(PbTreeRestoreSpan::toTreeRestoreSpan)
                val treeRetombstoneSpans = it.treeEdit.retombstoneSpansList
                    .takeIf { hasRestorePayload }
                    ?.map(PbTreeRestoreSpan::toTreeRestoreSpan)
                val treeRestoreMode = if (hasRestorePayload) {
                    if (it.treeEdit.restoreMode == PbRestoreMode.RESTORE_MODE_RETOMBSTONE) {
                        RestoreMode.Retombstone
                    } else {
                        RestoreMode.Restore
                    }
                } else {
                    null
                }
                TreeEditOperation(
                    parentCreatedAt = it.treeEdit.parentCreatedAt.toTimeTicket(),
                    fromPos = it.treeEdit.from.toCrdtTreePos(),
                    toPos = it.treeEdit.to.toCrdtTreePos(),
                    contents = it.treeEdit.contentsList.toCrdtTreeNodesWhenEdit(),
                    executedAt = it.treeEdit.executedAt.toTimeTicket(),
                    splitLevel = it.treeEdit.splitLevel,
                    // undoFromOffset/undoToOffset stay at their NotAnUndoOp
                    // default: a decoded remote restore op applies by
                    // identity and is not reconciled locally (mirrors the
                    // Text edit inbound path above).
                    restoreSpans = treeRestoreSpans,
                    restoreMode = treeRestoreMode,
                    retombstoneSpans = treeRetombstoneSpans,
                )
            }

            it.hasTreeStyle() -> TreeStyleOperation(
                parentCreatedAt = it.treeStyle.parentCreatedAt.toTimeTicket(),
                fromPos = it.treeStyle.from.toCrdtTreePos(),
                toPos = it.treeStyle.to.toCrdtTreePos(),
                attributes = it.treeStyle.attributesMap,
                executedAt = it.treeStyle.executedAt.toTimeTicket(),
                attributesToRemove = it.treeStyle.attributesToRemoveList,
            )

            it.hasArraySet() -> ArraySetOperation(
                createdAt = it.arraySet.createdAt.toTimeTicket(),
                value = it.arraySet.value.toCrdtElement(),
                parentCreatedAt = it.arraySet.parentCreatedAt.toTimeTicket(),
                executedAt = it.arraySet.executedAt.toTimeTicket(),
            )

            else -> throw YorkieException(ErrUnimplemented, "unimplemented operations")
        }
    }
}

internal fun Operation.toPBOperation(): PBOperation {
    return when (val operation = this@toPBOperation) {
        is SetOperation -> {
            operation {
                set = set {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    key = operation.key
                    value = operation.value.toPBJsonElementSimple()
                    executedAt = operation.executedAt.toPBTimeTicket()
                }
            }
        }

        is AddOperation -> {
            operation {
                add = add {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    prevCreatedAt = operation.prevCreatedAt.toPBTimeTicket()
                    value = operation.value.toPBJsonElementSimple()
                    executedAt = operation.executedAt.toPBTimeTicket()
                }
            }
        }

        is MoveOperation -> {
            operation {
                move = move {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    prevCreatedAt = operation.prevCreatedAt.toPBTimeTicket()
                    createdAt = operation.createdAt.toPBTimeTicket()
                    executedAt = operation.executedAt.toPBTimeTicket()
                }
            }
        }

        is RemoveOperation -> {
            operation {
                remove = remove {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    createdAt = operation.createdAt.toPBTimeTicket()
                    executedAt = operation.executedAt.toPBTimeTicket()
                }
            }
        }

        is IncreaseOperation -> {
            operation {
                increase = increase {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    value = operation.value.toPBJsonElementSimple()
                    executedAt = operation.executedAt.toPBTimeTicket()
                    actor = operation.actor
                }
            }
        }

        is EditOperation -> {
            operation {
                edit = edit {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    from = operation.fromPos.toPBTextNodePos()
                    to = operation.toPos.toPBTextNodePos()
                    content = operation.content
                    executedAt = operation.executedAt.toPBTimeTicket()
                    operation.attributes.forEach { attributes[it.key] = it.value }
                    // Ordinary edits set none of these — the wire payload stays
                    // byte-identical to before this field was added.
                    if (operation.restoreSpans != null || operation.retombstoneSpans != null) {
                        restoreSpans.addAll(operation.restoreSpans.orEmpty().map { it.toPbSpan() })
                        retombstoneSpans.addAll(
                            operation.retombstoneSpans.orEmpty().map { it.toPbSpan() },
                        )
                        restoreMode = if (operation.restoreMode == RestoreMode.Retombstone) {
                            PbRestoreMode.RESTORE_MODE_RETOMBSTONE
                        } else {
                            PbRestoreMode.RESTORE_MODE_RESTORE
                        }
                    }
                }
            }
        }

        is StyleOperation -> {
            operation {
                style = style {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    from = operation.fromPos.toPBTextNodePos()
                    to = operation.toPos.toPBTextNodePos()
                    executedAt = operation.executedAt.toPBTimeTicket()
                    operation.attributes.forEach { attributes[it.key] = it.value }
                    operation.attributesToRemove.forEach { attributesToRemove.add(it) }
                }
            }
        }

        is TreeEditOperation -> {
            operation {
                treeEdit = treeEdit {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    from = operation.fromPos.toPBTreePos()
                    to = operation.toPos.toPBTreePos()
                    executedAt = operation.executedAt.toPBTimeTicket()
                    contents.addAll(operation.contents?.toPBTreeNodesWhenEdit().orEmpty())
                    splitLevel = operation.splitLevel
                    // Ordinary tree edits set none of these — the wire payload
                    // stays byte-identical to before this field was added.
                    if (operation.restoreSpans != null || operation.retombstoneSpans != null) {
                        restoreSpans.addAll(
                            operation.restoreSpans.orEmpty().map { it.toPbTreeSpan() },
                        )
                        retombstoneSpans.addAll(
                            operation.retombstoneSpans.orEmpty().map { it.toPbTreeSpan() },
                        )
                        restoreMode = if (operation.restoreMode == RestoreMode.Retombstone) {
                            PbRestoreMode.RESTORE_MODE_RETOMBSTONE
                        } else {
                            PbRestoreMode.RESTORE_MODE_RESTORE
                        }
                    }
                }
            }
        }

        is TreeStyleOperation -> {
            operation {
                treeStyle = treeStyle {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    from = operation.fromPos.toPBTreePos()
                    to = operation.toPos.toPBTreePos()
                    executedAt = operation.executedAt.toPBTimeTicket()
                    operation.attributes?.forEach { (key, value) ->
                        attributes[key] = value
                    }
                    operation.attributesToRemove?.forEach { attributesToRemove.add(it) }
                }
            }
        }

        is ArraySetOperation -> {
            operation {
                arraySet = arraySet {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    createdAt = operation.createdAt.toPBTimeTicket()
                    value = operation.value.toPBJsonElementSimple()
                    executedAt = operation.executedAt.toPBTimeTicket()
                }
            }
        }

        else -> throw YorkieException(ErrUnimplemented, "unimplemented operation : $operation")
    }
}

internal fun List<Operation>.toPBOperations(): List<PBOperation> = map(Operation::toPBOperation)

/**
 * Converts a domain [RestoreSpan] to its protobuf representation.
 */
private fun RestoreSpan<TextValue>.toPbSpan(): PbRestoreSpan {
    val span = this
    return pbRestoreSpan {
        createdAt = span.createdAt.toPBTimeTicket()
        start = span.start
        end = span.end
        content = span.value.content
        span.value.attributes.forEach { attributes[it.key] = it.value }
    }
}

/**
 * Converts a protobuf [PbRestoreSpan] to its domain representation. Restores
 * the span's attributes onto the decoded [TextValue] via [executedAt] — the
 * decoded value's own attribute-RHT tickets are otherwise unobservable
 * (identity is carried by the span's [PbRestoreSpan.createdAt]/offsets, not
 * by the value's internal attribute tickets).
 */
private fun PbRestoreSpan.toRestoreSpan(executedAt: TimeTicket): RestoreSpan<TextValue> {
    val value = TextValue(content).apply {
        attributesMap.forEach { (key, attrValue) -> setAttribute(key, attrValue, executedAt) }
    }
    return RestoreSpan(createdAt.toTimeTicket(), start, end, value)
}

/**
 * Converts a domain [TreeRestoreSpan] to its protobuf representation.
 * Anchor ids ([TreeRestoreSpan.parentID]/[TreeRestoreSpan.leftSiblingID]/
 * [TreeRestoreSpan.rightSiblingID]) are set only when present, and [value]
 * only when the span is a text span — an ordinary tree edit never
 * populates this message at all (see the `is TreeEditOperation` branch
 * above), so [value]'s absence here is never observed as a malformed span.
 */
private fun TreeRestoreSpan.toPbTreeSpan(): PbTreeRestoreSpan {
    val span = this
    return pbTreeRestoreSpan {
        id = span.id.toPBTreeNodeID()
        nodeType = span.nodeType
        isText = span.isText
        length = span.length
        span.value?.let { value = it }
        span.attrs?.let { attributes.putAll(it.toPBRht()) }
        span.parentID?.let { parentId = it.toPBTreeNodeID() }
        span.leftSiblingID?.let { leftSiblingId = it.toPBTreeNodeID() }
        span.rightSiblingID?.let { rightSiblingId = it.toPBTreeNodeID() }
    }
}

/**
 * Converts a protobuf [PbTreeRestoreSpan] to its domain representation.
 * A span addresses content by insertion identity, so every node id it
 * carries is malformed without a `created_at`, and the attribute snapshot
 * is malformed without an `updated_at` per entry — both are rejected here,
 * at the decode boundary, rather than letting an undefined-equivalent
 * timestamp fail deep inside [dev.yorkie.document.crdt.CrdtTree.restore].
 */
private fun PbTreeRestoreSpan.toTreeRestoreSpan(): TreeRestoreSpan {
    val anchors = listOf(
        hasParentId() to parentId,
        hasLeftSiblingId() to leftSiblingId,
        hasRightSiblingId() to rightSiblingId,
    )
    val malformed = !hasId() || !id.hasCreatedAt() ||
        anchors.any { (present, anchor) -> present && !anchor.hasCreatedAt() } ||
        attributesMap.values.any { !it.hasUpdatedAt() }
    if (malformed) {
        throw YorkieException(ErrInvalidArgument, "malformed tree restore span: missing timestamp")
    }
    return TreeRestoreSpan(
        id = id.toCrdtTreeNodeID(),
        nodeType = nodeType,
        isText = isText,
        length = length,
        value = if (isText) value else null,
        attrs = attributesMap.takeIf { it.isNotEmpty() }?.toRht(),
        parentID = if (hasParentId()) parentId.toCrdtTreeNodeID() else null,
        leftSiblingID = if (hasLeftSiblingId()) leftSiblingId.toCrdtTreeNodeID() else null,
        rightSiblingID = if (hasRightSiblingId()) rightSiblingId.toCrdtTreeNodeID() else null,
    )
}
