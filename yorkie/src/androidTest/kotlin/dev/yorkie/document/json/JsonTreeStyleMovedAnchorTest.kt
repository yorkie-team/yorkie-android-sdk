package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.TreeTest
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.JsonTreeTest.Companion.Updater
import dev.yorkie.document.json.JsonTreeTest.Companion.assertTreesXmlEquals
import dev.yorkie.document.json.JsonTreeTest.Companion.rootTree
import dev.yorkie.document.json.JsonTreeTest.Companion.updateAndSync
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ports the headline case of `tree_style_moved_anchor_test.ts` (JS SDK
 * 1c033ff5, mirrors yorkie#1928) as an instrumented two-client test against
 * the real server (0.7.16 pin, AC11): a style range whose declared end
 * anchor sits inside a parent a concurrent merge tombstoned must not leak
 * onto a sibling the OTHER client inserted at that anchor — on either
 * replica, after full sync through the server.
 */
@TreeTest
@RunWith(AndroidJUnit4::class)
class JsonTreeStyleMovedAnchorTest {

    @Test
    fun test_style_does_not_apply_to_a_node_inserted_at_the_merge_anchor() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.setNewTree(
                        "t",
                        element("r") {
                            element("p") { text { "ab" } }
                            element("p") { text { "cd" } }
                        },
                    )
                },
                Updater(c2, d2),
            )
            assertTreesXmlEquals("<r><p>ab</p><p>cd</p></r>", d1, d2)

            updateAndSync(
                Updater(c1, d1) { root, _ ->
                    root.rootTree().edit(8, 8, element("x"))
                    root.rootTree().style(0, 6, mapOf("bold" to "x"))
                },
                Updater(c2, d2) { root, _ ->
                    root.rootTree().edit(0, 5)
                },
            )

            assertTreesXmlEquals("<r><x></x>cd</r>", d1, d2)
        }
    }
}
