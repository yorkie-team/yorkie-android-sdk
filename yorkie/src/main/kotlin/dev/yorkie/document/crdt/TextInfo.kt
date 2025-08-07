package dev.yorkie.document.crdt

import dev.yorkie.document.json.escapeString
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize

internal data class TextChange(
    val type: TextChangeType,
    val actor: ActorID,
    val from: Int,
    val to: Int,
    val content: String? = null,
    val attributes: Map<String, String>? = null,
)

/**
 * The type of [TextChange].
 */
internal enum class TextChangeType {
    Content,
    Style,
}

internal data class TextValue(
    val content: String,
    private val _attributes: Rht = Rht(),
) : RgaTreeSplitValue<TextValue>, GCParent<RhtNode> {

    val gcPairs: List<GCPair<RhtNode>>
        get() = _attributes
            .filter { node -> node.removedAt != null }
            .map { node -> GCPair(this, node) }

    val attributes
        get() = _attributes.nodeKeyValueMap

    val attributesWithTimeTicket: Iterable<RhtNode>
        get() = _attributes

    override val length: Int by content::length

    override fun get(index: Int): Char = content[index]

    override fun deepCopy(): TextValue {
        return copy(_attributes = _attributes.deepCopy())
    }

    override fun getDataSize(): DataSize {
        var data = content.length * 2
        var meta = 0

        for (node in _attributes) {
            val dataSize = node.dataSize
            data += dataSize.data
            meta += dataSize.meta
        }

        return DataSize(
            data = data,
            meta = meta,
        )
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        return TextValue(content.substring(startIndex, endIndex), _attributes.deepCopy())
    }

    fun setAttribute(
        key: String,
        value: String,
        executedAt: TimeTicket,
    ): RhtSetResult {
        return _attributes.set(key, value, executedAt)
    }

    /**
     * Deletes the given child node.
     */
    override fun delete(node: RhtNode) {
        _attributes.delete(node)
    }

    fun toJson(): String {
        val attrs = _attributes.nodeKeyValueMap.entries.joinToString(",") {
            """"${escapeString(it.key)}":"${escapeString(it.value)}""""
        }
        return if (attrs.isEmpty()) {
            """{"val":"${escapeString(content)}"}"""
        } else {
            """{"attrs":{$attrs},"val":"${escapeString(content)}"}"""
        }
    }

    override fun toString(): String {
        return content
    }
}

@JvmInline
public value class TextWithAttributes(private val value: Pair<String, Map<String, String>>) {
    val text: String
        get() = value.first

    val attributes: Map<String, String>
        get() = value.second
}

internal data class TextEditResult(
    val textChanges: List<TextChange>,
    val posRange: RgaTreeSplitPosRange,
    val gcPairs: List<GCPair<RgaTreeSplitNode<TextValue>>>,
)

internal data class TextStyleResult(
    val textChanges: List<TextChange>,
    val gcPairs: List<GCPair<RhtNode>>,
)
