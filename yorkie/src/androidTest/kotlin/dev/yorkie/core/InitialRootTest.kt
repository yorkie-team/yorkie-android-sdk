package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.document.json.JsonPrimitive
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InitialRootTest {

    @get:Rule
    val retryRule = RetryRule(retryCount = 2)

    @Test
    fun initialTreeSyncsWithoutReplacementAndRemainsOutsideUndoHistory() {
        withTwoClientsAndDocuments(
            attachDocuments = false,
            detachDocuments = false,
            syncMode = Manual,
        ) { c1, c2, d1, d2, _ ->
            c1.attachDocument(
                document = d1,
                syncMode = Manual,
                initialRoot = mapOf(
                    "tree" to { key ->
                        setNewTree(
                            key,
                            element("doc") {
                                element("p") { text { "initial" } }
                            },
                        )
                    },
                ),
            ).await()
            assertFalse(d1.history.canUndo())
            c1.syncAsync(d1).await()

            var replacementCalled = false
            c2.attachDocument(
                document = d2,
                syncMode = Manual,
                initialRoot = mapOf(
                    "tree" to { key ->
                        replacementCalled = true
                        setNewTree(key, element("replacement"))
                    },
                ),
            ).await()

            assertFalse(replacementCalled)
            assertEquals(
                "<doc><p>initial</p></doc>",
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
            )

            d1.updateAsync { root, _ ->
                root["edit"] = "value"
            }.await()
            assertTrue(d1.history.canUndo())
            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            assertEquals("value", d2.getRoot().getAs<JsonPrimitive>("edit").value)

            d1.history.undoAsync().await()
            assertEquals(
                "<doc><p>initial</p></doc>",
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            assertFalse(d1.history.canUndo())
            assertTrue(d1.history.canRedo())
            assertFalse("edit" in d2.getRoot().keys)
            assertEquals(null, d2.getRoot().getOrNull("edit"))

            d1.history.redoAsync().await()
            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            assertEquals("value", d2.getRoot().getAs<JsonPrimitive>("edit").value)

            c1.detachDocument(d1).await()
            c2.detachDocument(d2).await()
        }
    }

    @Test
    fun simultaneousInitialRootsConvergeThroughNormalSynchronization() {
        withTwoClientsAndDocuments(
            attachDocuments = false,
            detachDocuments = false,
            syncMode = Manual,
        ) { c1, c2, d1, d2, _ ->
            listOf(
                async {
                    c1.attachDocument(
                        document = d1,
                        syncMode = Manual,
                        initialRoot = mapOf("value" to { key -> this[key] = "one" }),
                    ).await()
                },
                async {
                    c2.attachDocument(
                        document = d2,
                        syncMode = Manual,
                        initialRoot = mapOf("value" to { key -> this[key] = "two" }),
                    ).await()
                },
            ).awaitAll().forEach { assertTrue(it.isSuccess) }

            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            c1.syncAsync(d1).await()

            assertEquals(d1.toJson(), d2.toJson())

            c1.detachDocument(d1).await()
            c2.detachDocument(d2).await()
        }
    }
}
