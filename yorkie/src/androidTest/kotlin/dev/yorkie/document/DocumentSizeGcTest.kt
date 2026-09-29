package dev.yorkie.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented two-client DocSize convergence + collection test against a
 * real Yorkie server (AC7, S9 compose pin at `yorkieteam/yorkie:0.7.17`) —
 * ports the `document_size_test.ts` container-GC invariant (JS SDK
 * `611e6e43`, #1322) end to end over the wire, complementing the in-process
 * `crossSync` cases in `DocumentSizeContainerGcTest`.
 */
@RunWith(AndroidJUnit4::class)
class DocumentSizeGcTest {

    @Test
    fun test_docSize_converges_after_removing_a_non_empty_container() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewObject("k").apply {
                    this["a"] = "1"
                    this["b"] = "2"
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            d2.updateAsync { root, _ -> root.remove("k") }.await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            assertEquals(d2.getDocSize(), d1.getDocSize())
            assertEquals("{}", d1.toJson())
            assertEquals("{}", d2.toJson())

            // Settle several extra rounds so the server's min-synced version
            // vector advances past the tombstone and the client-side GC
            // inside applyChangePack purges it on both sides (the
            // JsonTreeRestoreTest settle pattern).
            repeat(5) {
                c1.syncAsync().await()
                c2.syncAsync().await()
            }

            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)

            val emptyDocumentSize = Document("").getDocSize()
            assertEquals(emptyDocumentSize, d1.getDocSize())
            assertEquals(emptyDocumentSize, d2.getDocSize())
        }
    }
}
