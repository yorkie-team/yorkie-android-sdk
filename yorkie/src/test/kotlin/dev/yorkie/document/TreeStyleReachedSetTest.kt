package dev.yorkie.document

import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.json.JsonTree.TextNode
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DocSize
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the STYLE half of yorkie-js-sdk `tree_style_reached_set_test.ts`
 * (b69169c0, #1404; yorkie#2038/#2070, design doc
 * `concurrent-merge-split.md` §9 "Port specification") as JVM in-process
 * cross-sync tests: a style's reached set used to depend on which of two
 * concurrent changes (a structural edit and a style) arrived first, because
 * it was resolved in the receiving replica's own visible-index space.
 * `CrdtTree.styleTargets`/`declaredBoundaries`/`DeclaredLineage`
 * resolve it from the CHANGE's own positions instead, so both delivery
 * orders now converge.
 *
 * Scope: this class ports the named split∥style/merge∥style cases, the STYLE-flavored 10 of the JS source's 13
 * "complex suite" cases, and the style half of the level-2-split-before-range
 * and split-and-merge-in-one-change pairs. `removeStyle` shares
 * `styleTargets` with `style` (its own ~270-line duplicated traversal is
 * deleted), and the rest is ported here too: the 3
 * removeStyle-flavored complex-suite cases ('remove-style-across-merged
 * -anchor', 'remove-style-after-moved-anchor',
 * 'remove-style-from-side-moved-anchor'), the two named merge∥removeStyle
 * /split∥removeStyle cases, the removeStyle halves of the level-2/split
 * -merge pairs, and the by-path backwards-range rejection. The six
 * exhaustive scans live in `TreeStyleReachedSetScanTest`.
 */
class TreeStyleReachedSetTest {

    private val structuralActor = "000000000000000000000001"
    private val styleActor = "000000000000000000000002"
    private val seedActor = "000000000000000000000009"
    private val receiverAbActor = "00000000000000000000000a"
    private val receiverBaActor = "00000000000000000000000b"

    private val bold = mapOf("bold" to "x")
    private val italic = mapOf("italic" to "a")

    private fun p(value: String, attributes: Map<String, String>? = null): ElementNode =
        ElementNode("p", listOf(TextNode(value)), attributes.orEmpty())

    private val twoParagraphs = ElementNode("r", listOf(p("ab"), p("cd")))
    private val threeParagraphs = ElementNode("r", listOf(p("ab"), p("cd"), p("ef")))

    /**
     * `<r><p>ab</p><p>cd</p><p>ef</p></r>`, 12 wide inside the root -- the
     * flat base the named split/merge cases style across.
     */
    private val styleScanBase = threeParagraphs

    /**
     * [styleScanBase] with every paragraph already bold, so a removeStyle
     * has something to take off. Mirrors JS `styleScanBoldBase`.
     */
    private val styleScanBoldBase = ElementNode(
        "r",
        listOf(p("ab", bold), p("cd", bold), p("ef", bold)),
    )

    /**
     * `<r><p><p><p italic="a">abcd</p><p italic="a">efgh</p></p>
     * <p italic="a">ijkl</p></p></r>` -- where a level-2 split moves a half
     * into a NEW parent, unlike the flat scans' one-level split.
     */
    private val nestedLevel2Base = ElementNode(
        "r",
        listOf(
            ElementNode(
                "p",
                listOf(
                    ElementNode("p", listOf(p("abcd", italic), p("efgh", italic))),
                    p("ijkl", italic),
                ),
            ),
        ),
    )

    /**
     * `<r><p><p><p>abcd</p><p>efgh</p></p><p>ijkl</p></p></r>` -- no attrs,
     * the base the split-and-merge-in-one-change pair styles.
     */
    private val nestedScanBase = ElementNode(
        "r",
        listOf(
            ElementNode(
                "p",
                listOf(
                    ElementNode("p", listOf(p("abcd"), p("efgh"))),
                    p("ijkl"),
                ),
            ),
        ),
    )

    /**
     * [nestedScanBase] with every paragraph (including both wrapper levels)
     * already bold, so a removeStyle has something to take off. Mirrors JS
     * `nestedScanBase({b: 'x'})`.
     */
    private val nestedScanBoldBase = ElementNode(
        "r",
        listOf(
            ElementNode(
                "p",
                listOf(
                    ElementNode("p", listOf(p("abcd", bold), p("efgh", bold)), bold),
                    p("ijkl", bold),
                ),
                bold,
            ),
        ),
    )

    private fun newDoc(actor: String): Document {
        val doc = Document("test-doc")
        doc.setActor(actor)
        return doc
    }

    private suspend fun Document.xml(): String = getRoot().getAs<JsonTree>("t").toXml()

    private suspend fun Document.deliver(from: Document, changes: List<Change>) {
        applyChangePack(
            ChangePack(
                from.getKey(),
                CheckPoint.InitialCheckPoint,
                changes,
                null,
                false,
                VersionVector(),
            ),
        )
    }

    /**
     * Builds [root] (and, when [pre] is given, a further structural change
     * both concurrent authors already saw -- mirrors the JS harness's
     * `seedBase(root, pre)`) on a throwaway seed actor, then feeds the
     * resulting change(s) into every one of [targets] so each starts from an
     * identical state. No server: the SAME captured [Change] list is reused
     * across every target, exactly as `crossSync`/`exchange`
     * (`test/.../helper/Helper.kt`) already do for two/N already-live
     * documents.
     */
    private suspend fun seedInto(
        root: ElementNode,
        pre: (JsonTree.() -> Unit)?,
        vararg targets: Document,
    ) {
        val seed = newDoc(seedActor)
        seed.updateAsync { r, _ -> r.setNewTree("t", root) }.await()
        pre?.let { seed.updateAsync { r, _ -> r.getAs<JsonTree>("t").it() }.await() }
        val changes = seed.createChangePack().changes
        targets.forEach { target -> target.deliver(seed, changes) }
    }

    /** Everything two delivery orders have to agree on (JS `ReplayResult`). */
    private suspend fun Document.snapshot() = ReplayResult(xml(), getDocSize(), garbageLength)

    private data class ReplayResult(val xml: String, val docSize: DocSize, val garbageLength: Int)

    /**
     * Runs [structural] on a fresh structural-change author and [style] on a
     * fresh style-change author, both decided concurrently from [root] (and
     * [pre]), then replays the two resulting batches into a fresh replica in
     * EACH delivery order. Mirrors the JS harness's `concurrentTreeChanges` +
     * `replayOrder`.
     */
    private suspend fun replayBothOrders(
        root: ElementNode,
        pre: (JsonTree.() -> Unit)? = null,
        structural: JsonTree.() -> Unit,
        style: JsonTree.() -> Unit,
    ): Pair<ReplayResult, ReplayResult> {
        val docA = newDoc(structuralActor)
        val docB = newDoc(styleActor)
        val receiverAb = newDoc(receiverAbActor)
        val receiverBa = newDoc(receiverBaActor)
        seedInto(root, pre, docA, docB, receiverAb, receiverBa)

        docA.updateAsync { r, _ -> r.getAs<JsonTree>("t").structural() }.await()
        docB.updateAsync { r, _ -> r.getAs<JsonTree>("t").style() }.await()

        val changesA = docA.createChangePack().changes
        val changesB = docB.createChangePack().changes

        receiverAb.deliver(docA, changesA)
        receiverAb.deliver(docB, changesB)
        receiverBa.deliver(docB, changesB)
        receiverBa.deliver(docA, changesA)

        return receiverAb.snapshot() to receiverBa.snapshot()
    }

    private fun liveAttrDescs(doc: Document, withTicket: Boolean): List<String> {
        val tree = doc.getRootObject()["t"] as CrdtTree
        val descs = mutableListOf<String>()
        fun walk(node: CrdtTreeNode) {
            for (child in node.children) {
                if (child.isText) continue
                for (entry in child.getAttrs()) {
                    val base =
                        """${child.type} ${entry.key}="${entry.value}" removed=${entry.isRemoved}"""
                    descs.add(if (withTicket) "$base updatedAt=${entry.executedAt}" else base)
                }
                walk(child)
            }
        }
        walk(tree.root)
        return descs.sorted()
    }

    // A concurrent split of the paragraph a style covers. The style range ran
    // past the paragraph's end before the split existed, so it styles the
    // paragraph; the split then has to carry that onto both halves, whichever
    // change the replica applies first.
    @Test
    fun `styles both halves of a concurrently split paragraph`() = runTest {
        val (ab, ba) = replayBothOrders(
            root = styleScanBase,
            structural = { edit(6, 6, 1) },
            style = { style(5, 8, bold) },
        )
        assertEquals("""<r><p>ab</p><p bold="x">c</p><p bold="x">d</p><p>ef</p></r>""", ba.xml)
        assertEquals(ab, ba, "split-then-style diverges")
    }

    // A concurrent merge of the two paragraphs a style spans. The style
    // covered the first paragraph's End token and the second's Start token,
    // so both carry it -- the second as a tombstone, which only the ledger
    // shows.
    @Test
    fun `styles both paragraphs a concurrent merge joins`() = runTest {
        val (ab, ba) = replayBothOrders(
            root = styleScanBase,
            structural = { edit(1, 5) },
            style = { style(1, 6, bold) },
        )
        assertEquals("""<r><p bold="x">cd</p><p>ef</p></r>""", ba.xml)
        assertEquals(ab, ba, "merge-then-style diverges")
    }

    // The mirror: the merge removes the opening tag of the paragraph the
    // style range STARTS inside, so its children move into the paragraph
    // before it. The style covered only the paragraph it named, which the
    // merge turns into a tombstone, so nothing renders as styled.
    @Test
    fun `does not style the merge target a range start moved into`() = runTest {
        val (ab, ba) = replayBothOrders(
            root = styleScanBase,
            structural = { edit(1, 5) },
            style = { style(6, 8, bold) },
        )
        assertEquals("<r><p>cd</p><p>ef</p></r>", ba.xml)
        assertEquals(ab, ba, "merge-then-style styles the merge target")
    }

    // A level-2 split of the paragraph just BEFORE a style range (yorkie's
    // complex suite `concurrently-split-edit-test`, A -> B, split-2). The
    // style covered only `<p>efgh</p>`; the split carries the right half of
    // `<p>abcd</p>` into a NEW parent, so the traversal starting right after
    // `<p>abcd</p>` now passes that half's End token. The range never ran
    // past `<p>abcd</p>`'s end, so neither half may be styled.
    @Test
    fun `styles only the paragraph the range covered across a level-2 split before the range`() =
        runTest {
            val (ab, ba) = replayBothOrders(
                root = nestedLevel2Base,
                structural = { edit(5, 5, 2) },
                style = { style(8, 14, mapOf("bold" to "aa")) },
            )
            assertEquals(
                """<r><p><p><p italic="a">ab</p></p><p><p italic="a">cd</p>""" +
                    """<p bold="aa" italic="a">efgh</p></p><p italic="a">ijkl</p></p></r>""",
                ba.xml,
            )
            assertEquals(ab, ba, "split-then-style diverges")
        }

    // A split AND a merge in one change, both before a style range that
    // covers the result of both.
    @Test
    fun `styles both paragraphs the range covered before a split and merge in one change`() =
        runTest {
            val (ab, ba) = replayBothOrders(
                root = nestedScanBase,
                structural = {
                    edit(10, 10, 1)
                    edit(7, 9)
                },
                style = { style(0, 14, bold) },
            )
            assertEquals(
                """<r><p bold="x"><p bold="x"><p bold="x">abcde</p><p bold="x">fgh</p></p>""" +
                    """<p>ijkl</p></p></r>""",
                ba.xml,
            )
            assertEquals(ab, ba, "split-and-merge-then-style diverges")
        }

    /**
     * The merge cases of yorkie's test/complex suite, reproduced without a
     * server -- the STYLE-flavored 10 of 13 (see class KDoc). They pin what a style may NOT reach -- the
     * interloper a merge moved next to the range end, the descendants of one
     * -- and the reached-set resolution is what they consume. Mirrors
     * yorkie's `TestStyleReachedSetMatchesComplexSuite`.
     */
    private data class ComplexCase(
        val name: String,
        val base: ElementNode,
        val pre: (JsonTree.() -> Unit)? = null,
        val structural: JsonTree.() -> Unit,
        val style: JsonTree.() -> Unit,
        val want: String,
        val wantAttrs: List<String>? = null,
    )

    private val complexSuiteStyleCases = listOf(
        ComplexCase(
            name = "covering-merged-content",
            base = twoParagraphs,
            structural = { edit(0, 4) },
            style = { style(4, 8, bold) },
            want = """<r><p bold="x">cd</p></r>""",
        ),
        ComplexCase(
            name = "across-chained-merge",
            base = threeParagraphs,
            structural = {
                edit(7, 9)
                edit(3, 5)
            },
            style = {
                edit(12, 12, ElementNode("p"))
                style(0, 9, bold)
            },
            want = """<r><p bold="x">abcdef</p><p></p></r>""",
        ),
        ComplexCase(
            // The interloper inserted at the merged anchor stays UNSTYLED.
            name = "after-moved-anchor",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                style(0, 6, bold)
            },
            want = "<r><p></p>cd</r>",
        ),
        ComplexCase(
            name = "covers-own-insert-into-merged-range",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(5, 5, ElementNode("b"))
                style(0, 8, bold)
            },
            want = """<r><b bold="x"></b>cd</r>""",
        ),
        ComplexCase(
            name = "sibling-before-tombstone",
            base = ElementNode("r", listOf(ElementNode("b"), p("ab"), p("cd"))),
            structural = { edit(2, 7) },
            style = { style(0, 8, bold) },
            want = """<r><b bold="x"></b>cd</r>""",
        ),
        ComplexCase(
            name = "skips-interloper-descendants",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                edit(9, 9, ElementNode("b"))
                style(0, 6, bold)
            },
            want = "<r><p><b></b></p>cd</r>",
        ),
        ComplexCase(
            // The range ends inside the merged-away paragraph, so the
            // interloper the merge pulls next to that anchor stays out of
            // the reached set.
            name = "across-merged-anchor",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                style(0, 5, bold)
            },
            want = "<r><p></p>cd</r>",
            wantAttrs = emptyList(),
        ),
        ComplexCase(
            // <i> arrived in <p> through a merge both clients have seen, so a
            // range covering it still reaches it when a second merge lifts
            // it into the root.
            name = "covers-earlier-merged-child",
            base = ElementNode("r", listOf(p("ab"), ElementNode("s", listOf(ElementNode("i"))))),
            pre = { edit(3, 5) },
            structural = { edit(0, 1) },
            style = { style(0, 5, bold) },
            want = """<r>ab<i bold="x"></i></r>""",
            wantAttrs = listOf("""i bold="x" removed=false"""),
        ),
        ComplexCase(
            // The merge collapses the range, and the recovery hands back
            // exactly the writer's own insert -- which the range ended
            // inside, so it is styled.
            name = "style-from-side-moved-anchor",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                style(6, 9, bold)
            },
            want = """<r><p bold="x"></p>cd</r>""",
            wantAttrs = listOf("""p bold="x" removed=false"""),
        ),
        ComplexCase(
            // Both anchors sit inside the merged paragraph, so the resolved
            // range moves with the merge and stays ordered -- the recovery
            // must not widen it onto the writer's insert.
            name = "style-from-side-ordered-range",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                style(6, 7, bold)
            },
            want = "<r><p></p>cd</r>",
            wantAttrs = emptyList(),
        ),
    )

    @Test
    fun `matches the complex suite, style half`() = runTest {
        for (tc in complexSuiteStyleCases) {
            val docA = newDoc(structuralActor)
            val docB = newDoc(styleActor)
            val receiverAb = newDoc(receiverAbActor)
            val receiverBa = newDoc(receiverBaActor)
            seedInto(tc.base, tc.pre, docA, docB, receiverAb, receiverBa)

            docA.updateAsync { r, _ -> tc.structural(r.getAs<JsonTree>("t")) }.await()
            docB.updateAsync { r, _ -> tc.style(r.getAs<JsonTree>("t")) }.await()

            val changesA = docA.createChangePack().changes
            val changesB = docB.createChangePack().changes

            receiverAb.deliver(docA, changesA)
            receiverAb.deliver(docB, changesB)
            receiverBa.deliver(docB, changesB)
            receiverBa.deliver(docA, changesA)

            val abXml = receiverAb.xml()
            val baXml = receiverBa.xml()
            assertEquals(tc.want, baXml, tc.name)
            tc.wantAttrs?.let { wantAttrs ->
                // toXml shows neither an empty attribute container nor a
                // removal tombstone, so these are read off the CRDT
                // directly, with the entry tickets included for the order
                // comparison.
                assertEquals(
                    wantAttrs,
                    liveAttrDescs(receiverBa, withTicket = false),
                    "${tc.name}: wantAttrs",
                )
                assertEquals(
                    liveAttrDescs(receiverAb, withTicket = true),
                    liveAttrDescs(receiverBa, withTicket = true),
                    "${tc.name}: the two orders hold different attribute entries on live nodes",
                )
            }
            assertEquals(abXml, baXml, "${tc.name}: the two delivery orders render differently")
        }
    }

    // The removeStyle mirror of "does not style the merge target a range
    // start moved into": the merge removes the opening tag of the paragraph
    // the removeStyle range STARTS inside, so its children move into the
    // paragraph before it, and the removeStyle covers only the paragraph it
    // named, which the merge turns into a tombstone -- so the other
    // paragraph keeps its bold attribute.
    @Test
    fun `does not clear the merge target a range start moved into`() = runTest {
        val (ab, ba) = replayBothOrders(
            root = styleScanBoldBase,
            structural = { edit(1, 5) },
            style = { removeStyle(6, 8, listOf("bold")) },
        )
        assertEquals("""<r><p bold="x">cd</p><p bold="x">ef</p></r>""", ba.xml)
        assertEquals(ab, ba, "merge-then-remove-style clears the target")
    }

    // The removeStyle mirror of "styles both halves of a concurrently split
    // paragraph": a concurrent split of the paragraph a removeStyle covers,
    // so the removal carries onto both halves.
    @Test
    fun `clears both halves of a concurrently split paragraph`() = runTest {
        val (ab, ba) = replayBothOrders(
            root = styleScanBoldBase,
            structural = { edit(6, 6, 1) },
            style = { removeStyle(5, 8, listOf("bold")) },
        )
        assertEquals(
            """<r><p bold="x">ab</p><p>c</p><p>d</p><p bold="x">ef</p></r>""",
            ba.xml,
        )
        assertEquals(ab, ba, "split-then-remove-style diverges")
    }

    // The removeStyle half of the level-2-split-before-range pair: the
    // removeStyle covered only `<p>efgh</p>`; the split carries the right
    // half of `<p>abcd</p>` into a NEW parent, so neither half of `<p>abcd
    // </p>` may be cleared.
    @Test
    fun `clears only the paragraph the range covered across a level-2 split before the range`() =
        runTest {
            val (ab, ba) = replayBothOrders(
                root = nestedLevel2Base,
                structural = { edit(5, 5, 2) },
                style = { removeStyle(8, 14, listOf("italic")) },
            )
            assertEquals(
                """<r><p><p><p italic="a">ab</p></p><p><p italic="a">cd</p>""" +
                    """<p>efgh</p></p><p italic="a">ijkl</p></p></r>""",
                ba.xml,
            )
            assertEquals(ab, ba, "split-then-remove-style diverges")
        }

    // The removeStyle half of the split-and-merge-in-one-change pair.
    @Test
    fun `clears both paragraphs the range covered before a split and merge in one change`() =
        runTest {
            val (ab, ba) = replayBothOrders(
                root = nestedScanBoldBase,
                structural = {
                    edit(10, 10, 1)
                    edit(7, 9)
                },
                style = { removeStyle(0, 14, listOf("bold")) },
            )
            assertEquals(
                """<r><p><p><p>abcde</p><p>fgh</p></p><p bold="x">ijkl</p></p></r>""",
                ba.xml,
            )
            assertEquals(ab, ba, "split-and-merge-then-remove-style diverges")
        }

    /**
     * The removeStyle-flavored 3 of the 13 complex-suite cases (see class
     * KDoc). Mirrors yorkie's
     * `TestStyleReachedSetMatchesComplexSuite` removeStyle half.
     */
    private val complexSuiteRemoveStyleCases = listOf(
        ComplexCase(
            // The same range as "across-merged-anchor", as a removeStyle,
            // which must not leave an attribute container behind on the
            // interloper either.
            name = "remove-style-across-merged-anchor",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                removeStyle(0, 5, listOf("bold"))
            },
            want = "<r><p></p>cd</r>",
            wantAttrs = emptyList(),
        ),
        ComplexCase(
            name = "remove-style-after-moved-anchor",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                removeStyle(0, 6, listOf("bold"))
            },
            want = "<r><p></p>cd</r>",
            wantAttrs = emptyList(),
        ),
        ComplexCase(
            // The removal entry that arbitrates a later setAttr must land on
            // the surviving <p> in both orders, with one identity -- Rht
            // tombstones a key even when the node never held it, so the
            // interloper the merge pulls next to the range-end anchor still
            // ends up with a removal entry once the recovery reaches it.
            name = "remove-style-from-side-moved-anchor",
            base = twoParagraphs,
            structural = { edit(0, 5) },
            style = {
                edit(8, 8, ElementNode("p"))
                removeStyle(6, 9, listOf("bold"))
            },
            want = "<r><p></p>cd</r>",
            wantAttrs = listOf("""p bold="" removed=true"""),
        ),
    )

    @Test
    fun `matches the complex suite, removeStyle half`() = runTest {
        for (tc in complexSuiteRemoveStyleCases) {
            val docA = newDoc(structuralActor)
            val docB = newDoc(styleActor)
            val receiverAb = newDoc(receiverAbActor)
            val receiverBa = newDoc(receiverBaActor)
            seedInto(tc.base, tc.pre, docA, docB, receiverAb, receiverBa)

            docA.updateAsync { r, _ -> tc.structural(r.getAs<JsonTree>("t")) }.await()
            docB.updateAsync { r, _ -> tc.style(r.getAs<JsonTree>("t")) }.await()

            val changesA = docA.createChangePack().changes
            val changesB = docB.createChangePack().changes

            receiverAb.deliver(docA, changesA)
            receiverAb.deliver(docB, changesB)
            receiverBa.deliver(docB, changesB)
            receiverBa.deliver(docA, changesA)

            val abXml = receiverAb.xml()
            val baXml = receiverBa.xml()
            assertEquals(tc.want, baXml, tc.name)
            tc.wantAttrs?.let { wantAttrs ->
                assertEquals(
                    wantAttrs,
                    liveAttrDescs(receiverBa, withTicket = false),
                    "${tc.name}: wantAttrs",
                )
                assertEquals(
                    liveAttrDescs(receiverAb, withTicket = true),
                    liveAttrDescs(receiverBa, withTicket = true),
                    "${tc.name}: the two orders hold different attribute entries on live nodes",
                )
            }
            assertEquals(abXml, baXml, "${tc.name}: the two delivery orders render differently")
        }
    }

    // A backwards range reaches nothing through the index form, which
    // rejects it up front; the path form does the same instead of resolving
    // it into a range the boundary elements would have to judge.
    // Android keeps `require` (IllegalArgumentException), not
    // YorkieException as JS throws. Mirrors JS `Tree style by path` /
    // `rejects a backwards range` (b69169c0, #1404).
    @Test
    fun `rejects a backwards by-path range`() = runTest {
        val doc = newDoc(structuralActor)
        doc.updateAsync { r, _ -> r.setNewTree("t", twoParagraphs) }.await()

        val styleResult = doc.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(1, 0), listOf(0, 1), bold)
        }.await()
        assertTrue(styleResult.exceptionOrNull() is IllegalArgumentException)

        val removeStyleResult = doc.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").removeStyleByPath(listOf(1, 0), listOf(0, 1), listOf("bold"))
        }.await()
        assertTrue(removeStyleResult.exceptionOrNull() is IllegalArgumentException)

        doc.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(0, 0), listOf(1, 0), bold)
        }.await()
        assertEquals("""<r><p bold="x">ab</p><p bold="x">cd</p></r>""", doc.xml())
    }
}
