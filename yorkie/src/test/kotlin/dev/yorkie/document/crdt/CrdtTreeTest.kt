package dev.yorkie.document.crdt

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.yorkie.document.crdt.CrdtTreePos.Companion.InitialCrdtTreePos as ITP

class CrdtTreeTest {

    private lateinit var target: CrdtTree

    @Before
    fun setUp() {
        target = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())
    }

    @Test
    fun `should create CrdtTreeNode`() {
        val node = CrdtTreeText(ITP, "hello")
        assertEquals(ITP, node.pos)
        assertEquals(DEFAULT_TEXT_TYPE, node.type)
        assertEquals("hello", node.value)
        assertEquals(5, node.size)
        assertTrue(node.isText)
        assertFalse(node.isRemoved)
    }

    @Test
    fun `should split CrdtTreeNode`() {
        val para = CrdtTreeElement(ITP, "p")
        para.append(CrdtTreeText(ITP, "helloyorkie"))
        assertEquals("<p>helloyorkie</p>", para.toXml())
        assertEquals(11, para.size)
        assertFalse(para.isText)

        val left = para.children.first()
        val right = left.split(5)
        assertEquals(11, para.size)
        assertFalse(para.isText)
        assertEquals("hello", left.value)
        assertEquals("yorkie", right?.value)
        assertEquals(CrdtTreePos(TimeTicket.InitialTimeTicket, 0), left.pos)
        assertEquals(CrdtTreePos(TimeTicket.InitialTimeTicket, 5), right?.pos)
    }

    @Test
    fun `should insert nodes with editByIndex`() {
        //       0
        // <root> </root>
        assertTrue(target.isEmpty())
        assertEquals("<root></root>", target.toXml())
        assertEquals(listOf("root"), target.toList())

        //           1
        // <root> <p> </p> </root>
        target.editByIndex(0 to 0, CrdtTreeElement(issuePos(), "p"), issueTime())
        assertEquals("<root><p></p></root>", target.toXml())
        assertEquals(listOf("p", "root"), target.toList())
        assertEquals(2, target.root.size)

        //           1
        // <root> <p> h e l l o </p> </root>
        target.editByIndex(1 to 1, CrdtTreeText(issuePos(), "hello"), issueTime())
        assertEquals("<root><p>hello</p></root>", target.toXml())
        assertEquals(listOf("text.hello", "p", "root"), target.toList())
        assertEquals(7, target.root.size)

        //       0   1 2 3 4 5 6    7   8 9  10 11 12 13    14
        // <root> <p> h e l l o </p> <p> w  o  r  l  d  </p>  </root>
        val p = CrdtTreeElement(issuePos(), "p").apply {
            insertAt(0, CrdtTreeText(issuePos(), "world"))
        }
        target.editByIndex(7 to 7, p, issueTime())
        assertEquals("<root><p>hello</p><p>world</p></root>", target.toXml())
        assertEquals(listOf("text.hello", "p", "text.world", "p", "root"), target.toList())
        assertEquals(14, target.root.size)

        //       0   1 2 3 4 5 6 7    8   9 10 11 12 13 14    15
        // <root> <p> h e l l o ! </p> <p> w  o  r  l  d  </p>  </root>
        target.editByIndex(6 to 6, CrdtTreeText(issuePos(), "!"), issueTime())
        assertEquals("<root><p>hello!</p><p>world</p></root>", target.toXml())
        assertEquals(
            listOf("text.hello", "text.!", "p", "text.world", "p", "root"),
            target.toList(),
        )
        assertEquals(15, target.root.size)

        target.editByIndex(6 to 6, CrdtTreeText(issuePos(), "~"), issueTime())
        assertEquals("<root><p>hello~!</p><p>world</p></root>", target.toXml())
        assertEquals(
            listOf("text.hello", "text.~", "text.!", "p", "text.world", "p", "root"),
            target.toList(),
        )
        assertEquals(16, target.root.size)
    }

    @Test
    fun `should delete text nodes with editByIndex`() {
        // 01. Create a tree with 2 paragraphs.
        //       0   1 2 3    4   5 6 7    8
        // <root> <p> a b </p> <p> c d </p> </root>
        target.editByIndex(0 to 0, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(1 to 1, CrdtTreeText(issuePos(), "ab"), issueTime())
        target.editByIndex(4 to 4, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(5 to 5, CrdtTreeText(issuePos(), "cd"), issueTime())
        assertEquals("<root><p>ab</p><p>cd</p></root>", target.toXml())
        assertEquals(listOf("text.ab", "p", "text.cd", "p", "root"), target.toList())
        assertEquals(8, target.root.size)

        // 02. delete b from first paragraph
        //       0   1 2    3   4 5 6    7
        // <root> <p> a </p> <p> c d </p> </root>
        target.editByIndex(2 to 3, null, issueTime())
        assertEquals("<root><p>a</p><p>cd</p></root>", target.toXml())
        assertEquals(listOf("text.a", "p", "text.cd", "p", "root"), target.toList())
        assertEquals(7, target.root.size)
    }

    @Test
    fun `should delete nodes between element nodes with editByIndex`() {
        // 01. Create a tree with 2 paragraphs.
        //       0   1 2 3    4   5 6 7    8
        // <root> <p> a b </p> <p> c d </p> </root>
        target.editByIndex(0 to 0, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(1 to 1, CrdtTreeText(issuePos(), "ab"), issueTime())
        target.editByIndex(4 to 4, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(5 to 5, CrdtTreeText(issuePos(), "cd"), issueTime())
        assertEquals("<root><p>ab</p><p>cd</p></root>", target.toXml())
        assertEquals(listOf("text.ab", "p", "text.cd", "p", "root"), target.toList())

        // 02. delete b, c and first paragraph.
        //       0   1 2 3    4
        // <root> <p> a d </p> </root>
        target.editByIndex(2 to 6, CrdtTreeText(issuePos()), issueTime())
        assertEquals("<root><p>ad</p></root>", target.toXml())

        // 03. insert a new text node at the start of the first paragraph.
        target.editByIndex(1 to 1, CrdtTreeText(issuePos(), "@"), issueTime())
        assertEquals("<root><p>@ad</p></root>", target.toXml())
    }

    @Test
    fun `should merge and edit different levels with editByIndex`() {
        fun initializeTree() {
            setUp()
            target.editByIndex(0 to 0, CrdtTreeElement(issuePos(), "p"), issueTime())
            target.editByIndex(1 to 1, CrdtTreeElement(issuePos(), "b"), issueTime())
            target.editByIndex(2 to 2, CrdtTreeElement(issuePos(), "i"), issueTime())
            target.editByIndex(3 to 3, CrdtTreeText(issuePos(), "ab"), issueTime())
            assertEquals("<root><p><b><i>ab</i></b></p></root>", target.toXml())
        }

        // 01. edit between two element nodes in the same hierarchy.
        //       0   1   2   3 4 5    6    7    8
        // <root> <p> <b> <i> a b </i> </b> </p> </root>
        initializeTree()
        target.editByIndex(5 to 6, null, issueTime())
        assertEquals("<root><p><b>ab</b></p></root>", target.toXml())

        // 02. edit between two element nodes in same hierarchy.
        initializeTree()
        target.editByIndex(6 to 7, null, issueTime())
        assertEquals("<root><p><i>ab</i></p></root>", target.toXml())

        // 03. edit between text and element node in different hierarchy.
        initializeTree()
        target.editByIndex(4 to 6, null, issueTime())
        assertEquals("<root><p><b>a</b></p></root>", target.toXml())

        // 04. edit between text and element node in different hierarchy.
        initializeTree()
        target.editByIndex(5 to 7, null, issueTime())
        assertEquals("<root><p>ab</p></root>", target.toXml())

        // 05. edit between text and element node in different hierarchy.
        initializeTree()
        target.editByIndex(4 to 7, null, issueTime())
        assertEquals("<root><p>a</p></root>", target.toXml())

        // 06. edit between text and element node in different hierarchy.
        initializeTree()
        target.editByIndex(3 to 7, null, issueTime())
        assertEquals("<root><p></p></root>", target.toXml())

        // 07. edit between text and element node in same hierarchy.
        setUp()
        target.editByIndex(0 to 0, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(1 to 1, CrdtTreeText(issuePos(), "ab"), issueTime())
        target.editByIndex(4 to 4, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(5 to 5, CrdtTreeElement(issuePos(), "b"), issueTime())
        target.editByIndex(6 to 6, CrdtTreeText(issuePos(), "cd"), issueTime())
        target.editByIndex(10 to 10, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(11 to 11, CrdtTreeText(issuePos(), "ef"), issueTime())
        assertEquals("<root><p>ab</p><p><b>cd</b></p><p>ef</p></root>", target.toXml())

        target.editByIndex(9 to 10, null, issueTime())
        assertEquals("<root><p>ab</p><b>cd</b><p>ef</p></root>", target.toXml())
    }

    @Test
    fun `should get correct index from CrdtTreePos`() {
        //     0  1  2   3 4 5    6   7   8
        // <root><p><b><i> a b </i></b></p></root>
        target.editByIndex(0 to 0, CrdtTreeElement(issuePos(), "p"), issueTime())
        target.editByIndex(1 to 1, CrdtTreeElement(issuePos(), "b"), issueTime())
        target.editByIndex(2 to 2, CrdtTreeElement(issuePos(), "i"), issueTime())
        target.editByIndex(3 to 3, CrdtTreeText(issuePos(), "ab"), issueTime())
        assertEquals("<root><p><b><i>ab</i></b></p></root>", target.toXml())

        var (from, to) = target.pathToPosRange(listOf(0))
        var fromIndex = target.toIndex(from)
        var toIndex = target.toIndex(to)
        assertEquals(7 to 8, fromIndex to toIndex)

        target.pathToPosRange(listOf(0, 0)).also {
            from = it.first
            to = it.second
        }
        fromIndex = target.toIndex(from)
        toIndex = target.toIndex(to)
        assertEquals(6 to 7, fromIndex to toIndex)

        target.pathToPosRange(listOf(0, 0, 0)).also {
            from = it.first
            to = it.second
        }
        fromIndex = target.toIndex(from)
        toIndex = target.toIndex(to)
        assertEquals(5 to 6, fromIndex to toIndex)

        var range = target.createRange(0, 5)
        assertEquals(0 to 5, target.rangeToIndex(range))

        range = target.createRange(5, 7)
        assertEquals(5 to 7, target.rangeToIndex(range))
    }

    private fun issuePos(offset: Int = 0) = CrdtTreePos(issueTime(), offset)

    private fun issueTime() = DummyContext.issueTimeTicket()

    private fun CrdtTree.toList() = map { node ->
        if (node.isText) {
            "${node.type}.${node.value}"
        } else {
            node.type
        }
    }

    companion object {

        private val DummyContext = ChangeContext(
            ChangeID.InitialChangeID,
            CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket)),
        )
    }
}
