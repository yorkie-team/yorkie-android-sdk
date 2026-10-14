package dev.yorkie.document.json

import dev.yorkie.util.YorkieException

/**
 * Reports whether a boundary between the UTF-16 code units [before] and
 * [after] falls inside a surrogate pair. A missing unit (`null`, what reading
 * past either end of a string yields here; JS `charCodeAt` returns `NaN` for
 * the same case) never pairs.
 *
 * Every check in this file works on UTF-16 [Char] units ([Char.isHighSurrogate],
 * [Char.isLowSurrogate]), never on a Unicode code point counted as one unit regardless
 * of width: the wire offsets Yorkie carries for Text and Tree indexes are UTF-16 units,
 * so a width-blind check would disagree with them.
 *
 * Ported from yorkie-js-sdk `6932da6a` (#1447) `strings.ts`'s `splitsSurrogatePair`.
 */
internal fun splitsSurrogatePair(before: Char?, after: Char?): Boolean {
    return before != null && before.isHighSurrogate() &&
        after != null && after.isLowSurrogate()
}

/**
 * Reports whether [offset], counted in UTF-16 code units, is a valid boundary
 * in [value] — i.e. it does not fall between the high and the low surrogate
 * of a pair. An [offset] outside `0..value.length` is always a boundary (there
 * is nothing on the missing side to pair with).
 *
 * Ported from yorkie-js-sdk `6932da6a` (#1447) `strings.ts`'s `isUTF16Boundary`.
 */
internal fun isUtf16Boundary(value: String, offset: Int): Boolean {
    val before = if (offset in 1..value.length) value[offset - 1] else null
    val after = if (offset in 0 until value.length) value[offset] else null
    return !splitsSurrogatePair(before, after)
}

/**
 * Throws when a local index between the code units [before] and [after]
 * splits a surrogate pair. An index there would split the node mid-pair, and
 * the SDKs store the lone halves differently: Go turns each into U+FFFD, this
 * SDK (like JS) keeps the raw code unit. Rejecting it keeps the same
 * operation from leaving different text on different replicas.
 *
 * Ported from yorkie-js-sdk `6932da6a` (#1447) `strings.ts`'s `ensureUTF16Boundary`.
 */
internal fun ensureUtf16Boundary(before: Char?, after: Char?) {
    if (splitsSurrogatePair(before, after)) {
        throw YorkieException(
            YorkieException.Code.ErrInvalidArgument,
            "index must not split a UTF-16 surrogate pair",
        )
    }
}

/**
 * Throws when [value] holds a surrogate code unit that is not part of a
 * pair. Rejecting the index that would split a pair ([ensureUtf16Boundary])
 * is only half of the contract: content carrying a lone half has the same
 * divergence (Go stores U+FFFD, this SDK the raw code unit), and once stored
 * it also pairs up with whatever code unit it lands against, so the index at
 * that seam becomes one [ensureUtf16Boundary] refuses for as long as the text
 * lives. Refusing the content keeps a local edit from manufacturing either.
 *
 * Ported from yorkie-js-sdk `6932da6a` (#1447) `strings.ts`'s `ensureNoLoneSurrogate`.
 */
internal fun ensureNoLoneSurrogate(value: String) {
    var index = 0
    while (index < value.length) {
        val c = value[index]
        if (!c.isHighSurrogate() && !c.isLowSurrogate()) {
            index++
            continue
        }
        // A high surrogate followed by a low one is a whole character; step
        // over both. Anything else reaching here is a half on its own.
        val next = if (index + 1 < value.length) value[index + 1] else null
        if (splitsSurrogatePair(c, next)) {
            index += 2
            continue
        }
        throw YorkieException(
            YorkieException.Code.ErrInvalidArgument,
            "content must not contain a lone UTF-16 surrogate",
        )
    }
}
