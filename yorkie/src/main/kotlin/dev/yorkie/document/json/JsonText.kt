package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.util.YorkieLogger
import dev.yorkie.document.RgaTreeSplitPosStruct as TextPosStruct

public typealias TextPosStructRange = Pair<TextPosStruct, TextPosStruct>

/**
 * [JsonText] is an extended data type for the contents of a text editor.
 */
public class JsonText internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtText,
) : JsonElement() {

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
    ): Pair<Int, Int>? {
        if (fromIndex > toIndex) {
            YorkieLogger.e(TAG, "fromIndex should be less than or equal to toIndex")
            return null
        }

        val range = createRange(fromIndex, toIndex) ?: return null

        YorkieLogger.d(
            TAG,
            "EDIT: f:$fromIndex->${range.first}, t:$toIndex->${range.second} c:$content",
        )

        val executedAt = context.issueTimeTicket()
        val (maxCreatedAtMapByActor, _, rangeAfterEdit) = runCatching {
            target.edit(range, content, executedAt, attributes)
        }.getOrElse {
            when (it) {
                is NoSuchElementException, is IllegalArgumentException -> {
                    YorkieLogger.e(TAG, "can't style text")
                    return null
                }

                else -> throw it
            }
        }

        context.push(
            EditOperation(
                fromPos = range.first,
                toPos = range.second,
                maxCreatedAtMapByActor = maxCreatedAtMapByActor,
                content = content,
                parentCreatedAt = target.createdAt,
                executedAt = executedAt,
                attributes = attributes ?: emptyMap(),
            ),
        )

        if (range.first != range.second) {
            context.registerElementHasRemovedNodes(target)
        }
        return rangeAfterEdit?.let(target::findIndexesFromRange)
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

        val range = createRange(fromIndex, toIndex) ?: return false

        YorkieLogger.d(
            TAG,
            "STYL: f:$fromIndex->${range.first}, t:$toIndex->${range.second} a:$attributes",
        )

        val executedAt = context.issueTimeTicket()
        runCatching {
            val maxCreatedAtMapByActor =
                target.style(range, attributes, executedAt).createdAtMapByActor
            context.push(
                StyleOperation(
                    parentCreatedAt = target.createdAt,
                    fromPos = range.first,
                    toPos = range.second,
                    attributes = attributes,
                    executedAt = executedAt,
                    maxCreatedAtMapByActor = maxCreatedAtMapByActor,
                ),
            )
        }.getOrElse {
            when (it) {
                is NoSuchElementException, is IllegalArgumentException -> {
                    YorkieLogger.e(TAG, "can't style text")
                    return false
                }

                else -> throw it
            }
        }
        return true
    }

    private fun createRange(fromIndex: Int, toIndex: Int): RgaTreeSplitPosRange? {
        return runCatching {
            target.indexRangeToPosRange(fromIndex, toIndex)
        }.getOrElse {
            when (it) {
                is NoSuchElementException, is IndexOutOfBoundsException -> {
                    YorkieLogger.e(TAG, "can't create range")
                    null
                }

                else -> throw it
            }
        }
    }

    /**
     * Returns [TextPosStructRange] of the given index range.
     */
    public fun indexRangeToPosRange(range: Pair<Int, Int>): TextPosStructRange? {
        val posRange = createRange(range.first, range.second) ?: return null
        return posRange.first.toStruct() to posRange.second.toStruct()
    }

    /**
     * Returns indexes of the given [TextPosStructRange].
     */
    public fun posRangeToIndexRange(range: TextPosStructRange): Pair<Int, Int> {
        return target.findIndexesFromRange(
            range.first.toOriginal() to range.second.toOriginal(),
        )
    }

    /**
     * Deletes the text in the given range.
     */
    public fun delete(fromIndex: Int, toIndex: Int): Pair<Int, Int>? {
        return edit(fromIndex, toIndex, "")
    }

    /**
     * Clears the text.
     */
    public fun clear(): Pair<Int, Int>? {
        return edit(0, target.length, "")
    }

    override fun toString(): String {
        return target.toString()
    }

    companion object {
        private const val TAG = "JsonText"
    }
}
