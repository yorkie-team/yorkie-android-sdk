package dev.yorkie.document.operation

import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonCounter
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.time.TimeTicket

/**
 * [OperationInfo] represents the information of an operation.
 * It is used to inform to the user what kind of operation was executed.
 */
public sealed class OperationInfo {

    public abstract var path: String

    internal var executedAt: TimeTicket = TimeTicket.InitialTimeTicket

    /**
     * [TextOperationInfo] represents the [OperationInfo] for the [JsonText].
     */
    public interface TextOperationInfo

    /**
     * [CounterOperationInfo] represents the [OperationInfo] for the [JsonCounter].
     */
    public interface CounterOperationInfo

    /**
     * [ArrayOperationInfo] represents the [OperationInfo] for the [JsonArray].
     */
    public interface ArrayOperationInfo

    /**
     * [ObjectOperationInfo] represents the [OperationInfo] for the [JsonObject].
     */
    public interface ObjectOperationInfo

    /**
     * [TreeOperationInfo] represents the [OperationInfo] for the [JsonTree].
     */
    public interface TreeOperationInfo

    public data class AddOpInfo(val index: Int, override var path: String = INITIAL_PATH) :
        OperationInfo(), ArrayOperationInfo

    public data class MoveOpInfo(
        val previousIndex: Int,
        val index: Int,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo(), ArrayOperationInfo

    public data class SetOpInfo(val key: String, override var path: String = INITIAL_PATH) :
        OperationInfo(), ObjectOperationInfo

    public data class RemoveOpInfo(
        val key: String?,
        val index: Int?,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo(), ArrayOperationInfo, ObjectOperationInfo

    public data class IncreaseOpInfo(val value: Number, override var path: String = INITIAL_PATH) :
        OperationInfo(), CounterOperationInfo

    public data class EditOpInfo(
        val from: Int,
        val to: Int,
        val value: TextWithAttributes,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo(), TextOperationInfo

    public data class StyleOpInfo(
        val from: Int,
        val to: Int,
        val attributes: Map<String, String>,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo(), TextOperationInfo

    public data class TreeEditOpInfo(
        val from: Int,
        val to: Int,
        val fromPath: List<Int>,
        val toPath: List<Int>,
        val nodes: List<JsonTree.TreeNode>?,
        val splitLevel: Int,
        override var path: String = INITIAL_PATH,
    ) : OperationInfo(), TreeOperationInfo

    public data class TreeStyleOpInfo(
        val from: Int,
        val to: Int,
        val fromPath: List<Int>,
        val toPath: List<Int>,
        val attributes: Map<String, String> = emptyMap(),
        val attributesToRemove: List<String> = emptyList(),
        override var path: String = INITIAL_PATH,
    ) : OperationInfo(), TreeOperationInfo

    companion object {
        private const val INITIAL_PATH = "initial path"
    }
}
