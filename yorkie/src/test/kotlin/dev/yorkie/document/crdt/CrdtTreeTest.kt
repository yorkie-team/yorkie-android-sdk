package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.issueTime
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

class CrdtTreeTest {

    private lateinit var target: CrdtTree

    @Before
    fun setUp() {
        target = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())
    }

    private fun initializeTarget() {
        target = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())
    }

    @Test
    fun `should create CrdtTreeNode`() {
        val node = CrdtTreeText(DIP, "hello")
        assertEquals(DIP, node.id)
        assertEquals(DEFAULT_TEXT_TYPE, node.type)
        assertEquals("hello", node.value)
        assertEquals(5, node.visibleSize)
        assertTrue(node.isText)
        assertFalse(node.isRemoved)
    }

    @Test
    fun `should split CrdtTreeNode`() {
        val para = CrdtTreeElement(DIP, "p")
        para.append(CrdtTreeText(DIP, "helloyorkie"))
        assertEquals("<p>helloyorkie</p>", para.toXml())
        assertEquals(11, para.visibleSize)
        assertFalse(para.isText)

        val left = para.children.first()
        val right = left.splitText(5, 0).first
        assertEquals(11, para.visibleSize)
        assertFalse(para.isText)
        assertEquals("hello", left.value)
        assertEquals("yorkie", right?.value)
        assertEquals(CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0), left.id)
        assertEquals(CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 5), right?.id)
    }

    @Test
    fun `should convert to XML`() {
        val text = CrdtTreeText(DIP, "hello")
        assertEquals("hello", text.toXml())

        val elem = CrdtTreeElement(DIP, "p")
        elem.append(text)
        assertEquals("<p>hello</p>", elem.toXml())

        val elemWithAttrs = CrdtTreeElement(DIP, "p")
        elemWithAttrs.append(CrdtTreeText(DIP, "hello"))
        elemWithAttrs.setAttributes(mapOf("b" to "\"t\"", "i" to "true"), TimeTicket.MaxTimeTicket)
        assertEquals("<p b=\"\"t\"\" i=\"true\">hello</p>", elemWithAttrs.toXml())

        elemWithAttrs.setAttributes(
            mapOf("img" to "{\"src\":\"yorkie.png\"}"),
            TimeTicket.MaxTimeTicket,
        )
        assertEquals(
            "<p b=\"\"t\"\" i=\"true\" img=\"{\"src\":\"yorkie.png\"}\">hello</p>",
            elemWithAttrs.toXml(),
        )
    }

    @Test
    fun `should insert nodes with edit`() {
        //       0
        // <root> </root>
        assertTrue(target.size == 0)
        assertEquals("<root></root>", target.toXml())

        //           1
        // <root> <p> </p> </root>
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        assertEquals("<root><p></p></root>", target.toXml())
        assertEquals(2, target.root.visibleSize)

        //           1
        // <root> <p> h e l l o </p> </root>
        target.edit(1 to 1, CrdtTreeText(issuePos(), "hello").toList())
        assertEquals("<root><p>hello</p></root>", target.toXml())
        assertEquals(7, target.root.visibleSize)

        //       0   1 2 3 4 5 6    7   8 9  10 11 12 13    14
        // <root> <p> h e l l o </p> <p> w  o  r  l  d  </p>  </root>
        val p = CrdtTreeElement(issuePos(), "p").apply {
            insertAt(0, CrdtTreeText(issuePos(), "world"))
        }
        target.edit(7 to 7, p.toList())
        assertEquals("<root><p>hello</p><p>world</p></root>", target.toXml())
        assertEquals(14, target.root.visibleSize)

        //       0   1 2 3 4 5 6 7    8   9 10 11 12 13 14    15
        // <root> <p> h e l l o ! </p> <p> w  o  r  l  d  </p>  </root>
        target.edit(6 to 6, CrdtTreeText(issuePos(), "!").toList())
        assertEquals("<root><p>hello!</p><p>world</p></root>", target.toXml())
        assertEquals(15, target.root.visibleSize)

        target.edit(6 to 6, CrdtTreeText(issuePos(), "~").toList())
        assertEquals("<root><p>hello~!</p><p>world</p></root>", target.toXml())
        assertEquals(16, target.root.visibleSize)
    }

    @Test
    fun `should delete text nodes with edit`() {
        // 01. Create a tree with 2 paragraphs.
        //       0   1 2 3    4   5 6 7    8
        // <root> <p> a b </p> <p> c d </p> </root>
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        target.edit(4 to 4, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(5 to 5, CrdtTreeText(issuePos(), "cd").toList())
        assertEquals("<root><p>ab</p><p>cd</p></root>", target.toXml())
        assertEquals(8, target.root.visibleSize)

        // 02. delete b from first paragraph
        //       0   1 2    3   4 5 6    7
        // <root> <p> a </p> <p> c d </p> </root>
        target.edit(2 to 3, null)
        assertEquals("<root><p>a</p><p>cd</p></root>", target.toXml())
    }

    @Test
    fun `should delete tree nodes with edit`() {
        // 01. Create a tree with 2 paragraphs.
        //       0   1 2 3    4   5 6 7    8
        // <root> <p> a b </p> <p> c d </p> </root>
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        target.edit(4 to 4, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(5 to 5, CrdtTreeText(issuePos(), "cd").toList())
        assertEquals("<root><p>ab</p><p>cd</p></root>", target.toXml())
        assertEquals(8, target.root.visibleSize)

        // 02. delete the first paragraph
        //       0   1 2 3    4
        // <root> <p> c d </p> </root>
        target.edit(0 to 4, null)
        assertEquals("<root><p>cd</p></root>", target.toXml())
        assertEquals(4, target.root.visibleSize)

        // 03. add a new paragraph
        //       0   1 2 3    4   5 6 7    8
        // <root> <p> e f </p> <p> c d </p> </root>
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ef").toList())
        assertEquals("<root><p>ef</p><p>cd</p></root>", target.toXml())
        assertEquals(8, target.root.visibleSize)

        // 04. delete all paragraphs
        target.edit(0 to 8, null)
        assertEquals("<root></root>", target.toXml())
        assertEquals(0, target.root.visibleSize)

        // 05. add a new paragraph
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "gh").toList())
        assertEquals("<root><p>gh</p></root>", target.toXml())
        assertEquals(4, target.root.visibleSize)
    }

    @Test
    fun `should delete nodes between element nodes with edit`() {
        // 01. Create a tree with 2 paragraphs.
        //       0   1 2 3    4   5 6 7    8
        // <root> <p> a b </p> <p> c d </p> </root>
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        target.edit(4 to 4, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(5 to 5, CrdtTreeText(issuePos(), "cd").toList())
        assertEquals("<root><p>ab</p><p>cd</p></root>", target.toXml())

        // 02. delete b, c and the second paragraph.
        //       0   1 2    3   4 5 6    7
        // <root> <p> a </p> <p> c d </p> </root>
        target.edit(2 to 6, null)
        assertEquals("<root><p>ad</p></root>", target.toXml())

        // 03. insert a new text node at the start of the first paragraph.
        target.edit(1 to 1, CrdtTreeText(issuePos(), "@").toList())
        assertEquals("<root><p>@ad</p></root>", target.toXml())
    }

    @Test
    fun `should delete nodes between elements in different level with edit`() {
        // 01. Create a tree with 2 paragraphs.
        //       0   1   2 3 4    5    6   7 8 9    10
        // <root> <p> <b> a b </b> </p> <p> c d </p>  </root>
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeElement(issuePos(), "b").toList())
        target.edit(2 to 2, CrdtTreeText(issuePos(), "ab").toList())
        target.edit(6 to 6, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(7 to 7, CrdtTreeText(issuePos(), "cd").toList())
        assertEquals("<root><p><b>ab</b></p><p>cd</p></root>", target.toXml())

        // 02. delete b, c and second paragraph.
        //       0   1   2 3 4    5
        // <root> <p> <b> a d </b> </root>
        target.edit(3 to 8, null)
        assertEquals("<root><p><b>ad</b></p></root>", target.toXml())
    }

    @Test
    fun `should find the closest TreePos when parentNode or leftSiblingNode does not exist`() {
        val pNode = CrdtTreeElement(issuePos(), "p")
        val textNode = CrdtTreeText(issuePos(), "ab")

        //       0   1 2 3    4
        // <root> <p> a b </p> </root>
        target.edit(0 to 0, pNode.toList())
        target.edit(1 to 1, textNode.toList())
        assertEquals("<root><p>ab</p></root>", target.toXml())

        // Find the closest index.TreePos when leftSiblingNode in crdt.TreePos is removed.
        //       0   1    2
        // <root> <p> </p> </root>
        target.edit(1 to 3, null)
        assertEquals("<root><p></p></root>", target.toXml())

        var (parent, left) = target.findNodesAndSplitText(
            CrdtTreePos(pNode.id, textNode.id),
            issueTime(),
        ).first
        assertEquals(1, target.toIndex(parent, left))

        // Find the closest index.TreePos when parentNode in crdt.TreePos is removed.
        //       0
        // <root> </root>
        target.edit(0 to 2, null)
        assertEquals("<root></root>", target.toXml())

        target.findNodesAndSplitText(CrdtTreePos(pNode.id, textNode.id), issueTime()).first.also {
            parent = it.first
            left = it.second
        }
        assertEquals(0, target.toIndex(parent, left))
    }

    @Test
    fun `should split text nodes`() {
        // 00. Create a tree with 2 paragraphs.
        //       0   1     6     11
        // <root> <p> hello world  </p> </root>
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "helloworld").toList())
        assertEquals("<root><p>helloworld</p></root>", target.toXml())
        assertEquals(12, target.root.visibleSize)

        // 01. Split left side of 'helloworld' - no change expected.
        target.edit(1 to 1, null)
        assertEquals("<root><p>helloworld</p></root>", target.toXml())
        assertEquals(12, target.root.visibleSize)

        // 02. Split right side of 'helloworld' - no change expected.
        target.edit(11 to 11, null)
        assertEquals("<root><p>helloworld</p></root>", target.toXml())
        assertEquals(12, target.root.visibleSize)

        // 03. Split 'helloworld' into 'hello' and 'world'.
        target.edit(6 to 6, null)
        assertEquals("<root><p>helloworld</p></root>", target.toXml())
        assertEquals(12, target.root.visibleSize)
    }

    @Test
    fun `should split element nodes level 1`() {
        //       0   1 2 3    4
        // <root> <p> a b </p> </root>

        // 01. Split position 1.
        initializeTarget()
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        assertEquals("<root><p>ab</p></root>", target.toXml())
        target.edit(1 to 1, null, 1)
        assertEquals("<root><p></p><p>ab</p></root>", target.toXml())
        assertEquals(6, target.size)

        // 02. Split position 2.
        initializeTarget()
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        assertEquals("<root><p>ab</p></root>", target.toXml())
        target.edit(2 to 2, null, 1)
        assertEquals("<root><p>a</p><p>b</p></root>", target.toXml())
        assertEquals(6, target.size)

        // 03. Split position 3.
        initializeTarget()
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        assertEquals("<root><p>ab</p></root>", target.toXml())
        target.edit(3 to 3, null, 1)
        assertEquals("<root><p>ab</p><p></p></root>", target.toXml())
        assertEquals(6, target.size)
    }

    @Test
    fun `should split element nodes multi-level`() {
        //       0   1   2 3 4    5    6
        // <root> <p> <b> a b </b> </p> </root>

        // 01. Split nodes level 1.
        initializeTarget()
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeElement(issuePos(), "b").toList())
        target.edit(2 to 2, CrdtTreeText(issuePos(), "ab").toList())
        assertEquals("<root><p><b>ab</b></p></root>", target.toXml())
        target.edit(3 to 3, null, 1)
        assertEquals("<root><p><b>a</b><b>b</b></p></root>", target.toXml())

        // 02. Split nodes level 2.
        initializeTarget()
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeElement(issuePos(), "b").toList())
        target.edit(2 to 2, CrdtTreeText(issuePos(), "ab").toList())
        assertEquals("<root><p><b>ab</b></p></root>", target.toXml())
        target.edit(3 to 3, null, 2)
        assertEquals("<root><p><b>a</b></p><p><b>b</b></p></root>", target.toXml())
    }

    @Test
    fun `should split and merge element nodes by edit`() {
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "abcd").toList())
        assertEquals("<root><p>abcd</p></root>", target.toXml())
        assertEquals(6, target.size)

        //       0   1 2 3    4   5 6 7    8
        // <root> <p> a b </p> <p> c d </p> </root>
        target.edit(3 to 3, null, 1)
        assertEquals("<root><p>ab</p><p>cd</p></root>", target.toXml())
        assertEquals(8, target.size)

        target.edit(3 to 5, null)
        assertEquals("<root><p>abcd</p></root>", target.toXml())
        assertEquals(6, target.size)
    }

    @Test
    @Ignore("should be resolved after the JS SDK implementation")
    fun `should merge and edit different levels with edit`() {
        fun initializeTree() {
            setUp()
            target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
            target.edit(1 to 1, CrdtTreeElement(issuePos(), "b").toList())
            target.edit(2 to 2, CrdtTreeElement(issuePos(), "i").toList())
            target.edit(3 to 3, CrdtTreeText(issuePos(), "ab").toList())
            assertEquals("<root><p><b><i>ab</i></b></p></root>", target.toXml())
        }

        // 01. edit between two element nodes in the same hierarchy.
        //       0   1   2   3 4 5    6    7    8
        // <root> <p> <b> <i> a b </i> </b> </p> </root>
        initializeTree()
        target.edit(5 to 6, null)
        assertEquals("<root><p><b>ab</b></p></root>", target.toXml())

        // 02. edit between two element nodes in same hierarchy.
        initializeTree()
        target.edit(6 to 7, null)
        assertEquals("<root><p><i>ab</i></p></root>", target.toXml())

        // 03. edit between text and element node in different hierarchy.
        initializeTree()
        target.edit(4 to 6, null)
        assertEquals("<root><p><b>a</b></p></root>", target.toXml())

        // 04. edit between text and element node in different hierarchy.
        initializeTree()
        target.edit(5 to 7, null)
        assertEquals("<root><p>ab</p></root>", target.toXml())

        // 05. edit between text and element node in different hierarchy.
        initializeTree()
        target.edit(4 to 7, null)
        assertEquals("<root><p>a</p></root>", target.toXml())

        // 06. edit between text and element node in different hierarchy.
        initializeTree()
        target.edit(3 to 7, null)
        assertEquals("<root><p></p></root>", target.toXml())

        // 07. edit between text and element node in same hierarchy.
        setUp()
        target.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        target.edit(4 to 4, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(5 to 5, CrdtTreeElement(issuePos(), "b").toList())
        target.edit(6 to 6, CrdtTreeText(issuePos(), "cd").toList())
        target.edit(10 to 10, CrdtTreeElement(issuePos(), "p").toList())
        target.edit(11 to 11, CrdtTreeText(issuePos(), "ef").toList())
        assertEquals("<root><p>ab</p><p><b>cd</b></p><p>ef</p></root>", target.toXml())

        target.edit(9 to 10, null)
        assertEquals("<root><p>ab</p><b>cd</b><p>ef</p></root>", target.toXml())
    }

    private fun issuePos(offset: Int = 0) = CrdtTreeNodeID(issueTime(), offset)

    private fun CrdtTreeNode.toList() = listOf(this)

    private fun CrdtTree.edit(
        range: Pair<Int, Int>,
        nodes: List<CrdtTreeNode>?,
        splitLevel: Int = 0,
    ) {
        val fromPos = findPos(range.first)
        val toPos = findPos(range.second)
        edit(fromPos to toPos, nodes, splitLevel, issueTime(), ::issueTime)
    }

    companion object {
        private val DIP = CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0)
    }
}
