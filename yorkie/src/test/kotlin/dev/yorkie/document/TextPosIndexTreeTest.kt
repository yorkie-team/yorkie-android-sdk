package dev.yorkie.document

import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val ACTOR = "000000000000000000000001"
private const val PEER = "000000000000000000000002"

private const val LOCAL_SEEDS = 15
private const val LOCAL_STEPS = 80

private const val REMOTE_SEEDS = 10
private const val REMOTE_STEPS = 50

private const val GC_SEEDS = 10
private const val GC_STEPS = 40

private const val TYPING_EDIT_COUNT = 40_000
private const val REMOTE_EDIT_COUNT = 20_000

// Measured as a ratio between two windows of the same run, not as an absolute
// wall-clock bound. The earlier absolute bounds (3,500ms typing / 1,000ms
// remote, ~3x the baselines measured on one machine) tripped on a loaded CI
// runner with nothing regressed, while bounds wide enough for any runner (the
// 15s / 5s before them) would never catch what these tests exist for: a
// reintroduced O(n) chain walk per edit (what `cab55005` replaced with the
// O(1) `treeByIndex.indexOf` read). That regression's cost grows with the
// text length, so it shows up as the LAST window of a long run costing
// several times the FIRST -- with the windows below, roughly 3x for typing
// and 3.5x for remote apply (extra cost ~ i per edit, summed over each
// window, on top of the measured linear baseline), against ~1x for a linear
// path. A slower machine slows both windows alike, and the JIT warm-up that
// is not covered by the unmeasured lead-in only inflates the FIRST window,
// which lowers the ratio -- the safe direction. The fixed slack absorbs the
// millisecond noise of a short window; it is far below any regression.
// These remain a coarse smoke check: `treeByIndex sums to the same length`
// and this class's other chain-walk-vs-index-tree equivalence tests are the
// real guard against the chain walk coming back.
private const val TYPING_WARMUP = 2_000
private const val TYPING_WINDOW = 10_000
private const val REMOTE_WARMUP = 2_000
private const val REMOTE_WINDOW = 5_000
private const val LINEAR_RATIO_BOUND = 2.0
private const val RATIO_SLACK_MS = 100L

/**
 * Pins `RgaTreeSplit.posToIndex` (and the `treeByIndex` sum it reads) against
 * an independent chain-walk oracle -- the invariant JS SDK `cab55005`
 * (#1442) relies on when it replaced `Text.normalizePos`'s `prev`-chain walk
 * with a `treeByIndex.indexOf` read: "every chain node is in `treeByIndex`,
 * in chain order, weighted by its live length".
 *
 * Android never had `normalizePos` or the chain walk it replaced --
 * `RgaTreeSplit.posToIndex`/`indexToPos`/`findIndexesFromRange` already read
 * `treeByIndex`, and `refinePos` walks the physical `next` chain only on the
 * restore-anchor rung (e), unchanged by `cab55005`. This is therefore a
 * no-production-change, test-only commit: a red run is not constructible --
 * every case below is green at the baseline it pins. Measured on a
 * scratch worktree: typing 40,000 single-character edits took 1,310ms;
 * applying 20,000 remote single-character edits took 103ms; a chain-walk vs
 * `treeByIndex.indexOf` comparison across a seeded 400-step
 * edit/undo/redo/GC sequence held for all 20,436 checks, 0 mismatches.
 *
 * Maps to the JS `text_normalize_pos_test.ts` (`cab55005`) cases: 1 and 2
 * below port cases 1 ("across edit, style, undo, redo and GC") and 2
 * ("on replicas applying remote edits") directly; `positions captured
 * before GC` below ports case 4 ("positions whose pieces GC purged"), scoped
 * to the chain-walk/`treeByIndex` invariant rather than JS's
 * `normalizePos`-specific stale-id resolution ladder (already pinned
 * separately for Android in `TextRestoreConvergenceTest`'s `refinePos`
 * cases). Content generation is plain ASCII/BMP text, not JS's deliberate
 * lone-surrogate slices (`'ab😀가'.slice(...)`) -- surrogate-pair handling is
 * out of scope here and lands in a later change (#1447). The two
 * hop-counting cases (JS cases 3 and 5) cannot be ported as hop counts on
 * the JVM (no `prev`-hop instrumentation point to patch), so they are
 * replaced by two bounded wall-clock pins measuring the exact scenario
 * `cab55005`'s own commit message benchmarks: typing N single-character
 * edits, and applying N remote ones in one batch.
 */
class TextPosIndexTreeTest {

    private fun Document.crdtText(key: String): CrdtText = getRootObject()[key] as CrdtText

    /**
     * Independent oracle for the index-tree sum `cab55005` ported
     * `Text.normalizePos` onto: the absolute offset of a live node is the
     * summed live length of every node before it in physical chain order
     * (accumulated forward here, equivalent to summing the `prev` chain
     * backward from each node). Returns the number of nodes checked.
     */
    private fun assertTreeByIndexMatchesChainWalk(
        split: RgaTreeSplit<TextValue>,
        context: String,
    ): Int {
        var prefix = 0
        var checks = 0
        for (node in split) {
            assertEquals(
                prefix,
                split.treeByIndex.indexOf(node),
                "$context: node ${node.id} expected tree-by-index offset $prefix",
            )
            checks++
            prefix += node.length
        }
        return checks
    }

    // JS text_normalize_pos_test.ts case 1 ("should match the chain walk
    // across edit, style, undo, redo and GC").
    @Test
    fun `tree-by-index offsets match the chain walk across edit style undo redo GC steps`() =
        runTest {
            var checks = 0
            var undone = 0
            var redone = 0
            var purged = 0

            for (seed in 1..LOCAL_SEEDS) {
                val random = Random(seed)
                val document = Document("text-pos-index-tree-local-$seed")
                document.setActor(ACTOR)
                document.updateAsync { root, _ -> root.setNewText("t") }.await()
                document.clearHistory()

                repeat(LOCAL_STEPS) {
                    when (random.nextInt(10)) {
                        in 0..3 -> document.updateAsync { root, _ ->
                            val text = root.getAs<JsonText>("t")
                            val at = random.nextInt(text.length + 1)
                            text.edit(at, at, "abc가".substring(0, 1 + random.nextInt(4)))
                        }.await()

                        in 4..5 -> document.updateAsync { root, _ ->
                            val text = root.getAs<JsonText>("t")
                            if (text.length == 0) return@updateAsync
                            val from = random.nextInt(text.length)
                            text.edit(from, minOf(text.length, from + 1 + random.nextInt(3)), "")
                        }.await()

                        6 -> document.updateAsync { root, _ ->
                            val text = root.getAs<JsonText>("t")
                            if (text.length == 0) return@updateAsync
                            val from = random.nextInt(text.length)
                            text.style(
                                from,
                                minOf(text.length, from + 1 + random.nextInt(3)),
                                mapOf("b" to "1"),
                            )
                        }.await()

                        7 -> if (document.history.canUndo()) {
                            document.history.undoAsync().await()
                            undone++
                        }

                        8 -> if (document.history.canRedo()) {
                            document.history.redoAsync().await()
                            redone++
                        }

                        else -> purged += document.garbageCollect(maxVectorOf(listOf(ACTOR)))
                    }

                    checks += assertTreeByIndexMatchesChainWalk(
                        document.crdtText("t").rgaTreeSplit,
                        "seed $seed",
                    )
                }
            }

            // Guards the harness itself (JS does the same): a sequence that
            // never undoes, redoes or purges would pass without exercising
            // the paths that matter.
            assertTrue(undone > 20, "guard: expected undo to fire repeatedly, got $undone")
            assertTrue(redone > 5, "guard: expected redo to fire repeatedly, got $redone")
            assertTrue(purged > 20, "guard: expected GC to purge repeatedly, got $purged")
            assertTrue(checks > 3000, "guard: expected many per-node checks, got $checks")
        }

    // JS text_normalize_pos_test.ts case 2 ("should match the chain walk on
    // replicas applying remote edits").
    @Test
    fun `tree-by-index offsets match the chain walk on two replicas applying remote edits`() =
        runTest {
            var checks = 0
            var purged = 0

            for (seed in 1..REMOTE_SEEDS) {
                val random = Random(seed)
                val d1 = Document("text-pos-index-tree-remote-$seed")
                val d2 = Document("text-pos-index-tree-remote-$seed")
                d1.setActor(ACTOR)
                d2.setActor(PEER)
                d1.updateAsync { root, _ -> root.setNewText("t") }.await()
                crossSync(d1, d2)

                repeat(REMOTE_STEPS) {
                    val doc = if (random.nextInt(2) == 0) d1 else d2
                    when (random.nextInt(10)) {
                        in 0..4 -> doc.updateAsync { root, _ ->
                            val text = root.getAs<JsonText>("t")
                            val at = random.nextInt(text.length + 1)
                            text.edit(at, at, "abc가".substring(0, 1 + random.nextInt(4)))
                        }.await()

                        in 5..7 -> doc.updateAsync { root, _ ->
                            val text = root.getAs<JsonText>("t")
                            if (text.length == 0) return@updateAsync
                            val from = random.nextInt(text.length)
                            text.edit(from, minOf(text.length, from + 1 + random.nextInt(3)), "")
                        }.await()

                        8 -> crossSync(d1, d2)

                        else -> {
                            crossSync(d1, d2)
                            val vector = maxVectorOf(listOf(ACTOR, PEER))
                            purged += d1.garbageCollect(vector)
                            purged += d2.garbageCollect(vector)
                        }
                    }

                    checks += assertTreeByIndexMatchesChainWalk(
                        d1.crdtText("t").rgaTreeSplit,
                        "d1 seed $seed",
                    )
                    checks += assertTreeByIndexMatchesChainWalk(
                        d2.crdtText("t").rgaTreeSplit,
                        "d2 seed $seed",
                    )
                }

                crossSync(d1, d2)
                assertEquals(d1.toJson(), d2.toJson())
            }

            assertTrue(
                purged > 10,
                "guard: expected GC to purge repeatedly across replicas, got $purged",
            )
            assertTrue(
                checks > 1000,
                "guard: expected many per-node checks across both replicas, got $checks",
            )
        }

    // JS text_normalize_pos_test.ts case 4 ("should resolve positions whose
    // pieces GC purged as the chain walk did"), scoped to the
    // chain-walk/`treeByIndex` invariant: every surviving node's offset must
    // still agree right after a GC pass, and some of the ids captured before
    // the pass must actually be gone afterward.
    @Test
    fun `tree-by-index offsets still match the chain walk immediately after each GC pass`() =
        runTest {
            var checks = 0
            var purgedNodeIds = 0
            var purgedTotal = 0

            for (seed in 1..GC_SEEDS) {
                val random = Random(seed)
                val document = Document("text-pos-index-tree-gc-$seed")
                document.setActor(ACTOR)
                document.updateAsync { root, _ ->
                    val text = root.setNewText("t")
                    text.edit(0, 0, "abcdefghij")
                    text.edit(5, 5, "0123456789")
                }.await()

                repeat(GC_STEPS) {
                    val before = document.crdtText("t").rgaTreeSplit.map { it.id }.toSet()

                    document.updateAsync { root, _ ->
                        val text = root.getAs<JsonText>("t")
                        if (random.nextInt(3) == 0 || text.length < 4) {
                            val at = random.nextInt(text.length + 1)
                            text.edit(at, at, "xyz".substring(0, 1 + random.nextInt(3)))
                        } else {
                            val from = random.nextInt(text.length)
                            text.edit(from, minOf(text.length, from + 1 + random.nextInt(4)), "")
                        }
                    }.await()
                    purgedTotal += document.garbageCollect(maxVectorOf(listOf(ACTOR)))

                    val split = document.crdtText("t").rgaTreeSplit
                    checks += assertTreeByIndexMatchesChainWalk(split, "seed $seed")
                    purgedNodeIds += before.count { split.treeByID[it] == null }
                }
            }

            assertTrue(
                checks > 2000,
                "guard: expected many per-node checks right after GC, got $checks",
            )
            assertTrue(
                purgedNodeIds > 20,
                "guard: expected some pre-GC node ids to be purged, got $purgedNodeIds",
            )
            assertTrue(
                purgedTotal > 20,
                "guard: expected GC to purge tombstones repeatedly, got $purgedTotal",
            )
        }

    // Replaces JS case 5 ("should keep typing linear in the length of the
    // text") with the exact scenario `cab55005`'s own commit message
    // benchmarks: typing is measured directly in wall-clock time instead of
    // counting `prev` hops (no hop-instrumentation point exists on the JVM
    // port, since Android never had the chain walk to instrument). Linear
    // means a window of edits late in the run costs about what the same-sized
    // window early in the run did.
    @Test
    fun `types 40,000 single-character edits in time linear in the text length`() = runTest {
        val document = Document("text-pos-index-tree-typing-cost")
        document.updateAsync { root, _ -> root.setNewText("t") }.await()
        val type: suspend (IntRange) -> Long = { range ->
            measureTimeMillis {
                for (i in range) {
                    document.updateAsync { root, _ ->
                        root.getAs<JsonText>("t").edit(i, i, "a")
                    }.await()
                }
            }
        }

        type(0 until TYPING_WARMUP)
        val first = type(TYPING_WARMUP until TYPING_WARMUP + TYPING_WINDOW)
        type(TYPING_WARMUP + TYPING_WINDOW until TYPING_EDIT_COUNT - TYPING_WINDOW)
        val last = type(TYPING_EDIT_COUNT - TYPING_WINDOW until TYPING_EDIT_COUNT)

        assertEquals(
            TYPING_EDIT_COUNT,
            document.getRoot().getAs<JsonText>("t").toString().length,
        )
        assertTrue(
            last <= first * LINEAR_RATIO_BOUND + RATIO_SLACK_MS,
            "typing the last $TYPING_WINDOW of $TYPING_EDIT_COUNT edits took ${last}ms" +
                " against ${first}ms for $TYPING_WINDOW edits early in the run:" +
                " the per-edit cost grows with the text length",
        )
    }

    // Replaces JS case 3 ("should cost the same to normalize at any document
    // length") with `cab55005`'s own commit-message benchmark shape: applying
    // a remote history, measured in wall-clock time around only the apply
    // calls (construction of the sender's local edits is excluded -- it is
    // unrelated setup cost, not the path `cab55005` sped up). The history is
    // applied in slices so a late slice can be compared with an early one.
    @Test
    fun `applies 20,000 remote edits in time linear in the text length`() = runTest {
        val sender = Document("text-pos-index-tree-remote-cost")
        sender.updateAsync { root, _ -> root.setNewText("t") }.await()
        repeat(REMOTE_EDIT_COUNT) { i ->
            sender.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(i, i, "a") }.await()
        }
        val changes = sender.createChangePack().changes

        val receiver = Document("text-pos-index-tree-remote-cost")
        val apply: suspend (IntRange) -> Long = { range ->
            measureTimeMillis {
                receiver.applyChangePack(
                    ChangePack(
                        sender.getKey(),
                        CheckPoint.InitialCheckPoint,
                        changes.slice(range),
                        null,
                        false,
                        VersionVector(),
                    ),
                )
            }
        }

        // `changes[0]` is the setNewText change, so the slices run one past the edit count.
        val total = changes.size
        apply(0 until REMOTE_WARMUP)
        val first = apply(REMOTE_WARMUP until REMOTE_WARMUP + REMOTE_WINDOW)
        apply(REMOTE_WARMUP + REMOTE_WINDOW until total - REMOTE_WINDOW)
        val last = apply(total - REMOTE_WINDOW until total)

        assertEquals(
            REMOTE_EDIT_COUNT,
            receiver.getRoot().getAs<JsonText>("t").toString().length,
        )
        assertTrue(
            last <= first * LINEAR_RATIO_BOUND + RATIO_SLACK_MS,
            "applying the last $REMOTE_WINDOW of $REMOTE_EDIT_COUNT remote edits took ${last}ms" +
                " against ${first}ms for $REMOTE_WINDOW edits early in the history:" +
                " the per-edit cost grows with the text length",
        )
    }
}
