package dev.yorkie.document.crdt

import dev.yorkie.document.json.escapeString
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.DataSize

/**
 * [Rht] is a replicated hash table by creation time.
 * For more details about RHT:
 * @link http://csl.skku.edu/papers/jpdc11.pdf
 */
class Rht : Collection<RhtNode> {
    private val nodeMapByKey = mutableMapOf<String, RhtNode>()
    private var numberOfRemovedElements = 0

    val nodeKeyValueMap: Map<String, String>
        get() {
            return nodeMapByKey.filterValues { !it.isRemoved }.entries.associate { (key, node) ->
                key to node.value
            }
        }

    fun set(
        key: String,
        value: String,
        executedAt: TimeTicket,
    ): RhtWrite {
        val prev = nodeMapByKey[key]
        if (prev?.isRemoved == true && prev.executedAt < executedAt) {
            numberOfRemovedElements--
        }

        if (prev?.executedAt < executedAt) {
            val node = RhtNode(key, value, executedAt, isRemoved = false)
            nodeMapByKey[key] = node
            return when {
                prev == null -> RhtWrite(installed = node)
                // Already-removed predecessor: hand back the SAME object so
                // CrdtRoot.registerGCPair's identity-keyed toggle unregisters
                // its earlier registration (re-setting a removed key makes
                // that old tombstone unreachable again, same as before).
                prev.isRemoved -> RhtWrite(installed = node, revived = prev)
                // Live predecessor: the SAME live object, never a fresh
                // tombstoned copy — RHT overrides immutably, so `prev` is
                // dropped with no tombstone and nothing to collect,
                // but its bytes were counted in docSize.live and have to
                // leave it via accAttrWrite's `superseded` subtraction.
                else -> RhtWrite(installed = node, superseded = prev)
            }
        }

        // Write lost LWW: nothing installed, nothing revived, nothing
        // superseded. This also removes the previous spurious toggle where a
        // write that lost LWW against an already-removed `prev` returned
        // `prev` anyway, which would wrongly re-cancel Prev's gc
        // registration for a write that changed nothing.
        return RhtWrite()
    }

    fun setInternal(
        key: String,
        value: String,
        executedAt: TimeTicket,
        removed: Boolean,
    ) {
        val node = RhtNode(key, value, executedAt, removed)
        nodeMapByKey[key] = node
        if (removed) {
            numberOfRemovedElements++
        }
    }

    /**
     * Removes the Element of the given [key].
     *
     * The tombstone still STORES the value — what goes on the wire is
     * unchanged — but stops being CHARGED for it, because [RhtNode.dataSize]
     * skips a removed node's value (#1392). [RhtRemoval.valueDropped] is what
     * the caller takes back out of whichever side of the ledger was holding
     * those bytes.
     */
    fun remove(key: String, executedAt: TimeTicket): RhtRemoval {
        val prev = nodeMapByKey[key]
        var valueDropped = DataSize(data = 0, meta = 0)
        val gcNodes = buildList {
            if (prev == null) {
                numberOfRemovedElements++
                nodeMapByKey[key] = RhtNode(key, "", executedAt, true).also { add(it) }
            } else if (prev.executedAt < executedAt) {
                if (prev.isRemoved) {
                    add(prev)
                } else {
                    numberOfRemovedElements++
                    valueDropped = DataSize(data = valueSize(prev.value), meta = 0)
                }
                nodeMapByKey[key] = RhtNode(key, prev.value, executedAt, true).also { add(it) }
            }
        }
        return RhtRemoval(gcNodes, valueDropped)
    }

    /**
     * Deletes the given child node.
     */
    fun delete(child: RhtNode) {
        val node = nodeMapByKey[child.key] ?: return
        if (node != child) {
            return
        }
        nodeMapByKey.remove(child.key)
        numberOfRemovedElements--
    }

    fun getNodeMapByKey(): Map<String, RhtNode> = nodeMapByKey.toMap()

    operator fun get(key: String): String? = nodeMapByKey[key]?.value

    fun has(key: String): Boolean = nodeMapByKey[key]?.isRemoved == false

    fun deepCopy(): Rht {
        val rht = Rht()
        nodeMapByKey.values.forEach { node ->
            rht.setInternal(node.key, node.value, node.executedAt, node.isRemoved)
        }
        return rht
    }

    /**
     * Converts the given [Rht] to XML String.
     *
     * Both the key and the value are peer-chosen (a remote [set] can write
     * either one raw), so each is escaped before interpolation: unescaped, a
     * `"` closes the attribute and a `<` opens an element, letting a peer
     * forge markup into every reader's rendered tree (port JS
     * `document/crdt/tree.ts` `escapeXMLAttr`, `6932da6a`, #1447) -- the XML
     * half of the forging [toJson] already closes for the JSON encoding via
     * [escapeString].
     */
    fun toXml(): String {
        return nodeMapByKey.filterValues { !it.isRemoved }.entries
            .sortedBy { it.key }
            .joinToString(" ") { (key, node) ->
                "${escapeXmlAttr(key)}=\"${escapeXmlAttr(node.value)}\""
            }
    }

    fun toJson(): String {
        return nodeMapByKey.filterValues { !it.isRemoved }.entries
            .joinToString(",", "{", "}") { (key, node) ->
                "\"${escapeString(key)}\":\"${escapeString(node.value)}\""
            }
    }

    override fun iterator(): Iterator<RhtNode> {
        return nodeMapByKey.values.iterator()
    }

    override val size: Int
        get() = nodeMapByKey.size - numberOfRemovedElements

    override fun containsAll(elements: Collection<RhtNode>): Boolean = elements.all { contains(it) }

    override fun contains(element: RhtNode): Boolean = nodeMapByKey[element.key]?.isRemoved == false

    override fun equals(other: Any?): Boolean {
        if (other !is Rht) {
            return false
        }
        return nodeMapByKey == other.nodeKeyValueMap
    }

    override fun hashCode(): Int {
        return nodeMapByKey.hashCode()
    }

    override fun isEmpty(): Boolean = size == 0
}

data class RhtNode(
    val key: String,
    val value: String,
    val executedAt: TimeTicket,
    val isRemoved: Boolean,
) : GCChild {

    override val removedAt: TimeTicket? = executedAt.takeIf { isRemoved }

    /**
     * A tombstone charges its key only (#1392). The value it still carries
     * is dead weight — nothing reads it, [Rht.has]/[Rht.toJson]/[Rht.toXml]
     * all gate on [isRemoved] — and charging it made the running `docSize`
     * disagree with a rebuild of the same document, which replays the same
     * removals. The value is left on the node rather than cleared so that
     * what this SDK stores and serializes for a tombstone is byte-for-byte
     * what it was, and what a peer sends us is kept verbatim: the
     * convergence fix belongs in the accounting, not the wire format.
     */
    override val dataSize: DataSize
        get() = DataSize(
            data = utf8Length(key) * 2 + (if (isRemoved) 0 else valueSize(value)),
            meta = TIME_TICKET_SIZE,
        )
}

/**
 * [RhtRemoval] is what [Rht.remove] reports back.
 *
 * [gcNodes] are the tombstones this removal made collectable.
 *
 * [valueDropped] is the size of the value the removal stopped charging for,
 * which no node's [RhtNode.dataSize] accounts for any more. The caller
 * subtracts it from whichever side of the ledger was holding it: live for an
 * attribute that was live on a live node, gc for one on a node that is
 * itself a tombstone. Zero when the attribute was already a tombstone, since
 * a tombstone's value was not being charged in the first place.
 */
data class RhtRemoval(
    val gcNodes: List<RhtNode>,
    val valueDropped: DataSize,
)

/**
 * [RhtWrite] is what [Rht.set] reports back.
 *
 * [installed] is the node the write put in the map, absent when the write
 * lost LWW and changed nothing. Its size is what enters `docSize.live`.
 *
 * [revived] is a tombstone this write replaced. It was registered as garbage
 * when it was removed, so the caller re-registers the pair to cancel that
 * registration: it is no longer collectable, it is simply gone.
 *
 * [superseded] is a LIVE node this write replaced. RHT overrides immutably,
 * so the old node is dropped with no tombstone and nothing to collect, but
 * its bytes were counted in `docSize.live` and have to leave it.
 */
data class RhtWrite(
    val installed: RhtNode? = null,
    val revived: RhtNode? = null,
    val superseded: RhtNode? = null,
)

/**
 * `utf8Length` returns the number of UTF-8 bytes in [s], which is what Go's
 * `len()` counts. A size charged in UTF-16 units instead agrees only for
 * ASCII, and the two SDKs enforce the document size limit client-side
 * against their own accounting, so they have to measure the same way. A
 * lone (unpaired) surrogate is walked one char at a time rather than via
 * `String.toByteArray`, because the JVM's UTF-8 encoder replaces it with a
 * single `?` byte while JS `TextEncoder` replaces it with U+FFFD (3 bytes).
 */
private fun utf8Length(s: String): Int {
    var length = 0
    var index = 0
    while (index < s.length) {
        val c = s[index]
        when {
            c.isHighSurrogate() && index + 1 < s.length && s[index + 1].isLowSurrogate() -> {
                length += 4
                index += 2
            }
            c.isHighSurrogate() || c.isLowSurrogate() -> {
                length += 3 // unpaired surrogate -> U+FFFD, as JS TextEncoder encodes it
                index++
            }
            else -> {
                length += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                index++
            }
        }
    }
    return length
}

/**
 * Returns the [DataSize.data] bytes an attribute value of the given stored
 * form contributes, on the same terms as [RhtNode.dataSize].
 */
private fun valueSize(stored: String): Int = utf8Length(logicalValue(stored)) * 2

/**
 * Returns [stored] as a peer storing attribute values raw would hold it. JS
 * `logicalValue` parses [stored] as JSON and unwraps only when the parsed
 * value is a string, tolerating surrounding whitespace the way `JSON.parse`
 * does; Android carries no JSON parser (and must not add one solely for
 * this — YAGNI), so this hand-rolls the one case the ported sizing tests
 * exercise: [stored], once trimmed, is a well-formed JSON string literal —
 * length >= 2, first and last char `"` — in which case the outer quotes are
 * stripped and the interior is JSON-unescaped. Anything else (an unquoted
 * word, a number, a boolean, malformed quoting, or an escape `JSON.parse`
 * would reject) passes through as the original [stored], matching
 * `JSON.parse`'s catch branch.
 */
private fun logicalValue(stored: String): String {
    val trimmed = stored.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
    if (trimmed.length < 2 || trimmed.first() != '"' || trimmed.last() != '"') {
        return stored
    }
    return unescapeJsonString(trimmed.substring(1, trimmed.length - 1)) ?: stored
}

/**
 * Unescapes the interior of a JSON string literal, mirroring `JSON.parse`:
 * `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t` and `\uXXXX` (exactly four
 * hex digits) are the only valid escapes. An unescaped `"` (the interior is
 * not a single string literal, e.g. `"a"+"b"`), a raw control character
 * (U+0000-U+001F) that was never escaped, an unknown escape, or a dangling
 * trailing `\` all mean `JSON.parse` would throw, so this returns null and
 * the caller falls back to the raw stored value.
 */
private fun unescapeJsonString(interior: String): String? {
    val result = StringBuilder(interior.length)
    var index = 0
    while (index < interior.length) {
        val c = interior[index]
        if (c == '"' || c.code < 0x20) return null
        if (c != '\\') {
            result.append(c)
            index++
            continue
        }
        if (index + 1 >= interior.length) return null
        when (interior[index + 1]) {
            '"' -> {
                result.append('"')
                index += 2
            }
            '\\' -> {
                result.append('\\')
                index += 2
            }
            '/' -> {
                result.append('/')
                index += 2
            }
            'b' -> {
                result.append('\b')
                index += 2
            }
            'f' -> {
                result.append('\u000C')
                index += 2
            }
            'n' -> {
                result.append('\n')
                index += 2
            }
            'r' -> {
                result.append('\r')
                index += 2
            }
            't' -> {
                result.append('\t')
                index += 2
            }
            'u' -> {
                if (index + 6 > interior.length) return null
                val hex = interior.substring(index + 2, index + 6)
                if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
                result.append(hex.toInt(16).toChar())
                index += 6
            }
            else -> return null
        }
    }
    return result.toString()
}

private val xmlAttrEscapes = mapOf(
    '&' to "&amp;",
    '<' to "&lt;",
    '>' to "&gt;",
    '"' to "&quot;",
    '\'' to "&apos;",
)

/**
 * Escapes a name or a value for interpolation into a double-quoted XML
 * attribute (port JS `escapeXMLAttr`, `6932da6a`, #1447). A single left-to-
 * right pass over [value]'s original characters, so an entity this function
 * inserts (e.g. `&amp;` for a literal `&`) is never itself re-escaped.
 */
private fun escapeXmlAttr(value: String): String {
    val result = StringBuilder(value.length)
    for (c in value) {
        result.append(xmlAttrEscapes[c] ?: c.toString())
    }
    return result.toString()
}
