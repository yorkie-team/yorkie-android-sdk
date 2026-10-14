package dev.yorkie.document

import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.json.JsonTree.TextNode
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DocSize
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the two EXHAUSTIVE-scan `describe` blocks of yorkie-js-sdk
 * `tree_style_reached_set_test.ts` (`'Tree style reached set scans'` +
 * `'Tree style reached set nested scans'`, b69169c0, #1404; yorkie#2038/
 * #2070) as JVM in-process cross-sync tests: every (structural edit, style
 * range) pair over a fixed base tree, replayed in both delivery orders,
 * closed on the rendered XML (and the size ledger where JS closes it too).
 * `TreeStyleReachedSetTest` ports the NAMED/complex-suite
 * cases from the same JS file; that class's own KDoc defers the six scans
 * to this class explicitly.
 *
 * Test harness: the SAME seed-then-replay-both-orders mechanism
 * `TreeStyleReachedSetTest` uses (there: `newDoc`/`deliver`/`seedInto`/
 * `replayBothOrders`), reproduced here with one change forced by volume --
 * JS's own `scanDivergences` builds the seed `ChangePack` ONCE per scan
 * (`styleScanBase()` etc. are called once per `it()`, then replayed via
 * `feed` into every one of the up to 11592 inner pairs) rather than once
 * per pair; `seedInto` there rebuilds the tree via `setNewTree`/`updateAsync`
 * on every call, which this file would otherwise do 1001-11592 times per
 * scan. `scanDivergences` below factors that same seeding out of the inner
 * loop, matching JS's own precedent, not a third harness design. The other
 * difference from the named-case harness: every `Document` this file
 * creates is `close()`d right after its one use -- `Document` spins up a
 * dedicated single-thread `Closeable` dispatcher per instance (see its
 * class KDoc), and a scan creates on the order of 10,000-50,000 of them, so
 * leaving them open would exhaust OS threads long before a scan's pinned
 * count is reached.
 *
 * Timeouts: each test's bound is sized off TWO measurements, not one -- the
 * scan run in isolation (seconds) and the SAME scan run inside the full
 * `yorkie:testDebugUnitTest` suite, which is 5-10x slower per scan (JaCoCo
 * instrumentation plus CPU contention from Gradle's other parallel
 * test-worker forks, not this file's own cost -- the pair/styled counts are
 * identical either way). Every budget below is sized off the full-suite
 * figure, not the isolated one. The bound is passed to `runTest(timeout =
 * ...)`, not a JUnit `@Test(timeout = ...)`: `runTest` (kotlinx-coroutines-
 * test 1.8.1) enforces its OWN 60s default internal watchdog regardless of
 * any outer JUnit annotation, so a `@Test(timeout = 90_000)`/`150_000` here
 * would never be the one that fires -- `runTest`'s 60s default always beats
 * it first. `runTest(timeout = ...)` replaces that default outright.
 */
class TreeStyleReachedSetScanTest {

    private val docKey = "test-doc"
    private val bold = mapOf("bold" to "x")

    private fun newDoc(actor: String): Document {
        val doc = Document(docKey)
        doc.setActor(actor)
        return doc
    }

    private suspend fun Document.deliver(changes: List<Change>) {
        applyChangePack(
            ChangePack(
                docKey,
                CheckPoint.InitialCheckPoint,
                changes,
                null,
                false,
                VersionVector(),
            ),
        )
    }

    private suspend fun Document.xml(): String = getRoot().getAs<JsonTree>("t").toXml()

    private fun p(value: String, attributes: Map<String, String>? = null): ElementNode =
        ElementNode("p", listOf(TextNode(value)), attributes.orEmpty())

    /**
     * `<r><p>ab</p><p>cd</p><p>ef</p></r>`, 12 wide inside the root --
     * mirrors JS `styleScanBase` (and `TreeStyleReachedSetTest`'s own copy).
     */
    private val styleScanBase = ElementNode("r", listOf(p("ab"), p("cd"), p("ef")))

    /**
     * [styleScanBase] with every paragraph already bold, so a removeStyle
     * has something to take off -- mirrors JS `styleScanBoldBase`.
     */
    private val styleScanBoldBase = ElementNode(
        "r",
        listOf(p("ab", bold), p("cd", bold), p("ef", bold)),
    )

    /**
     * `<r><p><p><p>abcd</p><p>efgh</p></p><p>ijkl</p></p></r>`, 22 wide --
     * mirrors JS `nestedScanBase()`. A level-2 split moves a half into a
     * NEW parent here, unlike the flat scans' one-level split.
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
     * [nestedScanBase] with every "p" -- both wrapper levels too -- already
     * bold, mirrors JS `nestedScanBase({b: 'x'})`.
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

    private fun splitAt(at: Int, level: Int): JsonTree.() -> Unit = { edit(at, at, level) }
    private fun mergeRange(from: Int, to: Int): JsonTree.() -> Unit = { edit(from, to) }

    /** 11 level-1 splits, `at` = 1..11 -- mirrors JS `splits`. */
    private val flatSplits: List<JsonTree.() -> Unit> = (1..11).map { splitAt(it, 1) }

    /** Every [from, to) merge over width 12 (78 of them) -- mirrors JS `merges`. */
    private val flatMerges: List<JsonTree.() -> Unit> = buildList {
        for (from in 0..12) {
            for (to in (from + 1)..12) {
                add(mergeRange(from, to))
            }
        }
    }

    /** Every level-1 AND level-2 split, `at` = 1..21 (42 of them) -- mirrors JS `nestedSplits`. */
    private val nestedSplits: List<JsonTree.() -> Unit> = buildList {
        for (level in 1..2) {
            for (at in 1..21) {
                add(splitAt(at, level))
            }
        }
    }

    private val styleRange: JsonTree.(Int, Int) -> Unit = { from, to -> style(from, to, bold) }
    private val removeStyleRange: JsonTree.(Int, Int) -> Unit =
        { from, to -> removeStyle(from, to, listOf("bold")) }

    private fun countOf(needle: String): (String) -> Int = { xml -> xml.split(needle).size - 1 }

    /** Mirrors JS `ScanResult`. */
    private data class ScanResult(
        val pairs: Int,
        val rendered: Int,
        val tombstoneOnly: Int,
        val errored: Int,
        val styledNodes: Int,
        val styledPairs: Int,
    )

    /** Mirrors JS `ReplayResult` (`xml`/`size`/`err`), split into its parts. */
    private data class ReplayOutcome(
        val xml: String?,
        val docSize: DocSize?,
        val garbageLength: Int?,
        val err: String?,
    )

    /**
     * Replays [seedChanges] then every batch in [batches], in order, into a
     * fresh replica, and reports what it rendered -- mirrors JS
     * `replayOrder`. Closes the replica before returning either way.
     */
    private suspend fun replaySeeded(
        seedChanges: List<Change>,
        vararg batches: List<Change>,
    ): ReplayOutcome {
        val doc = newDoc("00000000000000000000000a")
        return try {
            doc.deliver(seedChanges)
            batches.forEach { doc.deliver(it) }
            ReplayOutcome(doc.xml(), doc.getDocSize(), doc.garbageLength, null)
        } catch (e: Exception) {
            ReplayOutcome(null, null, null, e.toString())
        } finally {
            doc.close()
        }
    }

    /**
     * Replays every (structural edit in [structuralEdits], style range in
     * `[0, width] x [from, width]`) pair in both delivery orders over
     * [root], and reports what diverged -- mirrors JS `scanDivergences`.
     * The seed is built and serialized ONCE (see class KDoc); a genuine
     * exception from [structural]/[styleFn] itself (not from replay) is
     * NOT caught -- JS does not catch it either, and a real divergence this
     * unit was built to catch should fail loudly, not get folded into
     * `errored`.
     */
    private suspend fun scanDivergences(
        root: ElementNode,
        structuralEdits: List<JsonTree.() -> Unit>,
        styleFn: JsonTree.(Int, Int) -> Unit,
        countStyled: (String) -> Int,
        width: Int = 12,
    ): ScanResult {
        val seed = newDoc("000000000000000000000009")
        try {
            seed.updateAsync { r, _ -> r.setNewTree("t", root) }.await()
            val seedChanges = seed.createChangePack().changes

            var pairs = 0
            var rendered = 0
            var tombstoneOnly = 0
            var errored = 0
            var styledNodes = 0
            var styledPairs = 0

            for (structural in structuralEdits) {
                for (from in 0..width) {
                    for (to in from..width) {
                        pairs++
                        val docA = newDoc("000000000000000000000001")
                        val docB = newDoc("000000000000000000000002")
                        val changesA: List<Change>
                        val changesB: List<Change>
                        try {
                            docA.deliver(seedChanges)
                            docB.deliver(seedChanges)
                            docA.updateAsync { r, _ -> r.getAs<JsonTree>("t").structural() }
                                .await()
                            docB.updateAsync { r, _ -> r.getAs<JsonTree>("t").styleFn(from, to) }
                                .await()
                            changesA = docA.createChangePack().changes
                            changesB = docB.createChangePack().changes
                        } finally {
                            docA.close()
                            docB.close()
                        }

                        val ab = replaySeeded(seedChanges, changesA, changesB)
                        val ba = replaySeeded(seedChanges, changesB, changesA)

                        if (ab.err != null || ba.err != null) {
                            assertEquals(ab.err, ba.err, "pair $pairs: errors diverge")
                            errored++
                        } else if (ab.xml != ba.xml) {
                            rendered++
                        } else if (ab.docSize != ba.docSize ||
                            ab.garbageLength != ba.garbageLength
                        ) {
                            tombstoneOnly++
                        }
                        if (ab.err == null) {
                            val styled = countStyled(ab.xml!!)
                            styledNodes += styled
                            if (styled > 0) styledPairs++
                        }
                    }
                }
            }
            return ScanResult(pairs, rendered, tombstoneOnly, errored, styledNodes, styledPairs)
        } finally {
            seed.close()
        }
    }

    // Every level-1 split against every style range, over the flat 12-wide
    // base: closed on BOTH the rendered document and the size ledger.
    // Measured: ~0.4-0.5s run alone, ~3.5s run inside the full `yorkie:
    // testDebugUnitTest` suite (JaCoCo + sibling test-worker CPU contention
    // -- see class KDoc); 30s is a >8x margin over the full-suite figure.
    @Test
    fun `converges split x style`() = runTest(timeout = 30.seconds) {
        val scan = scanDivergences(styleScanBase, flatSplits, styleRange, countOf("bold=\"x\""))
        assertEquals(ScanResult(1001, 0, 0, 0, 1862, 759), scan)
    }

    @Test
    fun `converges split x remove-style`() = runTest(timeout = 30.seconds) {
        val scan =
            scanDivergences(styleScanBoldBase, flatSplits, removeStyleRange, countOf("<p>"))
        assertEquals(ScanResult(1001, 0, 0, 0, 1862, 759), scan)
    }

    // Every merge against every style range, over the flat 12-wide base:
    // closed on the rendered document; attributes on TOMBSTONES are bounded
    // as an upper bound, not pinned exactly (JS `isAtMost`) -- ab/ba still have
    // to agree, just not necessarily never disagree, since a style landing
    // only on a tombstone shows up in the size ledger, not the XML.
    // Measured: ~2.5-3.2s run alone, up to ~26s run inside the full suite
    // (contention, see class KDoc); 90s is a >3.4x margin over that figure.
    @Test
    fun `converges merge x style on the rendered document`() = runTest(timeout = 90.seconds) {
        val scan = scanDivergences(styleScanBase, flatMerges, styleRange, countOf("bold=\"x\""))
        assertEquals(ScanResult(7098, 0, scan.tombstoneOnly, 0, 6302, 4254), scan)
        assertTrue(
            scan.tombstoneOnly <= 1292,
            "tombstoneOnly regressed on tombstone attributes: ${scan.tombstoneOnly}",
        )
    }

    // Measured: up to ~24s run inside the full suite; 90s margin as above.
    @Test
    fun `converges merge x remove-style on the rendered document`() = runTest(
        timeout = 90.seconds,
    ) {
        val scan =
            scanDivergences(styleScanBoldBase, flatMerges, removeStyleRange, countOf("<p>"))
        assertEquals(ScanResult(7098, 0, scan.tombstoneOnly, 0, 6302, 4254), scan)
        assertTrue(
            scan.tombstoneOnly <= 1292,
            "tombstoneOnly regressed on tombstone attributes: ${scan.tombstoneOnly}",
        )
    }

    // Every level-1 AND level-2 split against every style range, over the
    // nested 22-wide base: 241 pairs still diverge on the rendered document
    // (the open yorkie#2070 §9.5 shapes) -- pinned exactly, not a
    // cap, so any movement in either direction has to be read against
    // yorkie#2070's own counts before this number changes.
    // Measured: ~4.2-4.4s run alone, up to ~39.7s run inside the full suite
    // (JaCoCo + sibling test-worker CPU contention, see class KDoc) -- the
    // heaviest scan, closest to its budget; 150s is a >3.7x margin over the
    // full-suite figure, generous for a slower/busier CI than this run.
    @Test
    fun `bounds nested split x style`() = runTest(timeout = 150.seconds) {
        val scan = scanDivergences(
            nestedScanBase,
            nestedSplits,
            styleRange,
            countOf("bold=\"x\""),
            width = 22,
        )
        assertEquals(ScanResult(11592, 241, 0, 0, 33208, 9318), scan)
    }

    // Measured: up to ~39.5s run inside the full suite; 150s margin as above.
    @Test
    fun `bounds nested split x remove-style`() = runTest(timeout = 150.seconds) {
        val scan = scanDivergences(
            nestedScanBoldBase,
            nestedSplits,
            removeStyleRange,
            countOf("<p>"),
            width = 22,
        )
        assertEquals(ScanResult(11592, 241, 0, 0, 33208, 9318), scan)
    }
}
