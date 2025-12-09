package dev.yorkie

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.time.TimeTicket

internal val DummyContext = ChangeContext(
    ChangeID.InitialChangeID,
    CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket)),
)

internal fun issueTime() = DummyContext.issueTimeTicket()

internal data class TestCase(val desc: String, val steps: List<Step>)

internal data class Step(
    val op: TestOperation,
    val expected: String,
    val expectedSize: Int = 0,
) {
    fun toList() = listOf(this)
}

internal data class TestOperation(val code: OpCode, val key: String, val value: String)

internal interface OpCode {

    enum class RhtCode : OpCode {
        NoOp,
        Set,
        Remove,
    }

    enum class TreeCode : OpCode {
        NoOp,
        Style,
        RemoveStyle,
        DeleteNode,
        GC,
    }

    enum class TextCode : OpCode {
        NoOp,
        Style,
        DeleteNode,
        GC,
    }
}

fun String.toDocKey(): String {
    return lowercase().replace("[^a-z\\d-]".toRegex(), "-")
        .substring(0, length.coerceAtMost(120))
}
