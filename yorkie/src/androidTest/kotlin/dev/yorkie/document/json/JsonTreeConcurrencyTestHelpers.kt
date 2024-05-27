package dev.yorkie.document.json

import dev.yorkie.core.Client
import dev.yorkie.core.toDocKey
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonTreeTest.Companion.Updater
import dev.yorkie.document.json.JsonTreeTest.Companion.assertTreesXmlEquals
import dev.yorkie.document.json.JsonTreeTest.Companion.rootTree
import dev.yorkie.document.json.JsonTreeTest.Companion.updateAndSync
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal typealias xmlPair = Pair<String, String>

private val pass = Unit

internal sealed interface TestOperation {
    val selector: RangeSelector
    val op: OpCode
    val desc: String
    suspend fun run(document: Document, range: RangeWithMiddleType)

    data class StyleOperationType(
        override val selector: RangeSelector,
        override val op: OpCode.StyleOpCode,
        val key: String,
        val value: String,
        override val desc: String,
    ) : TestOperation {
        override suspend fun run(document: Document, range: RangeWithMiddleType) {
            val (from, to) = getRange(range, selector)
            document.updateAsync { root, _ ->
                when (op) {
                    OpCode.StyleOpCode.StyleRemove -> {
                        root.rootTree().removeStyle(from, to, listOf(key))
                    }

                    OpCode.StyleOpCode.StyleSet -> {
                        root.rootTree().style(from, to, mapOf(key to value))
                    }

                    else -> pass
                }
            }.await()
        }
    }

    data class EditOperationType(
        override val selector: RangeSelector,
        override val op: OpCode.EditOpCode,
        val content: JsonTree.TreeNode?,
        val splitLevel: Int,
        override val desc: String,
    ) : TestOperation {
        override suspend fun run(document: Document, range: RangeWithMiddleType) {
            val interval = getRange(range, selector)
            val (from, to) = interval
            val convertedContent = if (content == null) arrayOf() else arrayOf(content)
            document.updateAsync { root, _ ->
                when (op) {
                    OpCode.EditOpCode.EditUpdate -> {
                        root.rootTree().edit(from, to, splitLevel, *convertedContent)
                    }

                    OpCode.EditOpCode.MergeUpdate -> {
                        val mergeInterval = getMergeRange(root.rootTree().toXml(), interval)
                        val (st, ed) = mergeInterval
                        if (st != -1 && ed != -1 && st < ed) {
                            root.rootTree().edit(st, ed, splitLevel, *convertedContent)
                        }
                    }

                    OpCode.EditOpCode.SplitUpdate -> {
                        assertNotEquals(0, splitLevel)
                        assertEquals(from, to)
                        root.rootTree().edit(from, to, splitLevel, *convertedContent)
                    }

                    else -> pass
                }
            }.await()
        }
    }
}

internal enum class RangeSelector {
    RangeUnknown,
    RangeFront,
    RangeMiddle,
    RangeBack,
    RangeAll,
    RangeOneQuarter,
    RangeThreeQuarter,
}

internal sealed interface OpCode {

    enum class StyleOpCode : OpCode {
        StyleUndefined,
        StyleRemove,
        StyleSet,
    }

    enum class EditOpCode : OpCode {
        EditUndefined,
        EditUpdate,
        MergeUpdate,
        SplitUpdate,
    }
}

internal data class RangeType(val from: Int, val to: Int)

internal data class RangeWithMiddleType(val from: Int, val mid: Int, val to: Int)

internal data class TwoRangesType(
    val from: RangeWithMiddleType,
    val to: RangeWithMiddleType,
    val desc: String,
)

internal fun getRange(range: RangeWithMiddleType, selector: RangeSelector): RangeType {
    return when (selector) {
        RangeSelector.RangeFront -> RangeType(range.from, range.from)
        RangeSelector.RangeMiddle -> RangeType(range.mid, range.mid)
        RangeSelector.RangeBack -> RangeType(range.to, range.to)
        RangeSelector.RangeAll -> RangeType(range.from, range.to)
        RangeSelector.RangeOneQuarter -> {
            val quarter = (range.from + range.mid + 1) shr 1
            RangeType(quarter, quarter)
        }

        RangeSelector.RangeThreeQuarter -> {
            val quarter = (range.mid + range.to) shr 1
            RangeType(quarter, quarter)
        }

        RangeSelector.RangeUnknown -> RangeType(-1, -1)
    }
}

internal fun makeTwoRanges(
    from: Triple<Int, Int, Int>,
    to: Triple<Int, Int, Int>,
    desc: String,
): TwoRangesType {
    val fromRange = RangeWithMiddleType(from.first, from.second, from.third)
    val toRange = RangeWithMiddleType(to.first, to.second, to.third)
    return TwoRangesType(fromRange, toRange, desc)
}

internal fun getMergeRange(xml: String, interval: RangeType): RangeType {
    val content = parseSimpleXml(xml)
    var st = -1
    var ed = -1
    for (i in interval.from + 1..interval.to) {
        if (st == -1 && content[i].startsWith("</")) {
            st = i - 1
        }
        if (content[i].startsWith("<") && !content[i].startsWith("</")) {
            ed = i
        }
    }
    return RangeType(st, ed)
}

private fun parseSimpleXml(s: String): List<String> = buildList {
    var i = 0
    while (i < s.length) {
        var now = ""
        if (s[i] == '<') {
            while (i < s.length && s[i] != '>') {
                now += s[i++]
            }
        }
        now += s[i++]
        add(now)
    }
}

internal suspend fun runTest(
    c1: Client,
    c2: Client,
    initialRoot: JsonTree.ElementNode,
    initialXml: String,
    ranges: TwoRangesType,
    op1: TestOperation,
    op2: TestOperation,
    desc: String,
): TestResult {
    val docKey = desc.toDocKey()
    val d1 = Document(docKey)
    val d2 = Document(docKey)

    c1.attachAsync(d1, syncMode = Client.SyncMode.Manual).await()
    c2.attachAsync(d2, syncMode = Client.SyncMode.Manual).await()

    updateAndSync(
        Updater(c1, d1) { root, _ ->
            root.setNewTree("t", initialRoot)
        },
        Updater(c2, d2),
    )
    assertTreesXmlEquals(initialXml, d1, d2)

    op1.run(d1, ranges.from)
    op2.run(d1, ranges.from)

    val before1 = d1.getRoot().rootTree().toXml()
    val before2 = d2.getRoot().rootTree().toXml()

    // save own changes and get previous changes
    c1.syncAsync().await()
    c2.syncAsync().await()

    // get last client changes
    c1.syncAsync().await()
    c2.syncAsync().await()

    val after1 = d1.getRoot().rootTree().toXml()
    val after2 = d2.getRoot().rootTree().toXml()

    c1.detachAsync(d1).await()
    c2.detachAsync(d2).await()
    d1.close()
    d2.close()

    return TestResult(before1 to before2, after1 to after2)
}

internal data class TestResult(val before: xmlPair, val after: xmlPair)
