package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.TextChange
import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.SelectOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieLogger

/**
 * [JsonText] is an extended data type for the contents of a text editor.
 */
public class JsonText internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtText,
) : JsonElement() {

    public val id: TimeTicket
        get() = target.id

    public val values: List<TextWithAttributes>
        get() = target.values

    public val length: Int
        get() = target.length

    /**
     * Edits this [JsonText] with the given [content] and [attributes].
     */
    public fun edit(
        fromIndex: Int,
        toIndex: Int,
        content: String,
        attributes: Map<String, String>? = null,
    ): Boolean {
        if (fromIndex > toIndex) {
            YorkieLogger.e(TAG, "fromIndex should be less than or equal to toIndex")
            return false
        }

        val range = target.createRange(fromIndex, toIndex)
        val executedAt = context.issueTimeTicket()
        val maxCreatedAtMapByActor = target.edit(range, content, executedAt, attributes).first
        context.push(
            EditOperation(
                fromPos = range.first,
                toPos = range.second,
                maxCreatedAtMapByActor = maxCreatedAtMapByActor,
                content = content,
                parentCreatedAt = target.createdAt,
                executedAt = executedAt,
                attributes = attributes ?: mapOf(),
            ),
        )

        if (range.first != range.second) {
            context.registerRemovedNodeTextElement(target)
        }
        return true
    }

    /**
     * Styles this [JsonText] with the given [attributes].
     */
    public fun style(
        fromIndex: Int,
        toIndex: Int,
        attributes: Map<String, String>,
    ): Boolean {
        if (fromIndex > toIndex) {
            YorkieLogger.e(TAG, "fromIndex should be less than or equal to toIndex")
            return false
        }

        val range = target.createRange(fromIndex, toIndex)
        val executedAt = context.issueTimeTicket()
        target.style(range, attributes, executedAt)

        context.push(
            StyleOperation(
                parentCreatedAt = target.createdAt,
                fromPos = range.first,
                toPos = range.second,
                attributes = attributes,
                executedAt = executedAt,
            ),
        )
        return true
    }

    /**
     * Selects the given range.
     */
    public fun select(fromIndex: Int, toIndex: Int): Boolean {
        val range = target.createRange(fromIndex, toIndex)
        val executedAt = context.issueTimeTicket()
        target.select(range, executedAt)
        context.push(
            SelectOperation(
                parentCreatedAt = target.createdAt,
                fromPos = range.first,
                toPos = range.second,
                executedAt = executedAt,
            ),
        )
        return true
    }

    /**
     * Deletes the text in the given range.
     */
    public fun delete(fromIndex: Int, toIndex: Int): Boolean {
        return edit(fromIndex, toIndex, "")
    }

    /**
     * Clears the text.
     */
    public fun clear(): Boolean {
        return edit(0, target.length, "")
    }

    /**
     * Registers a handler of onChanges event.
     */
    public fun onChanges(handler: ((List<TextChange>) -> Unit)) {
        return target.onChanges(handler)
    }

    override fun toString(): String {
        return target.toString()
    }

    companion object {
        private const val TAG = "JsonText"
    }
}
