package dev.yorkie.document.crdt

import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.Logger
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Ports `tree_test.ts`'s brute-force splitLevel case (JS SDK 2ef3260b, #1289)
 * as a JVM unit test (AC1, AC13-root).
 *
 * Before the fix, the splitLevel ancestor walk in [CrdtTree.edit] split a
 * node before checking whether it had a parent, so reaching the tree root
 * split the root itself and orphaned the clone. The fix stops the walk one
 * step earlier and logs via the project logger (approved divergence: JS
 * throws instead).
 */
class CrdtTreeSplitLevelTest {

    private val recordingLogger = RecordingLogger()

    @Before
    fun setUp() {
        Logger.init(recordingLogger)
    }

    @After
    fun tearDown() {
        // Reinstall a fresh, empty logger so captured state does not leak
        // into other test classes — Logger's backing instance is a
        // process-wide singleton.
        Logger.init(RecordingLogger())
    }

    private data class Seed(val name: String, val root: JsonTree.ElementNode, val length: Int)

    // <doc><p>x</p></doc>, <doc><p>x</p><p>y</p></doc>, <doc><p><b>x</b></p></doc>,
    // <doc><p></p></doc> — mirrors the JS SDK brute-force seeds exactly.
    private val seeds = listOf(
        Seed("shallow", element("doc") { element("p") { text { "x" } } }, length = 3),
        Seed(
            "twoP",
            element("doc") {
                element("p") { text { "x" } }
                element("p") { text { "y" } }
            },
            length = 6,
        ),
        Seed("deep", element("doc") { element("p") { element("b") { text { "x" } } } }, length = 5),
        Seed("emptyP", element("doc") { element("p") }, length = 2),
    )

    @Test
    fun `edit with splitLevel walking past root never throws and always applies the insertion`() =
        runTest {
            for (seed in seeds) {
                for (pos in 0..seed.length) {
                    for (splitLevel in 1..2) {
                        val label = "seed=${seed.name} pos=$pos splitLevel=$splitLevel"
                        val document = Document("")
                        document.updateAsync { root, _ ->
                            root.setNewTree(key = "t", initialRoot = seed.root)
                        }.await()

                        val sizeBefore = document.getRoot().getAs<JsonTree>("t").size

                        // Must not throw for any seed/position/splitLevel combination.
                        document.updateAsync { root, _ ->
                            root.getAs<JsonTree>("t").edit(pos, pos, splitLevel, text { "a" })
                        }.await()

                        val tree = document.getRoot().getAs<JsonTree>("t")
                        assertTrue(tree.size > sizeBefore, "$label: tree size should grow")
                        assertTrue(
                            tree.toXml().contains("a"),
                            "$label: inserted text should be present, got ${tree.toXml()}",
                        )
                        document.close()
                    }
                }
            }

            // AC13-root: pos=0 always lands the insertion at the tree root itself,
            // so the guard (parent.parent == null) fires on the very first walk
            // iteration for every seed/splitLevel combination above — assert it
            // logged at least once via the project logger, with no throw.
            assertTrue(
                recordingLogger.debugMessages.any {
                    it.contains("splitLevel walk reached tree root")
                },
                "expected the root-stop guard to log at least once during the sweep",
            )
        }
}
