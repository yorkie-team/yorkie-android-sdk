package dev.yorkie.document

import dev.yorkie.DummyContext
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/**
 * Ports JS SDK `tree_style_path_bounds_test.ts` (`f70e9f166`, #1524): the
 * single-path form of `styleByPath` derives a one-index-wide range around the
 * path, so the path has to name an existing node. A path one past the last
 * child used to resolve to the parent's closing tag and style the PARENT.
 * A negative path element used to resolve one index early in every path API.
 *
 * Android keeps its own exception type (`IllegalArgumentException`, as the
 * neighbouring `pathToTreePos` throws) where JS throws `ErrInvalidArgument`.
 */
class TreeStylePathBoundsTest {

    /** `<root><p><t>ab</t></p><p></p></root>` */
    private fun elementTree(): JsonTree = tree(
        element("root") {
            element("p") { element("t") { text { "ab" } } }
            element("p")
        },
    )

    /** `<root><p>ab</p><p>cd</p></root>` */
    private fun textTree(): JsonTree = tree(
        element("root") {
            element("p") { text { "ab" } }
            element("p") { text { "cd" } }
        },
    )

    private fun tree(root: JsonTree.ElementNode) = JsonTree(
        DummyContext,
        CrdtTree(JsonTree.buildRoot(root, DummyContext), TimeTicket.InitialTimeTicket),
    )

    private fun JsonTree.assertRejected(block: JsonTree.() -> Unit) {
        val before = toXml()
        assertFailsWith<IllegalArgumentException> { block() }
        assertEquals(before, toXml(), "the refused call left no trace")
    }

    private val bold = mapOf("bold" to "true")

    @Test
    fun `styles the node at an existing path`() {
        val target = elementTree()
        target.styleByPath(listOf(0, 0), bold)
        assertEquals(
            """<root><p><t bold="true">ab</t></p><p></p></root>""",
            target.toXml(),
        )
    }

    @Test
    fun `styles the element at a top-level path`() {
        val target = elementTree()
        target.styleByPath(listOf(1), bold)
        assertEquals(
            """<root><p><t>ab</t></p><p bold="true"></p></root>""",
            target.toXml(),
        )
    }

    @Test
    fun `rejects the first child path of an empty element`() {
        elementTree().assertRejected { styleByPath(listOf(1, 0), bold) }
    }

    @Test
    fun `rejects the path one past the last child`() {
        elementTree().assertRejected { styleByPath(listOf(0, 1), bold) }
    }

    @Test
    fun `rejects the top-level path one past the last element`() {
        elementTree().assertRejected { styleByPath(listOf(2), bold) }
    }

    @Test
    fun `keeps rejecting paths further out of range`() {
        val target = elementTree()
        target.assertRejected { styleByPath(listOf(1, 5), bold) }
        target.assertRejected { styleByPath(listOf(0, 3), bold) }
    }

    @Test
    fun `rejects a negative path element`() {
        val target = elementTree()
        target.assertRejected { styleByPath(listOf(-1), bold) }
        target.assertRejected { styleByPath(listOf(0, -1), bold) }
    }

    @Test
    fun `leaves the range form alone`() {
        val target = elementTree()
        target.styleByPath(listOf(0), listOf(1), bold)
        assertEquals(
            """<root><p bold="true"><t bold="true">ab</t></p><p></p></root>""",
            target.toXml(),
        )
    }

    // -- text-only parent ----------------------------------------------------

    @Test
    fun `rejects the offset one past the last character`() {
        textTree().assertRejected { styleByPath(listOf(0, 2), bold) }
    }

    @Test
    fun `rejects an offset further past the last character`() {
        textTree().assertRejected { styleByPath(listOf(0, 3), bold) }
    }

    @Test
    fun `rejects a negative character offset`() {
        textTree().assertRejected { styleByPath(listOf(0, -1), bold) }
    }

    @Test
    fun `accepts an offset that names a character`() {
        val target = textTree()
        val before = target.toXml()
        target.styleByPath(listOf(0, 0), bold)
        target.styleByPath(listOf(0, 1), bold)
        assertEquals(before, target.toXml(), "styling a character offset is a no-op")
    }

    @Test
    fun `still styles the elements themselves`() {
        val target = textTree()
        target.styleByPath(listOf(1), bold)
        assertEquals(
            """<root><p>ab</p><p bold="true">cd</p></root>""",
            target.toXml(),
        )
    }

    // -- negative element in every path API ----------------------------------

    @Test
    fun `rejects a negative element in the single-path style form`() {
        elementTree().assertRejected { styleByPath(listOf(0, -1), bold) }
    }

    @Test
    fun `rejects a negative element in the range style form`() {
        val target = elementTree()
        target.assertRejected { styleByPath(listOf(-1), listOf(1), bold) }
        target.assertRejected { styleByPath(listOf(0, -1), listOf(0, 0), bold) }
    }

    @Test
    fun `rejects a negative element in removeStyleByPath`() {
        val target = elementTree()
        target.styleByPath(listOf(0, 0), bold)
        target.assertRejected { removeStyleByPath(listOf(0, -1), listOf(0, 0), listOf("bold")) }
    }

    @Test
    fun `rejects a negative element in splitByPath`() {
        textTree().assertRejected { splitByPath(listOf(0, -1)) }
    }

    @Test
    fun `rejects a negative element in mergeByPath`() {
        elementTree().assertRejected { mergeByPath(listOf(-1)) }
    }

    @Test
    fun `rejects a negative element in editByPath`() {
        textTree().assertRejected { editByPath(listOf(0, -1), listOf(0, 1)) }
    }

    @Test
    fun `rejects a negative element in pathToIndex`() {
        val target = elementTree()
        assertFailsWith<IllegalArgumentException> { target.pathToIndex(listOf(0, -1)) }
        assertFailsWith<IllegalArgumentException> { target.pathToIndex(listOf(-1)) }
    }
}
