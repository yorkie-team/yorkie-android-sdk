package dev.yorkie.api

import dev.yorkie.api.v1.OperationKt.add
import dev.yorkie.api.v1.OperationKt.edit
import dev.yorkie.api.v1.OperationKt.increase
import dev.yorkie.api.v1.OperationKt.move
import dev.yorkie.api.v1.OperationKt.remove
import dev.yorkie.api.v1.OperationKt.select
import dev.yorkie.api.v1.OperationKt.set
import dev.yorkie.api.v1.OperationKt.style
import dev.yorkie.api.v1.OperationKt.treeEdit
import dev.yorkie.api.v1.OperationKt.treeStyle
import dev.yorkie.api.v1.operation
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SelectOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.time.ActorID

internal typealias PBOperation = dev.yorkie.api.v1.Operation

internal fun List<PBOperation>.toOperations(): List<Operation> {
    return map {
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
            )

            it.hasEdit() -> EditOperation(
                fromPos = it.edit.from.toRgaTreeSplitNodePos(),
                toPos = it.edit.to.toRgaTreeSplitNodePos(),
                parentCreatedAt = it.edit.parentCreatedAt.toTimeTicket(),
                executedAt = it.edit.executedAt.toTimeTicket(),
                maxCreatedAtMapByActor = buildMap {
                    it.edit.createdAtMapByActorMap.forEach { entry ->
                        set(ActorID(entry.key), entry.value.toTimeTicket())
                    }
                },
                content = it.edit.content,
                attributes = it.edit.attributesMap.takeUnless { attrs -> attrs.isEmpty() }
                    ?: mapOf(),
            )

            it.hasSelect() -> SelectOperation(
                fromPos = it.select.from.toRgaTreeSplitNodePos(),
                toPos = it.select.to.toRgaTreeSplitNodePos(),
                parentCreatedAt = it.select.parentCreatedAt.toTimeTicket(),
                executedAt = it.select.executedAt.toTimeTicket(),
            )

            it.hasStyle() -> StyleOperation(
                fromPos = it.style.from.toRgaTreeSplitNodePos(),
                toPos = it.style.to.toRgaTreeSplitNodePos(),
                attributes = it.style.attributesMap,
                parentCreatedAt = it.style.parentCreatedAt.toTimeTicket(),
                executedAt = it.style.executedAt.toTimeTicket(),
            )

            it.hasTreeEdit() -> TreeEditOperation(
                parentCreatedAt = it.treeEdit.parentCreatedAt.toTimeTicket(),
                fromPos = it.treeEdit.from.toCrdtTreePos(),
                toPos = it.treeEdit.to.toCrdtTreePos(),
                contents = it.treeEdit.contentsList.toCrdtTreeNodesWhenEdit(),
                executedAt = it.treeEdit.executedAt.toTimeTicket(),
                maxCreatedAtMapByActor =
                it.treeEdit.createdAtMapByActorMap.entries.associate { (key, value) ->
                    ActorID(key) to value.toTimeTicket()
                },
            )

            it.hasTreeStyle() -> TreeStyleOperation(
                parentCreatedAt = it.treeStyle.parentCreatedAt.toTimeTicket(),
                fromPos = it.treeStyle.from.toCrdtTreePos(),
                toPos = it.treeStyle.to.toCrdtTreePos(),
                attributes = it.treeStyle.attributesMap.toMap(),
                executedAt = it.treeStyle.executedAt.toTimeTicket(),
            )

            else -> throw IllegalArgumentException("unimplemented operation")
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
                    operation.maxCreatedAtMapByActor.forEach {
                        createdAtMapByActor[it.key.value] = it.value.toPBTimeTicket()
                    }
                    operation.attributes.forEach { attributes[it.key] = it.value }
                }
            }
        }

        is SelectOperation -> {
            operation {
                select = select {
                    parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                    from = operation.fromPos.toPBTextNodePos()
                    to = operation.toPos.toPBTextNodePos()
                    executedAt = operation.executedAt.toPBTimeTicket()
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
                    createdAtMapByActor.putAll(
                        operation.maxCreatedAtMapByActor.entries.associate {
                            it.key.value to it.value.toPBTimeTicket()
                        },
                    )
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
                    operation.attributes.forEach { (key, value) ->
                        attributes[key] = value
                    }
                }
            }
        }

        else -> throw IllegalArgumentException("unimplemented operation $operation")
    }
}

internal fun List<Operation>.toPBOperations(): List<PBOperation> = map(Operation::toPBOperation)
