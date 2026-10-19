package dev.yorkie.document

import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.exchange
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_remove_style_split_test.ts` (JS SDK c8928853, #1375) as JVM
 * in-process convergence tests.
 *
 * [CrdtTree.removeStyle][dev.yorkie.document.crdt.CrdtTree.removeStyle] now
 * advances past unknown split siblings on both range anchors, the same as
 * `style()` does -- so a `removeStyle` whose range was determined before a
 * concurrent element split also covers the right part of the split,
 * regardless of which replica's change arrives first.
 */
class TreeRemoveStyleSplitTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun JsonObject.tree() = getAs<JsonTree>("t")

    /**
     * Two replicas seeded with `<doc><p><span bold="true">abcde</span></p></doc>`,
     * already synced from replica 0.
     */
    private suspend fun replicas(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("span") {
                            attr { "bold" to "true" }
                            text { "abcde" }
                        }
                    }
                },
            )
        }.await()
        exchange(listOf(d1, d2), listOf(emptyList(), listOf(0)), overWire = true)
        return d1 to d2
    }

    @Test
    fun `converges whichever side arrives first`() = runTest {
        val (d1, d2) = replicas()

        d1.updateAsync { root, _ ->
            root.tree().editByPath(
                listOf(0, 0, 3),
                listOf(0, 0, 3),
                splitLevel = 1,
            )
        }
            .await()
        d2.updateAsync { root, _ ->
            root.tree().removeStyleByPath(listOf(0, 0), listOf(0, 1), listOf("bold"))
        }.await()
        exchange(listOf(d1, d2), listOf(listOf(1), listOf(0)), overWire = true)

        assertEquals(d1.getRoot().tree().toXml(), d2.getRoot().tree().toXml())
    }

    @Test
    fun `converges when both replicas split and one removes the style`() = runTest {
        val (d1, d2) = replicas()

        d1.updateAsync { root, _ ->
            val t = root.tree()
            t.editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
            t.removeStyleByPath(listOf(0, 0), listOf(0, 1), listOf("bold"))
        }.await()
        d2.updateAsync { root, _ ->
            root.tree().editByPath(
                listOf(0, 0, 3),
                listOf(0, 0, 3),
                splitLevel = 1,
            )
        }
            .await()
        exchange(listOf(d1, d2), listOf(listOf(1), listOf(0)), overWire = true)

        assertEquals(d1.getRoot().tree().toXml(), d2.getRoot().tree().toXml())
    }
}
