package dev.yorkie.document.json

import dev.yorkie.DummyContext
import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.pull
import dev.yorkie.helper.push
import dev.yorkie.issueTime
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports yorkie-js-sdk `surrogate_index_test.ts` (`6932da6a`, #1447), checked
 * at [Char] index granularity — never by code point — since the wire offsets
 * Text and Tree indexes carry are UTF-16 units. This class holds the
 * standalone `Strings.kt` helper cases, the Text entry-point cases
 * (`CrdtText`/`JsonText`) and the Tree entry-point cases
 * (`CrdtTree`/`JsonTree`), the initial-tree content cases (`setNewTree`'s own
 * `ensureNoLoneSurrogate` guard), the crafted-wire regression locks for an
 * index the document computed itself, and the `Document`-level wire-delivery
 * regression locks (an older client's own stale index, and a receiver's
 * locally-computed reverse) that exercise a real
 * [dev.yorkie.document.history.History] stack instead of a hand-built
 * [dev.yorkie.document.crdt.CrdtTree].
 */
class SurrogateIndexTest {

    // Surrogate-pair halves used throughout: U+1F600 GRINNING FACE ("😀") is
    // the high surrogate '\uD83D' followed by the low surrogate '\uDE00'.
    private val high = '\uD83D'
    private val low = '\uDE00'

    @Test
    fun `splitsSurrogatePair is true for a high surrogate followed by a low surrogate`() {
        assertTrue(splitsSurrogatePair(high, low))
    }

    @Test
    fun `splitsSurrogatePair is false for a reversed pair`() {
        assertFalse(splitsSurrogatePair(low, high))
    }

    @Test
    fun `splitsSurrogatePair is false when either side is null`() {
        assertFalse(splitsSurrogatePair(null, low))
        assertFalse(splitsSurrogatePair(high, null))
        assertFalse(splitsSurrogatePair(null, null))
    }

    @Test
    fun `splitsSurrogatePair is false for plain BMP characters`() {
        assertFalse(splitsSurrogatePair('a', 'b'))
    }

    /**
     * An independent oracle for [isUtf16Boundary]: a boundary is anywhere a
     * walk that steps one whole character (a surrogate pair together, via
     * [Character.isSurrogatePair]) at a time can stop, plus everything
     * outside the string — ported from the JS test's `encodedBoundary`.
     */
    private fun encodedBoundary(value: String, offset: Int): Boolean {
        if (offset <= 0 || offset >= value.length) return true
        var at = 0
        while (at < value.length) {
            if (at == offset) return true
            val isPair = at + 1 < value.length &&
                Character.isSurrogatePair(value[at], value[at + 1])
            at += if (isPair) 2 else 1
        }
        return false
    }

    @Test
    fun `isUtf16Boundary matches the encoded definition at every offset`() {
        val values = listOf(
            "",
            "abc",
            "가나다",
            "😀",
            "😀x",
            "x😀",
            "😀😁",
            "a😀b😁c",
            "\uD83D",
            "\uDE00\uD83D",
        )
        for (value in values) {
            for (offset in -1..value.length + 1) {
                assertEquals(
                    encodedBoundary(value, offset),
                    isUtf16Boundary(value, offset),
                    "value $value, offset $offset",
                )
            }
        }
    }

    @Test
    fun `isUtf16Boundary rejects only the offset inside a surrogate pair`() {
        val value = "a😀b"
        assertTrue(isUtf16Boundary(value, 1))
        assertFalse(isUtf16Boundary(value, 2))
        assertTrue(isUtf16Boundary(value, 3))
    }

    @Test
    fun `isUtf16Boundary is a boundary everywhere in the empty string`() {
        assertTrue(isUtf16Boundary("", 0))
        assertTrue(isUtf16Boundary("", -1))
        assertTrue(isUtf16Boundary("", 1))
    }

    @Test
    fun `ensureUtf16Boundary does not throw at a valid boundary`() {
        ensureUtf16Boundary('a', 'b')
        ensureUtf16Boundary(null, high)
        ensureUtf16Boundary(low, null)
    }

    @Test
    fun `ensureUtf16Boundary throws when the index splits a surrogate pair`() {
        val exception = assertFailsWith<YorkieException> {
            ensureUtf16Boundary(high, low)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertEquals("index must not split a UTF-16 surrogate pair", exception.errorMessage)
    }

    @Test
    fun `ensureNoLoneSurrogate does not throw for an empty string`() {
        ensureNoLoneSurrogate("")
    }

    @Test
    fun `ensureNoLoneSurrogate does not throw for plain BMP characters`() {
        ensureNoLoneSurrogate("abc가나다")
    }

    @Test
    fun `ensureNoLoneSurrogate does not throw for a whole surrogate pair`() {
        ensureNoLoneSurrogate("😀")
    }

    @Test
    fun `ensureNoLoneSurrogate accepts whole characters on both sides of a pair`() {
        ensureNoLoneSurrogate("a😀b")
    }

    @Test
    fun `ensureNoLoneSurrogate throws for a lone high surrogate`() {
        assertLoneSurrogateRejected("a$high")
    }

    @Test
    fun `ensureNoLoneSurrogate throws for a lone low surrogate`() {
        assertLoneSurrogateRejected("${low}b")
    }

    @Test
    fun `ensureNoLoneSurrogate throws for a reversed pair`() {
        assertLoneSurrogateRejected("$low$high")
    }

    @Test
    fun `ensureNoLoneSurrogate throws for a half beside a whole pair`() {
        assertLoneSurrogateRejected("😀$high")
    }

    private fun assertLoneSurrogateRejected(value: String) {
        val exception = assertFailsWith<YorkieException> {
            ensureNoLoneSurrogate(value)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertEquals("content must not contain a lone UTF-16 surrogate", exception.errorMessage)
    }

    // ---- Text entry-point checks (CrdtText.createRange, JsonText) ----
    //
    // `surrogateText` mirrors the JS fixture: one non-BMP character followed
    // by a BMP one -- 2 characters, 3 UTF-16 code units. Text offset 1 is
    // the only position that cuts the emoji in half.
    private val surrogateText = "😀x"

    private fun newText(): JsonText {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val text = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
        return JsonText(ChangeContext(ChangeID.InitialChangeID, CrdtRoot(obj)), text)
    }

    /**
     * Asserts [block] (a [JsonText] edit/style call) throws
     * [YorkieException] with [YorkieException.Code.ErrInvalidArgument] and
     * leaves [target] holding exactly [before] -- the refused call made no
     * change.
     */
    private fun assertEditRejected(
        target: JsonText,
        before: String,
        block: () -> Unit,
    ) {
        val exception = assertFailsWith<YorkieException> { block() }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertEquals(before, target.toString(), "the refused edit left no trace")
    }

    @Test
    fun `edit rejects an index that splits a surrogate pair`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        assertEditRejected(target, surrogateText) { target.edit(1, 1, "y") }
    }

    @Test
    fun `style rejects an index that splits a surrogate pair`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        assertEditRejected(target, surrogateText) {
            target.style(0, 1, mapOf("bold" to "true"))
        }
    }

    @Test
    fun `edit refuses a lone high surrogate`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        assertEditRejected(target, surrogateText) { target.edit(0, 0, "a$high") }
    }

    @Test
    fun `edit refuses a lone low surrogate`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        assertEditRejected(target, surrogateText) { target.edit(0, 0, "${low}b") }
    }

    @Test
    fun `edit refuses a reversed pair`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        assertEditRejected(target, surrogateText) { target.edit(0, 0, "$low$high") }
    }

    @Test
    fun `edit refuses a half beside a whole pair`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        assertEditRejected(target, surrogateText) { target.edit(0, 0, "😀$high") }
    }

    @Test
    fun `edit accepts whole characters on both sides of a pair`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        target.edit(0, 0, "a😀b")
        assertEquals("a😀b😀x", target.toString())
    }

    @Test
    fun `edit keeps valid Text boundaries editable`() {
        for ((index, expected) in listOf(0 to "y😀x", 2 to "😀yx", 3 to "😀xy")) {
            val target = newText().apply { edit(0, 0, surrogateText) }
            target.edit(index, index, "y")
            assertEquals(expected, target.toString(), "index $index")
        }
    }

    @Test
    fun `edit resolves an index in a later node after a split`() {
        // Appending a second emoji leaves "😀x😀y" across two nodes, so the
        // rejected offset lives in a node that is not the first one.
        fun twoNodeText() = newText().apply {
            edit(0, 0, surrogateText)
            edit(3, 3, "😀y")
        }

        val target = twoNodeText()
        assertEditRejected(target, "😀x😀y") { target.edit(4, 4, "w") }

        for ((index, expected) in listOf(3 to "😀xw😀y", 5 to "😀x😀wy", 6 to "😀x😀yw")) {
            val retry = twoNodeText()
            retry.edit(index, index, "w")
            assertEquals(expected, retry.toString(), "index $index")
        }
    }

    @Test
    fun `indexRangeToPosRange stays unchecked for a mid-pair index`() {
        val target = newText().apply { edit(0, 0, surrogateText) }
        assertNotNull(target.indexRangeToPosRange(1 to 1))
    }

    // Converting a position back to an index splits the text node there,
    // with no operation, so a mid-pair style from an older client leaves
    // this replica's emoji in two `RgaTreeSplit` nodes while every other
    // replica keeps it in one. `indexToPos` resolves the seam to the node on
    // its left, where the offset is at the END of that node, so the check
    // only sees the pair by reading the node that follows -- and
    // `indexRangeToPosRange`/`style` below (the unchecked lookup a remote
    // operation applies through) never throw while creating that seam.
    @Test
    fun `createRange rejects a Text index at a local seam a split left`() {
        val text = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
        text.edit(text.indexRangeToPosRange(0, 0), surrogateText, issueTime())
        assertFailsWith<YorkieException> { text.createRange(1, 1) }

        text.style(text.indexRangeToPosRange(0, 1), mapOf("bold" to "true"), issueTime())
        assertEquals(surrogateText, text.toString(), "the pair is still intact")

        assertFailsWith<YorkieException> { text.createRange(1, 1) }
        assertFailsWith<YorkieException> { text.createRange(0, 1) }
        assertFailsWith<YorkieException> { text.createRange(1, 3) }

        // The boundaries around the seam still resolve.
        for ((from, to) in listOf(0 to 0, 0 to 2, 2 to 2, 2 to 3, 0 to 3)) {
            text.createRange(from, to)
        }
    }

    @Test
    fun `edit discards earlier edits of the rejected update`() = runTest {
        val document = Document("surrogate-index")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, surrogateText)
        }.await()
        val original = document.getRoot().getAs<JsonText>("text").toString()

        val rejected = document.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(3, 3, "z")
            root.getAs<JsonText>("text").edit(1, 1, "y")
        }.await()
        assertTrue(rejected.isFailure)
        assertEquals(
            original,
            document.getRoot().getAs<JsonText>("text").toString(),
            "the refused update left no trace, including its earlier edit",
        )

        document.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(3, 3, "z")
        }.await()
        val edited = document.getRoot().getAs<JsonText>("text").toString()
        assertEquals("${surrogateText}z", edited)

        document.history.undoAsync().await()
        assertEquals(original, document.getRoot().getAs<JsonText>("text").toString())
        document.history.redoAsync().await()
        assertEquals(edited, document.getRoot().getAs<JsonText>("text").toString())
    }

    // ---- Tree entry-point checks (CrdtTree.findPos, JsonTree) ----
    //
    // The Tree fixture mirrors the Text one above: <r><p>😀x</p></r>, so
    // Tree index 2 (Text offset 1 of the <p>'s text) is the only position
    // that cuts the emoji in half.
    private fun surrogateTreeRoot() = element("r") {
        element("p") {
            text { surrogateText }
        }
    }

    private fun newTree(): JsonTree {
        return JsonTree(
            DummyContext,
            CrdtTree(
                JsonTree.buildRoot(surrogateTreeRoot(), DummyContext),
                TimeTicket.InitialTimeTicket,
            ),
        )
    }

    /**
     * Asserts [block] (a [JsonTree] edit/style call) throws
     * [YorkieException] with [YorkieException.Code.ErrInvalidArgument] and
     * leaves [target] holding exactly [before] -- the refused call made no
     * change.
     */
    private fun assertTreeEditRejected(
        target: JsonTree,
        before: String,
        block: () -> Unit,
    ) {
        val exception = assertFailsWith<YorkieException> { block() }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertEquals(before, target.toXml(), "the refused edit left no trace")
    }

    @Test
    fun `Tree entry points reject a mid-pair index`() {
        val y = JsonTree.TextNode("y")
        val z = JsonTree.TextNode("z")
        val cases: List<Pair<String, (JsonTree) -> Unit>> = listOf(
            "edit" to { t: JsonTree -> t.edit(2, 2, y) },
            "edit with several contents" to { t: JsonTree -> t.edit(2, 2, y, z) },
            "style" to { t: JsonTree -> t.style(1, 2, mapOf("bold" to "true")) },
            "removeStyle" to { t: JsonTree -> t.removeStyle(1, 2, listOf("bold")) },
            "editByPath" to { t: JsonTree -> t.editByPath(listOf(0, 1), listOf(0, 1), y) },
            "editByPath with several contents" to
                { t: JsonTree -> t.editByPath(listOf(0, 1), listOf(0, 1), y, z) },
            "styleByPath single" to
                { t: JsonTree -> t.styleByPath(listOf(0, 1), mapOf("bold" to "true")) },
            "styleByPath range" to { t: JsonTree ->
                t.styleByPath(listOf(0, 0), listOf(0, 1), mapOf("bold" to "true"))
            },
            "removeStyleByPath" to
                { t: JsonTree -> t.removeStyleByPath(listOf(0, 0), listOf(0, 1), listOf("bold")) },
            "splitByPath" to { t: JsonTree -> t.splitByPath(listOf(0, 1)) },
        )
        for ((name, fn) in cases) {
            val target = newTree()
            assertTreeEditRejected(target, "<r><p>😀x</p></r>") { fn(target) }
        }
    }

    @Test
    fun `Tree edit refuses a lone high surrogate`() {
        val target = newTree()
        assertTreeEditRejected(target, "<r><p>😀x</p></r>") {
            target.edit(1, 1, JsonTree.TextNode("a$high"))
        }
    }

    @Test
    fun `Tree edit refuses a lone low surrogate`() {
        val target = newTree()
        assertTreeEditRejected(target, "<r><p>😀x</p></r>") {
            target.edit(1, 1, JsonTree.TextNode("${low}b"))
        }
    }

    @Test
    fun `Tree edit refuses a reversed pair`() {
        val target = newTree()
        assertTreeEditRejected(target, "<r><p>😀x</p></r>") {
            target.edit(1, 1, JsonTree.TextNode("$low$high"))
        }
    }

    @Test
    fun `Tree edit refuses a half beside a whole pair`() {
        val target = newTree()
        assertTreeEditRejected(target, "<r><p>😀x</p></r>") {
            target.edit(1, 1, JsonTree.TextNode("😀$high"))
        }
    }

    @Test
    fun `Tree edit accepts whole characters on both sides of a pair`() {
        val target = newTree()
        target.edit(1, 1, JsonTree.TextNode("a😀b"))
        assertEquals("<r><p>a😀b😀x</p></r>", target.toXml())
    }

    @Test
    fun `edit keeps valid Tree boundaries editable`() {
        for ((index, expected) in listOf(
            1 to "<r><p>y😀x</p></r>",
            3 to "<r><p>😀yx</p></r>",
            4 to "<r><p>😀xy</p></r>",
        )) {
            val target = newTree()
            target.edit(index, index, JsonTree.TextNode("y"))
            assertEquals(expected, target.toXml(), "index $index")
        }
    }

    // Converting a position back to an index splits a tree's text node
    // there, with no operation, so a mid-pair selection from a peer leaves
    // this replica's emoji in two sibling nodes while every other replica
    // keeps it in one. The seam then looks like a node boundary, but an edit
    // there still names an offset inside the pair in the original node,
    // which other replicas split.
    @Test
    fun `rejects a Tree index at a local seam a split left`() {
        val target = newTree()
        val seam = target.target.indexRangeToPosRange(2 to 2)
        assertEquals(2 to 2, target.target.posRangeToIndexRange(seam))

        assertTreeEditRejected(target, "<r><p>😀x</p></r>") {
            target.edit(2, 2, JsonTree.TextNode("y"))
        }

        for ((index, expected) in listOf(1 to "<r><p>y😀x</p></r>", 3 to "<r><p>😀yx</p></r>")) {
            val clone = newTree()
            clone.target.posRangeToIndexRange(clone.target.indexRangeToPosRange(2 to 2))
            clone.edit(index, index, JsonTree.TextNode("y"))
            assertEquals(expected, clone.toXml(), "index $index")
        }
    }

    @Test
    fun `resolves Tree indexes after a split`() {
        // An earlier edit splits <p>'s text node, so every offset has to be
        // resolved relative to the node that holds it.
        fun splitTree() = newTree().apply { edit(3, 3, JsonTree.TextNode("yz")) }

        val rejectTarget = splitTree()
        assertTreeEditRejected(rejectTarget, "<r><p>😀yzx</p></r>") {
            rejectTarget.edit(2, 2, JsonTree.TextNode("w"))
        }

        // The seam the split created (3) and the offsets inside the
        // inserted node (4, 5) are whole-character boundaries.
        for ((index, expected) in listOf(
            3 to "<r><p>😀wyzx</p></r>",
            4 to "<r><p>😀ywzx</p></r>",
            5 to "<r><p>😀yzwx</p></r>",
        )) {
            val target = splitTree()
            target.edit(index, index, JsonTree.TextNode("w"))
            assertEquals(expected, target.toXml(), "index $index")
        }
    }

    @Test
    fun `Tree indexRangeToPosRange stays unchecked for a mid-pair index`() {
        val target = newTree()
        assertNotNull(target.indexRangeToPosRange(2 to 2))
    }

    // The single-path form of `styleByPath` goes through `pathToPosRange`,
    // which derives the end of the range itself as `fromIdx + 1`. The caller
    // named a node, so a derived index that happens to land between the
    // halves of the following pair must not refuse the request.
    @Test
    fun `styles a single Tree path whose derived end lands inside a pair`() {
        val target = newTree()
        assertEquals(1, target.pathToIndex(listOf(0, 0)))
        assertTrue(runCatching { target.target.findPos(2) }.isFailure, "index 2 is mid-pair")

        target.styleByPath(listOf(0, 0), mapOf("bold" to "true"))
        assertTrue(target.toXml().contains("😀"), "the pair survived the style")
    }

    @Test
    fun `edit discards earlier Tree edits of the rejected update`() = runTest {
        val document = Document("surrogate-index")
        document.updateAsync { root, _ ->
            root.setNewTree("tree", surrogateTreeRoot())
        }.await()
        val original = document.getRoot().getAs<JsonTree>("tree").toXml()

        val rejected = document.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(4, 4, JsonTree.TextNode("z"))
            root.getAs<JsonTree>("tree").edit(2, 2, JsonTree.TextNode("z"))
        }.await()
        assertTrue(rejected.isFailure)
        assertEquals(
            original,
            document.getRoot().getAs<JsonTree>("tree").toXml(),
            "the refused update left no trace, including its earlier edit",
        )

        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(4, 4, JsonTree.TextNode("z"))
        }.await()
        val edited = document.getRoot().getAs<JsonTree>("tree").toXml()
        assertEquals("<r><p>😀xz</p></r>", edited)

        document.history.undoAsync().await()
        assertEquals(original, document.getRoot().getAs<JsonTree>("tree").toXml())
        document.history.redoAsync().await()
        assertEquals(edited, document.getRoot().getAs<JsonTree>("tree").toXml())
    }

    @Test
    fun `undoes and redoes Tree edits around an intact pair`() = runTest {
        val cases: List<Pair<String, suspend (JsonTree) -> Unit>> = listOf(
            "insert after the pair" to { tree -> tree.edit(3, 3, JsonTree.TextNode("y")) },
            "delete the pair" to { tree -> tree.edit(1, 3) },
            "replace the pair" to { tree -> tree.edit(1, 3, JsonTree.TextNode("y")) },
        )
        for ((name, fn) in cases) {
            val document = Document("surrogate-index")
            document.updateAsync { root, _ -> root.setNewTree("tree", surrogateTreeRoot()) }.await()
            val original = document.getRoot().getAs<JsonTree>("tree").toXml()

            document.updateAsync { root, _ -> fn(root.getAs<JsonTree>("tree")) }.await()
            val edited = document.getRoot().getAs<JsonTree>("tree").toXml()

            document.history.undoAsync().await()
            assertEquals(original, document.getRoot().getAs<JsonTree>("tree").toXml(), name)
            document.history.redoAsync().await()
            assertEquals(edited, document.getRoot().getAs<JsonTree>("tree").toXml(), name)
        }
    }

    // ---- Initial tree content (setNewTree / buildDescendants) ----
    //
    // An initial tree's text reaches the CRDT through `buildRoot` ->
    // `buildDescendants`, never through `JsonTree.edit`'s
    // `validateTextNodes` -- so it needs its own lone-surrogate case, the
    // same way JS's `buildDescendants` calls `validateTextNode` (ported from
    // `6932da6a`, #1447).
    @Test
    fun `setNewTree rejects a lone surrogate in the initial text`() = runTest {
        val document = Document("surrogate-index")
        val result = document.updateAsync { root, _ ->
            root.setNewTree("tree", element("r") { text { "a$high" } })
        }.await()
        assertTrue(result.isFailure)
    }

    @Test
    fun `setNewTree accepts a whole emoji in the initial text`() = runTest {
        val document = Document("surrogate-index")
        document.updateAsync { root, _ ->
            root.setNewTree("tree", element("r") { element("p") { text { "a😀b" } } })
        }.await()
        assertEquals(
            "<r><p>a😀b</p></r>",
            document.getRoot().getAs<JsonTree>("tree").toXml(),
        )
    }

    @Test
    fun `setNewTree rejects a lone surrogate nested two levels deep`() = runTest {
        val document = Document("surrogate-index")
        val result = document.updateAsync { root, _ ->
            root.setNewTree(
                "tree",
                element("r") { element("section") { element("p") { text { "${low}b" } } } },
            )
        }.await()
        assertTrue(result.isFailure)
    }

    // ---- Text counterpart of "undoes and redoes Tree edits around an
    // intact pair" ----
    @Test
    fun `undoes and redoes Text edits around an intact pair`() = runTest {
        val cases: List<Pair<String, suspend (JsonText) -> Unit>> = listOf(
            "insert after the pair" to { text -> text.edit(2, 2, "y") },
            "delete the pair" to { text -> text.edit(0, 2, "") },
            "replace the pair" to { text -> text.edit(0, 2, "y") },
        )
        for ((name, fn) in cases) {
            val document = Document("surrogate-index")
            document.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, surrogateText)
            }.await()
            val original = document.getRoot().getAs<JsonText>("text").toString()

            document.updateAsync { root, _ -> fn(root.getAs<JsonText>("text")) }.await()
            val edited = document.getRoot().getAs<JsonText>("text").toString()

            document.history.undoAsync().await()
            assertEquals(original, document.getRoot().getAs<JsonText>("text").toString(), name)
            document.history.redoAsync().await()
            assertEquals(edited, document.getRoot().getAs<JsonText>("text").toString(), name)
        }
    }

    // ---- Document wire-delivery regression locks (push/pull, with History) ----
    //
    // Ports the JS `surrogate_index_test.ts` cases under "Mid-surrogate-pair
    // indexes the document computed" (`6932da6a`, #1447) that were missing or
    // under-covered here: these go through a real [Document] (sender and
    // receiver, or two replicas) instead of a hand-built [CrdtTree], so
    // [dev.yorkie.document.history.History]'s own undo/redo stack is real and
    // exercised, not just the operation in isolation.

    // Remote operations carry CRDT positions and never resolve an index, so
    // an operation an older client minted before an intervening edit shifted
    // the index space must still apply even though it now lands inside a
    // pair -- and must not break the receiver's own pending undo/redo.
    // `push`/`pull` hand back the decoded operations before delivery, so the
    // position is shifted here exactly the way an older client's own
    // (now-stale) index would have decoded.
    @Test
    fun `a remote Tree op from an older client lands mid-pair without breaking undo redo`() =
        runTest {
            val sender = Document("surrogate-index")
            val receiver = Document("surrogate-index")
            sender.setActor("000000000000000000000001")
            receiver.setActor("000000000000000000000002")
            sender.updateAsync { root, _ -> root.setNewTree("tree", surrogateTreeRoot()) }.await()
            pull(receiver, push(sender))

            // The receiver makes a local edit it will later undo and redo.
            receiver.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(4, 4, JsonTree.TextNode("z"))
            }.await()

            // The sender inserts right after the emoji -- a valid index.
            sender.updateAsync { root, _ ->
                root.getAs<JsonTree>("tree").edit(3, 3, JsonTree.TextNode("y"))
            }.await()
            val shifted = push(sender).map { change ->
                change.copy(
                    operations = change.operations.map { op ->
                        when (op) {
                            is TreeEditOperation -> op.copy(
                                fromPos = op.fromPos.copy(
                                    leftSiblingID = op.fromPos.leftSiblingID.copy(
                                        offset = op.fromPos.leftSiblingID.offset - 1,
                                    ),
                                ),
                                toPos = op.toPos.copy(
                                    leftSiblingID = op.toPos.leftSiblingID.copy(
                                        offset = op.toPos.leftSiblingID.offset - 1,
                                    ),
                                ),
                            )
                            else -> op
                        }
                    },
                )
            }
            pull(receiver, shifted)

            // The edit landed inside the pair, so the emoji no longer
            // survives -- what the lone half becomes is a Go/JS divergence
            // (yorkie#2065), not pinned here.
            val applied = receiver.getRoot().getAs<JsonTree>("tree").toXml()
            assertFalse(applied.contains("😀"), "the remote edit split the pair")

            receiver.history.undoAsync().await()
            assertEquals(
                applied.replace("z", ""),
                receiver.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            receiver.history.redoAsync().await()
            assertEquals(applied, receiver.getRoot().getAs<JsonTree>("tree").toXml())
        }

    // The Text counterpart of the Tree case above.
    @Test
    fun `a remote Text op from an older client lands mid-pair without breaking undo redo`() =
        runTest {
            val sender = Document("surrogate-index")
            val receiver = Document("surrogate-index")
            sender.setActor("000000000000000000000001")
            receiver.setActor("000000000000000000000002")
            sender.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, surrogateText)
            }.await()
            pull(receiver, push(sender))

            receiver.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(3, 3, "z")
            }.await()

            sender.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(2, 2, "y") }.await()
            val shifted = push(sender).map { change ->
                change.copy(
                    operations = change.operations.map { op ->
                        when (op) {
                            is EditOperation -> op.copy(
                                fromPos = op.fromPos.copy(
                                    relativeOffSet = op.fromPos.relativeOffSet - 1,
                                ),
                                toPos = op.toPos.copy(
                                    relativeOffSet = op.toPos.relativeOffSet - 1,
                                ),
                            )
                            else -> op
                        }
                    },
                )
            }
            pull(receiver, shifted)

            val applied = receiver.getRoot().getAs<JsonText>("text").toString()
            assertFalse(applied.contains("😀"), "the remote edit split the pair")

            receiver.history.undoAsync().await()
            assertEquals(
                applied.replace("z", ""),
                receiver.getRoot().getAs<JsonText>("text").toString(),
            )
            receiver.history.redoAsync().await()
            assertEquals(applied, receiver.getRoot().getAs<JsonText>("text").toString())
        }

    // Applying a change builds its reverse too, remote or not, from indexes
    // the receiver computes on its OWN tree. Every edit below is at a valid
    // caller index, but on `a` the reverse of `b`'s emoji insert ends inside
    // one of `a`'s own emojis -- checking that index would refuse the
    // remote change outright, and it must not.
    @Test
    fun `applies a remote Tree change whose reverse lands inside a pair`() = runTest {
        val a = Document("surrogate-index")
        val b = Document("surrogate-index")
        a.setActor("000000000000000000000002")
        b.setActor("000000000000000000000001")
        a.updateAsync { root, _ ->
            root.setNewTree(
                "tree",
                element("r") {
                    element("section") {
                        element("p") { text { "a😀b" } }
                        element("p") { text { "c😀d" } }
                    }
                },
            )
        }.await()
        crossSync(a, b)

        b.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(1, 2, JsonTree.TextNode("😀"))
        }.await()
        a.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(11, 11, JsonTree.TextNode("x"))
        }.await()
        a.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(0, 2, JsonTree.TextNode("x"))
        }.await()
        a.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").edit(7, 7, JsonTree.TextNode("😀"))
        }.await()
        crossSync(a, b)

        val converged = a.getRoot().getAs<JsonTree>("tree").toXml()
        assertEquals(converged, b.getRoot().getAs<JsonTree>("tree").toXml())

        // Strengthens the JS case: the receiving replica's own undo/redo
        // (of the local edit whose reverse landed inside the pair) must
        // still run and stay convergent afterward.
        a.history.undoAsync().await()
        crossSync(a, b)
        assertEquals(
            a.getRoot().getAs<JsonTree>("tree").toXml(),
            b.getRoot().getAs<JsonTree>("tree").toXml(),
        )
        a.history.redoAsync().await()
        crossSync(a, b)
        assertEquals(converged, a.getRoot().getAs<JsonTree>("tree").toXml())
        assertEquals(converged, b.getRoot().getAs<JsonTree>("tree").toXml())
    }

    // ---- Crafted-wire regression locks ----
    //
    // Indexes the document computes itself -- a remote op's own position, an
    // undo reconciled against a concurrent remote edit, a reverse builder
    // resolved on the post-edit tree -- must never throw, even though the
    // same index is refused at a checked (JsonTree) entry point. Each test
    // below hand-builds a CrdtTree the way TreeEditOperationReverseTest does,
    // so a crafted or reconciled position can be constructed directly,
    // bypassing JsonTree's checked entry points exactly as a remote apply
    // or an internal reconcile would.
    private var craftedLamport = 1L

    private fun craftedTicket() = TimeTicket(craftedLamport++, 0u, "actor-0")

    /** Builds `<r><p>[initialText]</p></r>` as a single text insertion. */
    private fun buildCraftedTreeRoot(initialText: String): Pair<CrdtTree, CrdtRoot> {
        val rootTicket = craftedTicket()
        val tree =
            CrdtTree(CrdtTreeElement(CrdtTreeNodeID(rootTicket, 0), DEFAULT_ROOT_TYPE), rootTicket)
        val obj = CrdtObject(createdAt = craftedTicket(), memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        root.rootObject.set("tree", tree, craftedTicket())
        root.registerElement(tree, root.rootObject)

        fun craftedEdit(
            fromIndex: Int,
            toIndex: Int,
            contents: List<CrdtTreeNode>?,
        ) {
            val (fromPos, toPos) = tree.indexRangeToPosRange(fromIndex to toIndex)
            TreeEditOperation(
                parentCreatedAt = rootTicket,
                fromPos = fromPos,
                toPos = toPos,
                contents = contents,
                splitLevel = 0,
                executedAt = craftedTicket(),
            ).execute(root, OpSource.Local, null)
        }

        craftedEdit(0, 0, listOf(CrdtTreeElement(CrdtTreeNodeID(craftedTicket(), 0), "p")))
        craftedEdit(1, 1, listOf(CrdtTreeText(CrdtTreeNodeID(craftedTicket(), 0), initialText)))
        return tree to root
    }

    // Remote operations carry CRDT positions and never resolve an index, so
    // an operation minted by an older client with a mid-pair offset must
    // still apply -- this is the shape a `TreeEditOperation` decoded off the
    // wire takes: its fromPos/toPos are used directly, never re-resolved
    // through findPos.
    @Test
    fun `a crafted remote op at a mid-pair index applies without throwing`() {
        val (tree, root) = buildCraftedTreeRoot("$high${low}x")
        assertTrue(
            runCatching { tree.findPos(2) }.isFailure,
            "index 2 is mid-pair before the crafted op",
        )

        val (midFrom, midTo) = tree.indexRangeToPosRange(2 to 2)
        val craftedSplit = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = midFrom,
            toPos = midTo,
            contents = null,
            splitLevel = 1,
            executedAt = craftedTicket(),
        )
        craftedSplit.execute(root, OpSource.Remote, null)
    }

    // Ports JS `surrogate_index_test.ts`'s `builds reverse operations at
    // indexes inside a pair` (`6932da6a`, #1447): the reverse builders are
    // called directly, at a hand-picked index no real edit could leave
    // mid-pair by the time it is measured (an edit AT that index would
    // itself split the pair) -- the emoji here is never touched by any
    // edit, only named by index in the reverse-builder calls.
    @Test
    fun `builds reverse operations at indexes inside a pair`() {
        val (tree, root) = buildCraftedTreeRoot("$high${low}x")
        assertTrue(runCatching { tree.findPos(2) }.isFailure, "index 2 is mid-pair")

        val op = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPosUnchecked(1),
            toPos = tree.findPosUnchecked(1),
            contents = null,
            splitLevel = 1,
            executedAt = craftedTicket(),
        )

        // The copy reverse of an insertion ends inside the pair.
        assertNotNull(
            op.toReverseOperationForTest(
                tree,
                tree.findPosUnchecked(1),
                1,
                insertedContentSize = 1,
            ),
        )
        // A merge reverse (a split) starts inside it.
        assertNotNull(
            op.toReverseOperationForTest(tree, tree.findPosUnchecked(1), 2, mergeLevel = 1),
        )
        // A split reverse starts or ends inside it.
        assertNotNull(op.toSplitReverseOperationForTest(tree, 2, 1, 1))
        assertNotNull(op.toSplitReverseOperationForTest(tree, 1, 1, 1))
    }

    @Test
    fun `runs a reconciled Tree undo whose index lands inside a pair`() {
        val (tree, root) = buildCraftedTreeRoot("0$high${low}123456789")
        assertTrue(
            runCatching { tree.findPos(3) }.isFailure,
            "index 3 is mid-pair before reconciliation",
        )

        // A hypothetical earlier undo range (3, 4), hand-set directly --
        // fromPos/toPos are placeholders: execute() re-resolves isUndoOp
        // positions from undoFromOffset/undoToOffset, never from these.
        val undo = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPosUnchecked(0),
            toPos = tree.findPosUnchecked(0),
            contents = null,
            splitLevel = 0,
            executedAt = craftedTicket(),
            undoFromOffset = 3,
            undoToOffset = 4,
        )

        // A remote zero-width insert of 2 UTF-16 units right before the
        // emoji shifts the undo range by 2, landing it exactly on the
        // emoji's own (now-shifted) mid-pair index.
        val (insFrom, insTo) = tree.indexRangeToPosRange(1 to 1)
        TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = insFrom,
            toPos = insTo,
            contents = listOf(CrdtTreeText(CrdtTreeNodeID(craftedTicket(), 0), "zz")),
            splitLevel = 0,
            executedAt = craftedTicket(),
        ).execute(root, OpSource.Remote, null)

        undo.reconcileOperation(1, 1, 2)
        assertEquals(5, undo.undoFromOffset)
        assertEquals(6, undo.undoToOffset)
        assertTrue(
            runCatching {
                tree.findPos(5)
            }.isFailure,
            "the reconciled index is mid-pair in the live tree",
        )

        undo.execute(root, OpSource.UndoRedo, null)
    }
}
