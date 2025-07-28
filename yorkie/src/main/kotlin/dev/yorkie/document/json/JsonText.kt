package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNode
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.crdt.TextWithAttributes
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.SplayTreeSet
import java.util.TreeMap
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

    internal val treeByIndex: SplayTreeSet<RgaTreeSplitNode<TextValue>>
        get() = target.rgaTreeSplit.treeByIndex

    internal val treeByID: TreeMap<RgaTreeSplitNodeID, RgaTreeSplitNode<TextValue>>
        get() = target.rgaTreeSplit.treeByID

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
            logError(TAG, "fromIndex should be less than or equal to toIndex")
            return null
        }

        val range = createRange(fromIndex, toIndex) ?: return null

        logDebug(TAG, "EDIT: f:$fromIndex->${range.first}, t:$toIndex->${range.second} c:$content")

        val executedAt = context.issueTimeTicket()
        val (_, rangeAfterEdit, gcPairs) = runCatching {
            target.edit(range, content, executedAt, attributes)
        }.getOrElse {
            when (it) {
                is NoSuchElementException, is IllegalArgumentException -> {
                    logError(TAG, "can't style text")
                    return null
                }

                else -> throw it
            }
        }
        gcPairs.forEach(context::registerGCPair)

        context.push(
            EditOperation(
                fromPos = range.first,
                toPos = range.second,
                content = content,
                parentCreatedAt = target.createdAt,
                executedAt = executedAt,
                attributes = attributes ?: emptyMap(),
            ),
        )

        return rangeAfterEdit.let(target::findIndexesFromRange)
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
            logError(TAG, "fromIndex should be less than or equal to toIndex")
            return false
        }

        val range = createRange(fromIndex, toIndex) ?: return false

        logDebug(
            TAG,
            "STYL: f:$fromIndex->${range.first}, t:$toIndex->${range.second} a:$attributes",
        )

        val executedAt = context.issueTimeTicket()
        runCatching {
            val (_, gcPairs) = target.style(range, attributes, executedAt)
            context.push(
                StyleOperation(
                    parentCreatedAt = target.createdAt,
                    fromPos = range.first,
                    toPos = range.second,
                    attributes = attributes,
                    executedAt = executedAt,
                ),
            )
            gcPairs.forEach(context::registerGCPair)
        }.getOrElse {
            when (it) {
                is NoSuchElementException, is IllegalArgumentException -> {
                    logError(TAG, "can't style text")
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
                    logError(TAG, "can't create range")
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
