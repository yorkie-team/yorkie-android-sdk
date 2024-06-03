package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.TreeTest
import dev.yorkie.core.createClient
import dev.yorkie.document.json.OpCode.EditOpCode
import dev.yorkie.document.json.OpCode.StyleOpCode
import dev.yorkie.document.json.TestOperation.EditOperationType
import dev.yorkie.document.json.TestOperation.StyleOperationType
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Ignore
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
    fun test_concurrent_style_and_style() {
        runBlocking {
            val root = element("r") {
                element("p") {
                    text { "a" }
                }
                element("p") {
                    text { "b" }
                }
                element("p") {
                    text { "c" }
                }
            }
            val initialXml = "<r><p>a</p><p>b</p><p>c</p></r>"

            val ranges = listOf(
                // equal: <p>b</p> - <p>b</p>
                makeTwoRanges(Triple(3, -1, 6), Triple(3, -1, 6), "equal"),
                // contain: <p>a</p><p>b</p><p>c</p> - <p>b</p>
                makeTwoRanges(Triple(0, -1, 9), Triple(3, -1, 6), "contain"),
                // intersect: <p>a</p><p>b</p> - <p>b</p><p>c</p>
                makeTwoRanges(Triple(0, -1, 6), Triple(3, -1, 9), "intersect"),
                // side-by-side: <p>a</p> - <p>b</p>
                makeTwoRanges(Triple(0, -1, 3), Triple(3, -1, 6), "side-by-side"),
            )

            val styleOperations = listOf(
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleRemove,
                    "bold",
                    "",
                    "remove-bold",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleSet,
                    "bold",
                    "aa",
                    "set-bold-aa",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleSet,
                    "bold",
                    "bb",
                    "set-bold-bb",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleRemove,
                    "italic",
                    "",
                    "remove-italic",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleSet,
                    "italic",
                    "aa",
                    "set-italic-aa",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleSet,
                    "italic",
                    "bb",
                    "set-italic-bb",
                ),
            )

            runTestConcurrency(
                root,
                initialXml,
                ranges,
                styleOperations,
                styleOperations,
                "concurrently-style-style-test",
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
                    StyleOpCode.StyleRemove,
                    "color",
                    "",
                    "remove-bold",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleSet,
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

    @Ignore("should be resolved after JS SDK implementation")
    @Test
    fun test_concurrent_split_and_split() {
        runBlocking {
            val root = element("r") {
                element("p") {
                    element("p") {
                        element("p") {
                            element("p") {
                                text { "abcd" }
                            }
                            element("p") {
                                text { "efgh" }
                            }
                        }
                        element("p") {
                            text { "ijkl" }
                        }
                    }
                }
            }
            val initialXml = "<r><p><p><p><p>abcd</p><p>efgh</p></p><p>ijkl</p></p></p></r>"
            val ranges = listOf(
                // equal-single-element: <p>abcd</p>
                makeTwoRanges(Triple(3, 6, 9), Triple(3, 6, 9), "equal-single"),
                // equal-multiple-element: <p>abcd</p><p>efgh</p>
                makeTwoRanges(Triple(3, 9, 15), Triple(3, 9, 15), "equal-multiple"),
                // A contains B same level: <p>abcd</p><p>efgh</p> - <p>efgh</p>
                makeTwoRanges(Triple(3, 9, 15), Triple(9, 12, 15), "A contains B same level"),
                // A contains B multiple level: <p><p>abcd</p><p>efgh</p></p><p>ijkl</p> - <p>efgh</p>
                makeTwoRanges(Triple(2, 16, 22), Triple(9, 12, 15), "A contains B multiple level"),
                // side by side
                makeTwoRanges(Triple(3, 6, 9), Triple(9, 12, 15), "B is next to A"),
            )

            val splitOperations = listOf(
                EditOperationType(
                    RangeSelector.RangeFront,
                    EditOpCode.SplitUpdate,
                    null,
                    1,
                    "split-front-1",
                ),
                EditOperationType(
                    RangeSelector.RangeOneQuarter,
                    EditOpCode.SplitUpdate,
                    null,
                    1,
                    "split-one-quarter-1",
                ),
                EditOperationType(
                    RangeSelector.RangeThreeQuarter,
                    EditOpCode.SplitUpdate,
                    null,
                    1,
                    "split-three-quarter-1",
                ),
                EditOperationType(
                    RangeSelector.RangeBack,
                    EditOpCode.SplitUpdate,
                    null,
                    1,
                    "split-back-1",
                ),
                EditOperationType(
                    RangeSelector.RangeFront,
                    EditOpCode.SplitUpdate,
                    null,
                    2,
                    "split-front-2",
                ),
                EditOperationType(
                    RangeSelector.RangeOneQuarter,
                    EditOpCode.SplitUpdate,
                    null,
                    2,
                    "split-one-quarter-2",
                ),
                EditOperationType(
                    RangeSelector.RangeThreeQuarter,
                    EditOpCode.SplitUpdate,
                    null,
                    2,
                    "split-three-quarter-2",
                ),
                EditOperationType(
                    RangeSelector.RangeBack,
                    EditOpCode.SplitUpdate,
                    null,
                    2,
                    "split-back-2",
                ),
            )

            runTestConcurrency(
                root,
                initialXml,
                ranges,
                splitOperations,
                splitOperations,
                "concurrently-split-split-test",
            )
        }
    }

    @Ignore("should be resolved after JS SDK implementation")
    @Test
    fun test_concurrent_split_and_edit() {
        runBlocking {
            val root = element("r") {
                element("p") {
                    element("p") {
                        element("p") {
                            element("p") {
                                text { "abcd" }
                                attrs { mapOf("italic" to "a") }
                            }
                            element("p") {
                                text { "efgh" }
                                attrs { mapOf("italic" to "a") }
                            }
                        }
                        element("p") {
                            text { "ijkl" }
                            attrs { mapOf("italic" to "a") }
                        }
                    }
                }
            }
            val initialXml =
                """<r><p><p><p italic="a">abcd</p><p italic="a">efgh</p></p>""" +
                    """<p italic="a">ijkl</p></p></r>"""
            val content = element("i")

            val ranges = listOf(
                // equal: <p>abcd</p>
                makeTwoRanges(Triple(2, 5, 8), Triple(2, 5, 8), "equal"),
                // A contains B: <p>abcd</p> - bc
                makeTwoRanges(Triple(2, 5, 8), Triple(4, 5, 6), "A contains B"),
                // B contains A: <p>abcd</p> - <p>abcd</p><p>efgh</p>
                makeTwoRanges(Triple(2, 5, 8), Triple(2, 8, 14), "B contains A"),
                // left node(text): <p>abcd</p> - ab
                makeTwoRanges(Triple(2, 5, 8), Triple(3, 4, 5), "left node(text)"),
                // right node(text): <p>abcd</p> - cd
                makeTwoRanges(Triple(2, 5, 8), Triple(5, 6, 7), "right node(text)"),
                // left node(element): <p>abcd</p><p>efgh</p> - <p>abcd</p>
                makeTwoRanges(Triple(2, 8, 14), Triple(2, 5, 8), "left node(element)"),
                // right node(element): <p>abcd</p><p>efgh</p> - <p>efgh</p>
                makeTwoRanges(Triple(2, 8, 14), Triple(8, 11, 14), "right node(element)"),
                // A -> B: <p>abcd</p> - <p>efgh</p>
                makeTwoRanges(Triple(2, 5, 8), Triple(8, 11, 14), "A -> B"),
                // B -> A: <p>efgh</p> - <p>abcd</p>
                makeTwoRanges(Triple(8, 11, 14), Triple(2, 5, 8), "B -> A"),
            )

            val splitOperations = listOf(
                EditOperationType(
                    RangeSelector.RangeMiddle,
                    EditOpCode.SplitUpdate,
                    null,
                    1,
                    "split-1",
                ),
                EditOperationType(
                    RangeSelector.RangeMiddle,
                    EditOpCode.SplitUpdate,
                    null,
                    2,
                    "split-2",
                ),
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
                    content,
                    0,
                    "replace",
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
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleSet,
                    "bold",
                    "aa",
                    "style",
                ),
                StyleOperationType(
                    RangeSelector.RangeAll,
                    StyleOpCode.StyleRemove,
                    "italic",
                    "",
                    "remove-style",
                ),
            )

            runTestConcurrency(
                root,
                initialXml,
                ranges,
                splitOperations,
                editOperations,
                "concurrently-split-edit-test",
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
                        val result = withTimeout(10_000) {
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
