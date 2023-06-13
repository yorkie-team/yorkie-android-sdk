package dev.yorkie.document.operation

import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.time.TimeTicket

/**
 * [OperationInfo] represents the information of an operation.
 * It is used to inform to the user what kind of operation was executed.
 */
public sealed class OperationInfo {

    public abstract var path: String

    public data class AddOpInfo(val index: Int, override var path: String = INITIAL_PATH) :
        OperationInfo()

    public data class MoveOpInfo(
        val previousIndex: Int,
        val index: Int,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo()

    public data class SetOpInfo(val key: String, override var path: String = INITIAL_PATH) :
        OperationInfo()

    public data class RemoveOpInfo(
        val key: String?,
        val index: Int?,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo()

    public data class IncreaseOpInfo(val value: Number, override var path: String = INITIAL_PATH) :
        OperationInfo()

    public data class EditOpInfo(
        val from: Int,
        val to: Int,
        val value: TextWithAttributes,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo()

    public data class StyleOpInfo(
        val from: Int,
        val to: Int,
        val value: TextWithAttributes,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo()

    public data class SelectOpInfo(
        val from: Int,
        val to: Int,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo()

    companion object {
        const val INITIAL_PATH = "initial path"
    }
}

internal data class InternalOpInfo(
    val element: TimeTicket,
    val operationInfo: OperationInfo,
)
