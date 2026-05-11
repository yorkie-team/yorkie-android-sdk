package dev.yorkie.document.yson

/**
 * [YsonValue] represents any valid YSON value.
 *
 * Variants cover the standard JSON value set plus Yorkie CRDT and special
 * scalar types: [YsonText], [YsonTree], [YsonInt], [YsonLong], [YsonDate],
 * [YsonBinData], and [YsonCounter].
 */
public sealed interface YsonValue {

    /**
     * [YsonString] wraps a string primitive.
     */
    public data class YsonString(public val value: String) : YsonValue

    /**
     * [YsonNumber] wraps a JSON number primitive.
     *
     * Kotlin uses [Double] to mirror the JS reference implementation, where
     * untyped numbers are always parsed as 64-bit floats. Typed integers use
     * [YsonInt] or [YsonLong].
     */
    public data class YsonNumber(public val value: Double) : YsonValue

    /**
     * [YsonBoolean] wraps a boolean primitive.
     */
    public data class YsonBoolean(public val value: Boolean) : YsonValue

    /**
     * [YsonNull] represents the JSON null literal.
     */
    public object YsonNull : YsonValue

    /**
     * [YsonObject] represents a plain key-value map.
     *
     * Entries preserve insertion order to keep round-tripping deterministic.
     */
    public data class YsonObject(public val entries: Map<String, YsonValue>) : YsonValue

    /**
     * [YsonArray] represents an ordered list of values.
     */
    public data class YsonArray(public val elements: List<YsonValue>) : YsonValue

    /**
     * [YsonInt] represents a 32-bit integer literal, serialized as `Int(value)`.
     */
    public data class YsonInt(public val value: Int) : YsonValue

    /**
     * [YsonLong] represents a 64-bit integer literal, serialized as `Long(value)`.
     */
    public data class YsonLong(public val value: Long) : YsonValue

    /**
     * [YsonDate] represents an ISO 8601 timestamp literal, serialized as `Date("...")`.
     */
    public data class YsonDate(public val value: String) : YsonValue

    /**
     * [YsonBinData] represents a Base64-encoded binary literal, serialized as `BinData("...")`.
     */
    public data class YsonBinData(public val value: String) : YsonValue

    /**
     * [YsonCounter] represents a Counter CRDT, serialized as `Counter(Int(...))` or `Counter(Long(...))`.
     *
     * [value] is constrained at construction to be either [YsonInt] or [YsonLong].
     */
    public data class YsonCounter(public val value: YsonValue) : YsonValue {
        init {
            require(value is YsonInt || value is YsonLong) {
                "Counter must contain Int or Long"
            }
        }
    }

    /**
     * [YsonText] represents a Text CRDT, serialized as `Text([...])`.
     */
    public data class YsonText(public val nodes: List<YsonTextNode>) : YsonValue

    /**
     * [YsonTree] represents a Tree CRDT, serialized as `Tree({...})`.
     */
    public data class YsonTree(public val root: YsonTreeNode) : YsonValue
}

/**
 * [YsonTextNode] represents a single character in a Text CRDT, optionally carrying inline attributes.
 *
 * @param value Character value held by this node.
 * @param attrs Optional attribute map (for example formatting flags).
 */
public data class YsonTextNode(
    public val value: String,
    public val attrs: Map<String, YsonValue>? = null,
)

/**
 * [YsonTreeNode] represents a node in a Tree CRDT.
 *
 * A text node carries [value]. An element node carries [attrs] and/or [children].
 *
 * @param type Node type (for example `text`, `p`, `div`).
 * @param value Text content for text nodes.
 * @param attrs Element attributes.
 * @param children Child element list.
 */
public data class YsonTreeNode(
    public val type: String,
    public val value: String? = null,
    public val attrs: Map<String, String>? = null,
    public val children: List<YsonTreeNode>? = null,
)

/**
 * [isText] returns true when [value] is a [YsonValue.YsonText].
 */
public fun isText(value: Any?): Boolean = value is YsonValue.YsonText

/**
 * [isTree] returns true when [value] is a [YsonValue.YsonTree].
 */
public fun isTree(value: Any?): Boolean = value is YsonValue.YsonTree

/**
 * [isInt] returns true when [value] is a [YsonValue.YsonInt].
 */
public fun isInt(value: Any?): Boolean = value is YsonValue.YsonInt

/**
 * [isLong] returns true when [value] is a [YsonValue.YsonLong].
 */
public fun isLong(value: Any?): Boolean = value is YsonValue.YsonLong

/**
 * [isDate] returns true when [value] is a [YsonValue.YsonDate].
 */
public fun isDate(value: Any?): Boolean = value is YsonValue.YsonDate

/**
 * [isBinData] returns true when [value] is a [YsonValue.YsonBinData].
 */
public fun isBinData(value: Any?): Boolean = value is YsonValue.YsonBinData

/**
 * [isCounter] returns true when [value] is a [YsonValue.YsonCounter].
 */
public fun isCounter(value: Any?): Boolean = value is YsonValue.YsonCounter

/**
 * [isObject] returns true when [value] is a plain [YsonValue.YsonObject], not a CRDT or special type.
 */
public fun isObject(value: Any?): Boolean = value is YsonValue.YsonObject
