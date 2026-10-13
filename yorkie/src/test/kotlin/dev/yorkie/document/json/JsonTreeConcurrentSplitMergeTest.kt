package dev.yorkie.document.json

import dev.yorkie.document.Document
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports JS `test/integration/tree_concurrent_split_merge_test.ts` (v0.7.23,
 * #1358, `9c15ab29`) as JVM in-process cross-sync tests: two replicas edit a
 * tree around a split/merge boundary without having seen each other's op,
 * then converge. Pins that the
 * `editInternal`-based `splitByPath`/`mergeByPath` (commit 1) neither
 * duplicates nor loses content across a concurrent edit -- the exact defect
 * the old copy-based helpers had (a deleted copy of the split/merged content
 * survived as a second, diverging version on each replica).
 */
class JsonTreeConcurrentSplitMergeTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun JsonObject.tree() = getAs<JsonTree>("t")

    /** `<doc><p><span>abcde</span></p></doc>`. */
    private val oneSpan = element("doc") {
        element("p") {
            element("span") { text { "abcde" } }
        }
    }

    /** [oneSpan] with the span carrying `bold`. */
    private val boldSpan = element("doc") {
        element("p") {
            element("span") {
                attr { "bold" to "true" }
                text { "abcde" }
            }
        }
    }

    /** `<doc><p><span>abc</span><span>de</span></p></doc>`. */
    private val twoSpans = element("doc") {
        element("p") {
            element("span") { text { "abc" } }
            element("span") { text { "de" } }
        }
    }

    /** `<doc><p><span>ab</span><span>cd</span><span>ef</span></p></doc>`. */
    private val threeSpans = element("doc") {
        element("p") {
            element("span") { text { "ab" } }
            element("span") { text { "cd" } }
            element("span") { text { "ef" } }
        }
    }

    /**
     * Seeds both replicas with [initial], runs [op1] on [d1] and [op2] on
     * [d2] without either having seen the other, cross-syncs until they
     * settle, and asserts both converge on [expected] XML and equal JSON.
     */
    private suspend fun concurrently(
        initial: JsonTree.ElementNode,
        op1: (JsonTree) -> Unit,
        op2: (JsonTree) -> Unit,
        expected: String,
    ) {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewTree("t", initial) }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> op1(root.tree()) }.await()
        d2.updateAsync { root, _ -> op2(root.tree()) }.await()

        crossSync(d1, d2)
        crossSync(d1, d2)

        assertEquals(expected, d1.getRoot().tree().toXml())
        assertEquals(d1.toJson(), d2.toJson())
    }

    @Test
    fun `does not duplicate content when two replicas split the same position`() = runTest {
        // The tail survives once, not twice.
        //
        // KNOWN LIMITATION (tracked, matches JS): each replica still
        // contributes its own boundary, so an empty node sits between them.
        // Collapsing the two into one would mean recognizing a concurrent
        // split at the same position, which the Android port (mirroring JS's
        // own §7.5 advance) does not do today. The empty node is asserted
        // rather than tolerated.
        concurrently(
            oneSpan,
            { it.splitByPath(listOf(0, 0, 3)) },
            { it.splitByPath(listOf(0, 0, 3)) },
            "<doc><p><span>abc</span><span></span><span>de</span></p></doc>",
        )
    }

    @Test
    fun `does not duplicate content when two replicas merge the same boundary`() = runTest {
        concurrently(
            twoSpans,
            { it.mergeByPath(listOf(0, 1)) },
            { it.mergeByPath(listOf(0, 1)) },
            "<doc><p><span>abcde</span></p></doc>",
        )
    }

    @Test
    fun `keeps every span when two replicas merge neighbouring boundaries`() = runTest {
        // Both merges land, so all three spans end up as one. Copying the
        // children lost a span instead: each replica deleted the node it
        // merged and re-inserted its children into a left sibling the other
        // replica had already removed, and both agreed on `abcd`.
        concurrently(
            threeSpans,
            { it.mergeByPath(listOf(0, 1)) },
            { it.mergeByPath(listOf(0, 2)) },
            "<doc><p><span>abcdef</span></p></doc>",
        )
    }

    @Test
    fun `keeps the text in order when two replicas split at different positions`() = runTest {
        // Two boundaries in one node give three pieces. Copying the tail
        // wrote each replica's view of it into the tree, landing on `aebcde`.
        concurrently(
            oneSpan,
            { it.splitByPath(listOf(0, 0, 1)) },
            { it.splitByPath(listOf(0, 0, 4)) },
            "<doc><p><span>a</span><span>bcd</span><span>e</span></p></doc>",
        )
    }

    @Test
    fun `applies a concurrent style change to both halves of a split`() = runTest {
        // The half the split opened is the same node to the style, so
        // clearing the formatting reaches it. A copied node was one the
        // concurrent style had never seen, so it kept `bold` and the text
        // came back half formatted.
        concurrently(
            boldSpan,
            { it.splitByPath(listOf(0, 0, 2)) },
            { it.removeStyleByPath(listOf(0, 0), listOf(0, 1), listOf("bold")) },
            "<doc><p><span>ab</span><span>cde</span></p></doc>",
        )
    }

    @Test
    fun `keeps the text in order when a split meets a merge`() = runTest {
        // The two structural changes compose: the split boundary stands and
        // the rest merges behind it. Copying the content moved it instead,
        // landing on `<span>ade</span><span>bc</span>` -- the same text,
        // reordered, on both replicas.
        concurrently(
            twoSpans,
            { it.mergeByPath(listOf(0, 1)) },
            { it.splitByPath(listOf(0, 0, 1)) },
            "<doc><p><span>a</span><span>bcde</span></p></doc>",
        )
    }
}
