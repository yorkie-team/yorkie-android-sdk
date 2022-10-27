package dev.yorkie.api

import dev.yorkie.api.v1.Operation.Add
import dev.yorkie.api.v1.Operation.Increase
import dev.yorkie.api.v1.Operation.Move
import dev.yorkie.api.v1.Operation.Remove
import dev.yorkie.api.v1.Operation.Set
import dev.yorkie.api.v1.operation
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation

typealias PBOperation = dev.yorkie.api.v1.Operation

internal fun List<PBOperation>.toOperation(): List<Operation> {
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
            it.hasEdit() -> TODO("not yet implemented")
            it.hasSelect() -> TODO("not yet implemented")
            it.hasRichEdit() -> TODO("not yet implemented")
            it.hasStyle() -> TODO("not yet implemented")
            else -> error("unimplemented operation")
        }
    }
}

// TODO(7hong13): should check Edit, Select, RichEdit, Style Operations
internal fun Operation.toPBOperation(): PBOperation {
    return when (val operation = this@toPBOperation) {
        is SetOperation -> {
            val setOperation = Set.newBuilder().apply {
                parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                key = operation.key
                value = operation.value.toPBJsonElementSimple()
                executedAt = operation.executedAt.toPBTimeTicket()
            }.build()
            operation { set = setOperation }
        }
        is AddOperation -> {
            val addOperation = Add.newBuilder().apply {
                parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                prevCreatedAt = operation.prevCreatedAt.toPBTimeTicket()
                value = operation.value.toPBJsonElementSimple()
                executedAt = operation.executedAt.toPBTimeTicket()
            }.build()
            operation { add = addOperation }
        }
        is MoveOperation -> {
            val moveOperation = Move.newBuilder().apply {
                parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                prevCreatedAt = operation.prevCreatedAt.toPBTimeTicket()
                createdAt = operation.createdAt.toPBTimeTicket()
                executedAt = operation.executedAt.toPBTimeTicket()
            }.build()
            operation { move = moveOperation }
        }
        is RemoveOperation -> {
            val removeOperation = Remove.newBuilder().apply {
                parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                createdAt = operation.createdAt.toPBTimeTicket()
                executedAt = operation.executedAt.toPBTimeTicket()
            }.build()
            operation { remove = removeOperation }
        }
        is IncreaseOperation -> {
            val increaseOperation = Increase.newBuilder().apply {
                parentCreatedAt = operation.parentCreatedAt.toPBTimeTicket()
                value = operation.value.toPBJsonElementSimple()
                executedAt = operation.executedAt.toPBTimeTicket()
            }.build()
            operation { increase = increaseOperation }
        }
        else -> error("unimplemented operation $operation")
    }
}

internal fun List<Operation>.toPBOperations(): List<PBOperation> {
    return map { it.toPBOperation() }
}
