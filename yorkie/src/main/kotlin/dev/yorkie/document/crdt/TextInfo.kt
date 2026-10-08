package dev.yorkie.document.crdt

import dev.yorkie.document.json.escapeString
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize

internal data class TextChange(
    val type: TextChangeType,
    val actor: String,
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
    var content: String,
    private val _attributes: Rht = Rht(),
) : RgaTreeSplitValue<TextValue>, GCParent<RhtNode> {

    // gcOnlySize: once getDataSize skips removed attributes (below), a
    // tombstoned attribute's bytes were never part of this node's own live
    // contribution, so registering it without gcOnlySize would wrongly debit
    // live for bytes it never held (JS text.ts:245-258).
    override val gcPairs: List<GCPair<RhtNode>>
        get() = _attributes
            .filter { node -> node.removedAt != null }
            .map { node -> GCPair(this, node, gcOnlySize = node.dataSize) }

    val attributes
        get() = _attributes.nodeKeyValueMap

    val attributesWithTimeTicket: Iterable<RhtNode>
        get() = _attributes

    override val length: Int
        get() = content.length

    override fun get(index: Int): Char = content[index]

    override fun deepCopy(): TextValue {
        return copy(_attributes = _attributes.deepCopy())
    }

    override fun getDataSize(): DataSize {
        var data = content.length * 2
        var meta = 0

        for (node in _attributes) {
            // A removed attribute belongs to docSize.gc, not to live (JS
            // text.ts:162-164) — its bytes are accounted separately via
            // gcPairs's gcOnlySize, never here.
            if (node.removedAt != null) {
                continue
            }
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

    /**
     * Shortens [content] to its first [offset] characters IN PLACE, keeping
     * this object's identity. A split has to keep the LEFT piece's value
     * object: GC pairs are keyed by parent identity, so replacing it would
     * orphan every pair already registered against it (JS
     * `rga_tree_split.ts`'s `RGATreeSplitValue.truncate` KDoc).
     */
    override fun truncate(offset: Int) {
        content = content.substring(0, offset)
    }

    fun setAttribute(
        key: String,
        value: String,
        executedAt: TimeTicket,
    ): RhtWrite {
        return _attributes.set(key, value, executedAt)
    }

    fun getAttrs() = _attributes

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
    // GCPair<*>: mixes born-dead split pieces (GCPair<RgaTreeSplitNode<TextValue>>)
    // with a split's copied attribute tombstones (GCPair<RhtNode>).
    val gcPairs: List<GCPair<*>>,
    val dataSize: DataSize,
    val removedValues: List<TextValue> = emptyList(),
    val removedSpans: List<RestoreSpan<TextValue>> = emptyList(),
)

/**
 * Result of [CrdtText.restore].
 */
internal data class TextRestoreResult(
    val untombstoned: List<RgaTreeSplitNode<TextValue>>,
    val recreated: List<RgaTreeSplitNode<TextValue>>,
    val textChanges: List<TextChange>,
    val dataSize: DataSize,
    // GCPair<*>: mixes born-dead split pieces (GCPair<RgaTreeSplitNode<TextValue>>)
    // with a recreated node's copied attribute tombstones (GCPair<RhtNode>).
    val pendingGcPairs: List<GCPair<*>>,
)

/**
 * Result of [CrdtText.retombstone].
 */
internal data class TextRetombstoneResult(
    // GCPair<*>: mixes re-tombstoned pieces (GCPair<RgaTreeSplitNode<TextValue>>)
    // with a split's copied attribute tombstones (GCPair<RhtNode>).
    val gcPairs: List<GCPair<*>>,
    val textChanges: List<TextChange>,
    val dataSize: DataSize,
)

internal data class TextStyleResult(
    val textChanges: List<TextChange>,
    // GCPair<*>: this result mixes attribute pairs (GCPair<RhtNode>) with
    // pairs drained from born-dead split pieces (GCPair<RgaTreeSplitNode<TextValue>>).
    val gcPairs: List<GCPair<*>>,
    // A style/removeStyle write can land on an already-removed node
    // (canStyle admits one unconditionally, since #1368) whose bytes must
    // move through gc, not live — see accAttrWrite in GC.kt.
    val docSize: DocSize,
    val prevAttributes: Map<String, String> = emptyMap(),
    val attributesToRemove: List<String> = emptyList(),
)
