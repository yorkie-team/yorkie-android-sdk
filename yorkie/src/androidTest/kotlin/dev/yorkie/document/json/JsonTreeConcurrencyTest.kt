package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.TreeTest
import dev.yorkie.core.GENERAL_TIMEOUT
import dev.yorkie.core.createClient
import dev.yorkie.document.json.OpCode.EditOpCode
import dev.yorkie.document.json.TestOperation.EditOperationType
import dev.yorkie.document.json.TestOperation.StyleOperationType
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@TreeTest
@RunWith(AndroidJUnit4::class)
class JsonTreeConcurrencyTest {

    @Test
    fun test_concurrent_edit_and_edit() {
        runBlocking {
            val root = element("r") {
                element("p") { text { "abc" } }
                element("p") { text { "def" } }
                element("p") { text { "ghi" } }
            }
            val initialXml = "<r><p>abc</p><p>def</p><p>ghi</p></r>"
            val textNode1 = text { "A" }
            val textNode2 = text { "B" }
            val elementNode1 = element("b")
            val elementNode2 = element("i")

            val ranges = listOf(
                // intersect-element: <p>abc</p><p>def</p> - <p>def</p><p>ghi</p>
                makeTwoRanges(Triple(0, 5, 10), Triple(5, 10, 15), "intersect-selement"),
                // intersect-text: ab - bc
                makeTwoRanges(Triple(1, 2, 3), Triple(2, 3, 4), "intersect-text"),
                // contain-element: <p>abc</p><p>def</p><p>ghi</p> - <p>def</p>
                makeTwoRanges(Triple(0, 5, 15), Triple(5, 5, 10), "contain-element"),
                // contain-text: abc - b
                makeTwoRanges(Triple(1, 2, 4), Triple(2, 2, 3), "contain-text"),
                // contain-mixed-type: <p>abc</p><p>def</p><p>ghi</p> - def
                makeTwoRanges(Triple(0, 5, 15), Triple(6, 7, 9), "contain-mixed-type"),
                // side-by-side-element: <p>abc</p> - <p>def</p>
                makeTwoRanges(Triple(0, 5, 5), Triple(5, 5, 10), "side-by-side-element"),
                // side-by-side-text: a - bc
                makeTwoRanges(Triple(1, 1, 2), Triple(2, 3, 4), "side-by-side-text"),
                // equal-element: <p>abc</p><p>def</p> - <p>abc</p><p>def</p>
                makeTwoRanges(Triple(0, 5, 10), Triple(0, 5, 10), "equal-element"),
                // equal-text: abc - abc
                makeTwoRanges(Triple(1, 2, 4), Triple(1, 2, 4), "equal-text"),
            )

            val edit1Operations = listOf(
                EditOperationType(
                    RangeSelector.RangeFront,
                    EditOpCode.EditUpdate,
                    textNode1,
                    0,
                    "insertTextFront",
                ),
                EditOperationType(
                    RangeSelector.RangeMiddle,
                    EditOpCode.EditUpdate,
                    textNode1,
                    0,
                    "insertTextMiddle",
                ),
                EditOperationType(
                    RangeSelector.RangeBack,
                    EditOpCode.EditUpdate,
                    textNode1,
                    0,
                    "insertTextBack",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    textNode1,
                    0,
                    "replaceText",
                ),
                EditOperationType(
                    RangeSelector.RangeFront,
                    EditOpCode.EditUpdate,
                    elementNode1,
                    0,
                    "insertElementFront",
                ),
                EditOperationType(
                    RangeSelector.RangeMiddle,
                    EditOpCode.EditUpdate,
                    elementNode1,
                    0,
                    "insertElementMiddle",
                ),
                EditOperationType(
                    RangeSelector.RangeBack,
                    EditOpCode.EditUpdate,
                    elementNode1,
                    0,
                    "insertElementBack",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    elementNode1,
                    0,
                    "replaceElement",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    null,
                    0,
                    "delete",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.MergeUpdate,
                    null,
                    0,
                    "merge",
                ),
            )

            val edit2Operations = listOf(
                EditOperationType(
                    RangeSelector.RangeFront,
                    EditOpCode.EditUpdate,
                    textNode2,
                    0,
                    "insertTextFront",
                ),
                EditOperationType(
                    RangeSelector.RangeMiddle,
                    EditOpCode.EditUpdate,
                    textNode2,
                    0,
                    "insertTextMiddle",
                ),
                EditOperationType(
                    RangeSelector.RangeBack,
                    EditOpCode.EditUpdate,
                    textNode2,
                    0,
                    "insertTextBack",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    textNode2,
                    0,
                    "replaceText",
                ),
                EditOperationType(
                    RangeSelector.RangeFront,
                    EditOpCode.EditUpdate,
                    elementNode2,
                    0,
                    "insertElementFront",
                ),
                EditOperationType(
                    RangeSelector.RangeMiddle,
                    EditOpCode.EditUpdate,
                    elementNode2,
                    0,
                    "insertElementMiddle",
                ),
                EditOperationType(
                    RangeSelector.RangeBack,
                    EditOpCode.EditUpdate,
                    elementNode2,
                    0,
                    "insertElementBack",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    elementNode2,
                    0,
                    "replaceElement",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    null,
                    0,
                    "delete",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.MergeUpdate,
                    null,
                    0,
                    "merge",
                ),
            )

            runTestConcurrency(
                root,
                initialXml,
                ranges,
                edit1Operations,
                edit2Operations,
                "concurrently-edit-edit-test",
            )
        }
    }

    @Test
    fun test_concurrent_edit_and_style() {
        runBlocking {
            val root = element("r") {
                element("p") {
                    text { "a" }
                    attrs { mapOf("color" to "red") }
                }
                element("p") {
                    text { "b" }
                    attrs { mapOf("color" to "red") }
                }
                element("p") {
                    text { "c" }
                    attrs { mapOf("color" to "red") }
                }
            }
            val initialXml =
                "<r><p color=\"red\">a</p><p color=\"red\">b</p><p color=\"red\">c</p></r>"
            val content = element("p") {
                text { "d" }
                attrs { mapOf("italic" to "true") }
            }

            val ranges = listOf(
                // equal: <p>b</p> - <p>b</p>
                makeTwoRanges(Triple(3, 3, 6), Triple(3, -1, 6), "equal"),
                // equal multiple: <p>a</p><p>b</p><p>c</p> - <p>a</p><p>b</p><p>c</p>
                makeTwoRanges(Triple(0, 3, 9), Triple(0, 3, 9), "equal multiple"),
                // A contains B: <p>a</p><p>b</p><p>c</p> - <p>b</p>
                makeTwoRanges(Triple(0, 3, 9), Triple(3, -1, 6), "A contains B"),
                // B contains A: <p>b</p> - <p>a</p><p>b</p><p>c</p>
                makeTwoRanges(Triple(3, 3, 6), Triple(0, -1, 9), "B contains A"),
                // intersect: <p>a</p><p>b</p> - <p>b</p><p>c</p>
                makeTwoRanges(Triple(0, 3, 6), Triple(3, -1, 9), "intersect"),
                // A -> B: <p>a</p> - <p>b</p>
                makeTwoRanges(Triple(0, 3, 3), Triple(3, -1, 6), "A -> B"),
                // B -> A: <p>b</p> - <p>a</p>
                makeTwoRanges(Triple(3, 3, 6), Triple(0, -1, 3), "B -> A"),
            )

            val editOperations = listOf(
                EditOperationType(
                    RangeSelector.RangeFront,
                    EditOpCode.EditUpdate,
                    content,
                    0,
                    "insertFront",
                ),
                EditOperationType(
                    RangeSelector.RangeMiddle,
                    EditOpCode.EditUpdate,
                    content,
                    0,
                    "insertMiddle",
                ),
                EditOperationType(
                    RangeSelector.RangeBack,
                    EditOpCode.EditUpdate,
                    content,
                    0,
                    "insertBack",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    null,
                    0,
                    "delete",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.EditUpdate,
                    content,
                    0,
                    "replace",
                ),
                EditOperationType(
                    RangeSelector.RangeAll,
                    EditOpCode.MergeUpdate,
                    null,
                    0,
                    "merge",
                ),
            )

            val styleOperations = listOf(
                StyleOperationType(
                    RangeSelector.RangeAll,
                    OpCode.StyleOpCode.StyleRemove,
                    "color",
                    "",
                    "remove-bold",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    OpCode.StyleOpCode.StyleSet,
                    "bold",
                    "aa",
                    "set-bold-aa",
                ),
            )

            runTestConcurrency(
                root,
                initialXml,
                ranges,
                editOperations,
                styleOperations,
                "concurrently-edit-style-test",
            )
        }
    }

    companion object {

        private suspend fun runTestConcurrency(
            root: JsonTree.ElementNode,
            initialXml: String,
            ranges: List<TwoRangesType>,
            op1s: List<TestOperation>,
            op2s: List<TestOperation>,
            desc: String,
        ) {
            val c1 = createClient()
            val c2 = createClient()
            c1.activateAsync().await()
            c2.activateAsync().await()

            ranges.forEach { range ->
                op1s.forEach { op1 ->
                    op2s.forEach { op2 ->
                        val testDesc = "$desc-${range.desc}(${op1.desc},${op2.desc})"
                        val result = withTimeout(GENERAL_TIMEOUT) {
                            runTest(c1, c2, root, initialXml, range, op1, op2, testDesc)
                        }
                        assertEquals(result.after.first, result.after.second)
                    }
                }
            }

            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }
}
