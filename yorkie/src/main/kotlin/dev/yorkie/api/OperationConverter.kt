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
import dev.yorkie.util.YorkieException.Code.ErrUnimplemented
import dev.yorkie.api.v1.RestoreMode as PbRestoreMode
import dev.yorkie.api.v1.RestoreSpan as PbRestoreSpan
import dev.yorkie.api.v1.restoreSpan as pbRestoreSpan

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

            it.hasTreeEdit() -> TreeEditOperation(
                parentCreatedAt = it.treeEdit.parentCreatedAt.toTimeTicket(),
                fromPos = it.treeEdit.from.toCrdtTreePos(),
                toPos = it.treeEdit.to.toCrdtTreePos(),
                contents = it.treeEdit.contentsList.toCrdtTreeNodesWhenEdit(),
                executedAt = it.treeEdit.executedAt.toTimeTicket(),
                splitLevel = it.treeEdit.splitLevel,
            )

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
