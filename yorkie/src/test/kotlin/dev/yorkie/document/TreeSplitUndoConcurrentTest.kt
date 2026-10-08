package dev.yorkie.document

import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports JS `test/integration/history_tree_concurrent_test.ts` "undo one of
 * two concurrent splits of the same node" (v0.7.23, part of #1360's
 * `e41069df`) as a JVM in-process cross-sync test: two replicas each split
 * the same `<span>abcde</span>` without having seen the other's split. After
 * they converge, one replica undoes its OWN split. The undo's reverse must
 * be sized from the measured split boundary (not `2 * actualSplitLevel`,
 * which pre-dates the concurrent peer's boundary) and `reconcileOperation`
 * must shift the undo's stored offsets by the OTHER replica's split boundary
 * growth -- exactly the stacked-reverse mis-indexing this port fixes
 * (reverting that fix reproduces the divergence asserted below).
 */
class TreeSplitUndoConcurrentTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun JsonObject.tree() = getAs<JsonTree>("t")

    @Test
    fun `undo one of two concurrent splits of the same node`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("span") { text { "abcde" } }
                    }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d1 splits after `a`; d2 splits before `e` -- neither has seen the
        // other's split yet.
        d1.updateAsync { root, _ ->
            root.tree().editByPath(listOf(0, 0, 1), listOf(0, 0, 1), splitLevel = 1)
        }.await()
        d2.updateAsync { root, _ ->
            root.tree().editByPath(listOf(0, 0, 4), listOf(0, 0, 4), splitLevel = 1)
        }.await()

        crossSync(d1, d2)
        crossSync(d1, d2)

        assertEquals(
            "<doc><p><span>a</span><span>bcd</span><span>e</span></p></doc>",
            d1.getRoot().tree().toXml(),
        )

        // d2 undoes its OWN split (the bcd|e boundary). d1's split (a|bcd)
        // must survive.
        d2.history.undoAsync().await()
        crossSync(d1, d2)
        crossSync(d1, d2)

        assertEquals(
            "<doc><p><span>a</span><span>bcde</span></p></doc>",
            d1.getRoot().tree().toXml(),
        )
        assertEquals(d1.toJson(), d2.toJson())
    }
}
