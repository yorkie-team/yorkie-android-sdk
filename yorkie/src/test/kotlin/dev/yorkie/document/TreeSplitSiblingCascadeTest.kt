package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_split_sibling_cascade_test.ts` (JS SDK `6f690a14`, #1456) as
 * JVM convergence tests: the delete walk's split-sibling cascade must stop
 * at a known sibling the editor saw removed or that this delete encloses
 * whole, instead of tombstoning every unknown split product down the
 * `insNextID` chain -- which can include a concurrent product holding moved
 * text (#1408: two replicas each split `<span>de</span>` at offset 0 and
 * delete the empty left piece; without the fix one of them loses "de").
 *
 * Each case runs with both role assignments (`swap`) and both actor orders
 * (`flip`), so either side's ticket wins the race once -- 10 cases x 2 x 2
 * = 40 runs.
 */
class TreeSplitSiblingCascadeTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun treeShape(doc: Document, key: String = "t"): String {
        fun walk(node: CrdtTreeNode): String {
            val removed = if (node.isRemoved) "x" else ""
            return if (node.isText) {
                "${node.id}$removed\"${node.value}\""
            } else {
                val children = node.allChildren.joinToString(",", transform = ::walk)
                "${node.type}#${node.id}$removed[$children]"
            }
        }
        return walk((doc.getRootObject()[key] as CrdtTree).root)
    }

    private suspend fun Document.tree(key: String = "t") = getRoot().getAs<JsonTree>(key)

    private fun JsonObject.tree(key: String = "t") = getAs<JsonTree>(key)

    /** Two synced replicas holding `<doc><p>` with one `<span>` per entry of [spans]. */
    private suspend fun replicas(
        spans: List<String>,
        flip: Boolean = false,
    ): Pair<Document, Document> {
        val d1 = Document("doc").apply { setActor(if (flip) actor2 else actor1) }
        val d2 = Document("doc").apply { setActor(if (flip) actor1 else actor2) }
        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        spans.forEach { value -> element("span") { text { value } } }
                    }
                },
            )
        }.await()
        crossSync(d1, d2, overWire = true)
        return d1 to d2
    }

    private val splitSpanAt0AndDropLeft: (JsonTree) -> Unit = { t ->
        t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1) // split the span at 0
        t.editByPath(listOf(0, 1), listOf(0, 2)) // drop the empty left piece
    }

    private val enterAtSpanStart: (JsonTree) -> Unit = { t ->
        t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1) // split the span at 0
        t.splitByPath(listOf(0, 2)) // split the paragraph after it
        t.editByPath(listOf(0, 1), listOf(0, 2)) // drop the empty left piece
    }

    private data class Case(
        val name: String,
        val spans: List<String>,
        val setup: ((JsonTree) -> Unit)? = null,
        val a: (JsonTree) -> Unit,
        val b: (JsonTree) -> Unit,
        val want: String? = null,
        // One replica may keep a concurrent split product as an empty span
        // that the other tombstoned (#1408, not fixed here). Text must
        // still agree.
        val emptySpanResidue: Boolean = false,
        // Tombstones may sit in a different order on each side (a merge
        // against a split; JS main does the same). Compare shapes after GC
        // only.
        val tombstoneOrder: Boolean = false,
    )

    private val cases = listOf(
        Case(
            name = "same-boundary split at 0 + drop left piece on both sides (#1408)",
            spans = listOf("abc", "de"),
            a = splitSpanAt0AndDropLeft,
            b = splitSpanAt0AndDropLeft,
            want = "<doc><p><span>abc</span><span>de</span></p></doc>",
            emptySpanResidue = true,
        ),
        Case(
            name = "Enter at the start of a styled run on both sides (#1408)",
            spans = listOf("abc", "de"),
            a = enterAtSpanStart,
            b = enterAtSpanStart,
            want = "<doc><p><span>abc</span></p><p></p><p><span>de</span></p></doc>",
            emptySpanResidue = true,
        ),
        Case(
            name = "Enter on one side, split only on the other",
            spans = listOf("abc", "de"),
            a = enterAtSpanStart,
            b = { t ->
                t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1)
                t.splitByPath(listOf(0, 2))
            },
        ),
        Case(
            name = "split at 0 + drop left piece against a plain split at 0",
            spans = listOf("abc", "de"),
            a = splitSpanAt0AndDropLeft,
            b = { t -> t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1) },
        ),
        Case(
            name = "split at different offsets + drop left piece on both sides",
            spans = listOf("abc", "defg"),
            a = { t ->
                t.editByPath(listOf(0, 1, 1), listOf(0, 1, 1), splitLevel = 1)
                t.editByPath(listOf(0, 1), listOf(0, 2))
            },
            b = { t ->
                t.editByPath(listOf(0, 1, 3), listOf(0, 1, 3), splitLevel = 1)
                t.editByPath(listOf(0, 1), listOf(0, 2))
            },
            emptySpanResidue = true,
        ),
        Case(
            name = "split + drop left piece against deleting the whole span",
            spans = listOf("abc", "de"),
            a = { t ->
                t.editByPath(listOf(0, 1, 1), listOf(0, 1, 1), splitLevel = 1)
                t.editByPath(listOf(0, 1), listOf(0, 2))
            },
            b = { t -> t.editByPath(listOf(0, 1), listOf(0, 2)) },
            want = "<doc><p><span>abc</span></p></doc>",
            emptySpanResidue = true,
        ),
        Case(
            // <span>ab</span><span>cd</span>, the second a split product. One
            // side merges it back and deletes the whole; the other splits
            // "cd". "d" was inside what the deleter deleted.
            name = "merge the split sibling back and delete, against splitting it",
            spans = listOf("abcd"),
            setup = { t -> t.editByPath(listOf(0, 0, 2), listOf(0, 0, 2), splitLevel = 1) },
            a = { t ->
                t.editByPath(listOf(0, 0, 2), listOf(0, 1, 0))
                t.editByPath(listOf(0, 0), listOf(0, 1))
            },
            b = { t -> t.editByPath(listOf(0, 1, 1), listOf(0, 1, 1), splitLevel = 1) },
            want = "<doc><p></p></doc>",
            tombstoneOrder = true,
        ),
        Case(
            // One side deletes "ab"; the other presses Enter at the start of
            // "cd", which nobody deleted.
            name = "delete an element against Enter at the start of its split sibling",
            spans = listOf("abcd"),
            setup = { t -> t.editByPath(listOf(0, 0, 2), listOf(0, 0, 2), splitLevel = 1) },
            a = { t -> t.editByPath(listOf(0, 0), listOf(0, 1)) },
            b = { t ->
                t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), splitLevel = 1)
                t.editByPath(listOf(0, 1), listOf(0, 2))
            },
            want = "<doc><p><span>cd</span></p></doc>",
        ),
        Case(
            // One side deletes both halves; the other splits "cd" and
            // deletes "c". The deleter's delete of "cd" may lose the LWW.
            name = "delete an element and its split sibling, against splitting and " +
                "deleting that sibling",
            spans = listOf("abcd"),
            setup = { t -> t.editByPath(listOf(0, 0, 2), listOf(0, 0, 2), splitLevel = 1) },
            a = { t -> t.editByPath(listOf(0, 0), listOf(0, 2)) },
            b = { t ->
                t.editByPath(listOf(0, 1, 1), listOf(0, 1, 1), splitLevel = 1)
                t.editByPath(listOf(0, 1), listOf(0, 2))
            },
            want = "<doc><p></p></doc>",
        ),
        Case(
            // <span>ab</span><span>cd</span>, the second a split product the
            // second replica made beforehand. That replica splits each half
            // again while the other deletes the first half; "cd" was never
            // in its range.
            name = "cascade stops at a split sibling the deleter already knew",
            spans = listOf("abcd"),
            setup = { t -> t.editByPath(listOf(0, 0, 2), listOf(0, 0, 2), splitLevel = 1) },
            a = { t -> t.editByPath(listOf(0, 0), listOf(0, 1)) },
            b = { t ->
                t.editByPath(listOf(0, 1, 1), listOf(0, 1, 1), splitLevel = 1)
                t.editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
            },
            want = "<doc><p><span>c</span><span>d</span></p></doc>",
        ),
    )

    private val empty = "<span></span>"
    private fun countEmpty(xml: String) = xml.split(empty).size - 1

    @Test
    fun `split-sibling cascade keeps text the deleter did not delete`() = runTest {
        for (case in cases) {
            for (swap in listOf(false, true)) {
                for (flip in listOf(false, true)) {
                    val label = "${case.name} (swap=$swap, flip=$flip)"
                    val (d1, d2) = replicas(case.spans, flip)
                    case.setup?.let { setup ->
                        d2.updateAsync { root, _ -> setup(root.tree()) }.await()
                        crossSync(d1, d2, overWire = true)
                    }
                    val opA = if (swap) case.b else case.a
                    val opB = if (swap) case.a else case.b
                    d1.updateAsync { root, _ -> opA(root.tree()) }.await()
                    d2.updateAsync { root, _ -> opB(root.tree()) }.await()
                    crossSync(d1, d2, overWire = true)

                    var x1 = d1.tree().toXml()
                    var x2 = d2.tree().toXml()
                    val residue = x1 != x2
                    if (residue) {
                        assertTrue(case.emptySpanResidue, "XML diverged ($label):\n$x1\n$x2")
                        val n1 = countEmpty(x1)
                        val n2 = countEmpty(x2)
                        assertEquals(1, abs(n1 - n2), "not one empty span ($label):\n$x1\n$x2")
                        val keep = minOf(n1, n2)
                        fun drop(x: String, n: Int): String {
                            var result = x
                            repeat(n - keep) { result = result.replaceFirst(empty, "") }
                            return result
                        }
                        x1 = drop(x1, n1)
                        x2 = drop(x2, n2)
                        assertEquals(x1, x2, "diverged beyond an empty span ($label)")
                    } else if (!case.tombstoneOrder) {
                        assertEquals(treeShape(d1), treeShape(d2), "shape diverged ($label)")
                    }
                    case.want?.let { want ->
                        assertEquals(want, x1.replace(empty, ""), label)
                    }

                    // Every tombstone the race left is collectable on both
                    // sides, and what remains still agrees.
                    val vv = maxVectorOf(listOf(actor1, actor2))
                    d1.garbageCollect(vv)
                    d2.garbageCollect(vv)
                    assertEquals(0, d1.garbageLength, "garbage left on d1 ($label)")
                    assertEquals(0, d2.garbageLength, "garbage left on d2 ($label)")
                    if (!residue) {
                        assertEquals(
                            treeShape(d1),
                            treeShape(d2),
                            "shape diverged after gc ($label)",
                        )
                    }
                }
            }
        }
    }

    /**
     * P5 asymmetric-purge probe (AC6): not in the JS suites -- parity QA
     * cannot catch a bug shared with JS (constitution C9). Two replicas
     * race the #1408 shape (both split `<span>de</span>` at offset 0 and
     * drop the empty left piece), but purge their own tombstones at
     * DIFFERENT times -- one right after the race, knowing only its own
     * tickets, the other only after it has seen everything -- then keep
     * editing and exchanging. A cascade that is sensitive to purge timing
     * (rather than purely to what each replica's own version vector knows)
     * would diverge or panic once the early purger's physically-deleted
     * nodes are referenced by a later cascade walk on the other replica.
     */
    @Test
    fun `asymmetric purge timing still converges after the split-sibling cascade fix`() = runTest {
        val (d1, d2) = replicas(listOf("abc", "de"))
        d1.updateAsync { root, _ -> splitSpanAt0AndDropLeft(root.tree()) }.await()
        d2.updateAsync { root, _ -> splitSpanAt0AndDropLeft(root.tree()) }.await()
        crossSync(d1, d2, overWire = true)
        val beforePurgeXml = d1.tree().toXml().replace("<span></span>", "")
        assertEquals(beforePurgeXml, d2.tree().toXml().replace("<span></span>", ""))

        // d1 purges early, knowing only its own ticket; d2 has not purged
        // at all yet. A cascade walk sensitive to purge timing rather than
        // purely to version-vector knowledge would corrupt d1's tree, or a
        // later exchange with d2, once d1's tombstones are physically gone.
        d1.garbageCollect(maxVectorOf(listOf(actor1)))
        assertEquals(
            beforePurgeXml,
            d1.tree().toXml().replace("<span></span>", ""),
            "the early purge changed d1's visible content",
        )

        // A further no-op resync after the asymmetric purge must not
        // disturb either replica's visible content.
        crossSync(d1, d2, overWire = true)
        assertEquals(
            beforePurgeXml,
            d1.tree().toXml().replace("<span></span>", ""),
            "resyncing after the early purge changed d1",
        )
        assertEquals(
            beforePurgeXml,
            d2.tree().toXml().replace("<span></span>", ""),
            "resyncing after the early purge changed d2",
        )

        // d2 purges late, now knowing everything; both end up garbage-free
        // and still agree.
        val vv = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vv)
        d2.garbageCollect(vv)
        assertEquals(0, d1.garbageLength, "garbage left on the early purger")
        assertEquals(0, d2.garbageLength, "garbage left on the late purger")
        assertEquals(
            beforePurgeXml,
            d1.tree().toXml().replace("<span></span>", ""),
            "the late purge changed d1's visible content",
        )
        assertEquals(
            beforePurgeXml,
            d2.tree().toXml().replace("<span></span>", ""),
            "the late purge changed d2's visible content",
        )
    }
}
