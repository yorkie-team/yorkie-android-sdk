package dev.yorkie.document

import dev.yorkie.api.snapshotToBytes
import dev.yorkie.api.toSnapshot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.json.JsonText
import dev.yorkie.helper.AdvClient
import dev.yorkie.helper.AdvServer
import dev.yorkie.helper.mulberry32
import dev.yorkie.helper.newAdvClients
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Ports `text_undo_fuzz_test.ts` (JS SDK #1514, yorkie#2310, itself a port of
 * Go's `text_undo_fuzz_test.go`) as a JVM unit test: random undo/redo against
 * concurrent deletions over the [AdvServer] push/pull model, every change
 * through protobuf.
 *
 * Seed count: matches the JS/Go suites' own default, 10 seeds x 40
 * steps per required setting. Set the `TEXT_UNDO_FUZZ_SEEDS` system property
 * to run more locally, e.g. `./gradlew yorkie:testDebugUnitTest -DTEXT_UNDO_FUZZ_SEEDS=100
 * --tests "*TextUndoFuzzTest*"` -- `yorkie/build.gradle.kts`'s `testOptions.unitTests.all`
 * forwards it (and `TEXT_UNDO_FUZZ_FULL`) from the Gradle daemon's own JVM, where the `-D`
 * lands, into the forked test JVM this class's `System.getProperty` calls actually run in;
 * a bare `-D` with no such forwarding would reach only the daemon. The optional
 * gc=true/insert-100% setting is behind the opt-in
 * `TEXT_UNDO_FUZZ_FULL` system property (default off, NOT `@Ignore`d): it is
 * report-only upstream (JS/Go: ~5 of 1000 seeds fail there too, a known
 * upstream mis-order of a recreated piece after GC with concurrent inserts —
 * see `RgaTreeSplit.findRestoreAnchor` rung (e)'s fallback-anchor caveat). Android and JS
 * do not fail the same seeds in this setting: traced to the pre-existing,
 * untouched-by-this-port `undoFromOffset` integer fallback anchor (`EditOperation.kt`) vs
 * JS's reconciled-position one, not attributable to this sync.
 */
class TextUndoFuzzTest {

    private val seeds = (System.getProperty("TEXT_UNDO_FUZZ_SEEDS") ?: "10").toInt()
    private val full = System.getProperty("TEXT_UNDO_FUZZ_FULL") != null
    private val steps = 40

    private fun textOf(doc: Document): CrdtText = doc.getRootObject()["t"] as CrdtText

    /** A compact string describing every node's removal ledger, for snapshot-equality checks. */
    private fun ledger(text: CrdtText): String {
        return text.rgaTreeSplit.joinToString("|") { node ->
            "${node.id.createdAt.toTestString()}:${node.id.offset}" +
                " r=${node.removedAt?.toTestString() ?: "-"}" +
                " e=${node.getRemovalEvents().sorted().joinToString(",") { it.toTestString() }}"
        }
    }

    /** Empty when [doc]'s Text ledger survives a snapshot round trip; the mismatch otherwise. */
    private fun snapshotMismatch(doc: Document): String {
        val bytes = snapshotToBytes(doc.getRootObject(), emptyMap())
        val decodedText = try {
            bytes.toSnapshot().first["t"] as CrdtText
        } catch (e: RuntimeException) {
            return "decode: ${e.message}"
        }
        val want = ledger(textOf(doc))
        val have = ledger(decodedText)
        return if (want == have) "" else "ledger\n want=$want\n have=$have"
    }

    /**
     * [failure] is empty on convergence, else the discriminating reason. [undoRedoRan] is how
     * many of this seed's steps actually invoked `history.undoAsync`/`redoAsync` -- a sanity
     * count on the fuzz loop itself: the step distribution is probabilistic, so a seed that
     * happens to never roll undo/redo would silently cover nothing of what this suite exists
     * to fuzz.
     */
    private data class SeedResult(val failure: String, val undoRedoRan: Int)

    private suspend fun runSeed(
        seed: Int,
        gc: Boolean,
        insertPct: Int,
        clients: Int,
    ): SeedResult {
        val rnd = mulberry32(seed)
        fun pick(n: Int) = (rnd() * n).toInt().coerceIn(0, maxOf(0, n - 1))
        val server = AdvServer()
        val cs = newAdvClients(clients)
        var undoRedoRan = 0
        cs[0].document.updateAsync { root, _ ->
            root.setNewText(
                "t",
            ).edit(0, 0, "abcdefghij")
        }.await()
        for (c in cs) server.sync(c, gc)
        server.sync(cs[0], gc)
        cs[0].document.clearHistory()

        for (step in 0 until steps) {
            val c: AdvClient = cs[pick(cs.size)]
            var k = pick(10)
            if (k < 2 && pick(100) >= insertPct) k = 2
            try {
                when {
                    k < 2 -> {
                        val pos = pick(textOf(c.document).length + 1)
                        val ch = ('A' + pick(26)).toString()
                        c.document.updateAsync { root, _ ->
                            root.getAs<JsonText>(
                                "t",
                            ).edit(pos, pos, ch)
                        }
                            .await().getOrThrow()
                    }

                    k < 5 -> {
                        val l = textOf(c.document).length
                        if (l == 0) continue
                        val from = pick(l)
                        val to = from + 1 + pick(minOf(3, l - from))
                        c.document.updateAsync { root, _ ->
                            root.getAs<JsonText>(
                                "t",
                            ).edit(from, to, "")
                        }
                            .await().getOrThrow()
                    }

                    k < 7 -> {
                        c.document.history.undoAsync().await().getOrThrow()
                        undoRedoRan++
                    }

                    k < 8 -> {
                        c.document.history.redoAsync().await().getOrThrow()
                        undoRedoRan++
                    }

                    else -> {
                        server.sync(c, gc)
                        server.wire()
                    }
                }
            } catch (e: RuntimeException) {
                return SeedResult("step $step: ${e.message}", undoRedoRan)
            }
            for (x in cs) {
                val why = snapshotMismatch(x.document)
                if (why.isNotEmpty()) return SeedResult("step $step: $why", undoRedoRan)
            }
        }

        try {
            // 6, not the original 3 -- each round's `minVersionVector`
            // is computed from whatever every client's OWN row (`AdvServer.sync`) last
            // happened to hold, so within one round the first client synced still reads the
            // OTHER clients' PRIOR-round rows; the minVV (and so how far `pruneLedgers`
            // reaches) only ratchets up to its true fixed point after enough idle rounds.
            // 3 rounds left it mid-ratchet -- clients had already converged on the same
            // visible text and `removedAt`, but not yet on exactly how much already-settled
            // ledger history each had pruned away, which the cross-replica ledger check
            // below now also compares.
            repeat(6) {
                for (c in cs) server.sync(c, gc)
            }
        } catch (e: RuntimeException) {
            return SeedResult("final sync: ${e.message}", undoRedoRan)
        }
        val want = cs[0].document.getRoot().getAs<JsonText>("t").toString()
        for (c in cs.drop(1)) {
            val got = c.document.getRoot().getAs<JsonText>("t").toString()
            if (got != want) {
                return SeedResult("diverged \"$want\" vs \"$got\"", undoRedoRan)
            }
        }

        // A5: cross-replica ledger equality -- every replica converged on the same text
        // above, but the removal ledger backing it (what GC may still hold, what an in-
        // flight undo could still cancel) must agree too, not just what is visible.
        val wantLedger = ledger(textOf(cs[0].document))
        for ((idx, c) in cs.drop(1).withIndex()) {
            val gotLedger = ledger(textOf(c.document))
            if (gotLedger != wantLedger) {
                return SeedResult(
                    "ledger diverged on client ${idx + 1}\n want=$wantLedger\n have=$gotLedger",
                    undoRedoRan,
                )
            }
        }

        // A5: with GC on, every replica must also agree on how much is still held as
        // garbage -- a size that silently diverges is a real accounting bug even when the
        // ledger strings above (and the visible text) already match.
        if (gc) {
            val wantGc = cs[0].document.getDocSize().gc
            for ((idx, c) in cs.drop(1).withIndex()) {
                val gotGc = c.document.getDocSize().gc
                if (gotGc != wantGc) {
                    return SeedResult(
                        "docSize.gc diverged on client ${idx + 1}: want=$wantGc have=$gotGc",
                        undoRedoRan,
                    )
                }
            }
        }
        return SeedResult("", undoRedoRan)
    }

    private fun runSetting(
        name: String,
        gc: Boolean,
        insertPct: Int,
        clients: Int,
        required: Boolean,
    ) = runTest {
        // assumeTrue (not a silent early return) reports a skipped opt-in
        // setting as SKIPPED, not PASSED, in the test output.
        assumeTrue("opt-in: set -DTEXT_UNDO_FUZZ_FULL to run", required || full)
        val failures = mutableListOf<String>()
        for (seed in 0 until seeds) {
            val result = runSeed(seed, gc, insertPct, clients)
            if (result.failure.isNotEmpty()) {
                failures.add("seed $seed: ${result.failure}")
            } else if (result.undoRedoRan == 0) {
                // A5: a seed that never actually ran undo/redo covers none of what this
                // suite exists to fuzz -- treated as a failure the same way a convergence
                // mismatch is, not a silent pass.
                failures.add("seed $seed: no undo/redo ever ran in $steps steps")
            }
        }
        if (required) {
            assertTrue(
                failures.isEmpty(),
                "$name: ${failures.size} of $seeds seeds failed\n${failures.joinToString("\n")}",
            )
        }
    }

    @Test
    fun `gc=false insert=0% clients=3`() = runSetting(
        "gc=false insert=0% clients=3",
        false,
        0,
        3,
        required = true,
    )

    @Test
    fun `gc=false insert=100% clients=3`() =
        runSetting("gc=false insert=100% clients=3", false, 100, 3, required = true)

    @Test
    fun `gc=false insert=100% clients=4`() =
        runSetting("gc=false insert=100% clients=4", false, 100, 4, required = true)

    @Test
    fun `gc=true insert=0% clients=3`() = runSetting(
        "gc=true insert=0% clients=3",
        true,
        0,
        3,
        required = true,
    )

    // Optional, report-only: run with -DTEXT_UNDO_FUZZ_FULL=1.
    @Test
    fun `gc=true insert=100% clients=3 (optional)`() =
        runSetting("gc=true insert=100% clients=3", true, 100, 3, required = false)
}
