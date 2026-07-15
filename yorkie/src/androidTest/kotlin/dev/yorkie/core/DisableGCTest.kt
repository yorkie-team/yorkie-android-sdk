package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonCounter
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisableGCTest {

    @Test
    fun test_disable_gc_attachment_converges_on_counter_workload() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            c1.activateAsync().await()
            c2.activateAsync().await()

            val documentKey = "disable-gc-${UUID.randomUUID()}".toDocKey()
            val d1 = Document(documentKey)
            val d2 = Document(documentKey)

            // Both clients opt out of server-side GC tracking; the wire flag
            // rides on attach and every push-pull
            c1.attachDocument(d1, syncMode = Client.SyncMode.Manual, disableGC = true).await()
            c2.attachDocument(d2, syncMode = Client.SyncMode.Manual, disableGC = true).await()

            d1.updateAsync { root, _ ->
                root.setNewCounter("counter", 0)
            }.await()
            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(5)
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonCounter>("counter").increase(3)
            }.await()

            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            c1.syncAsync(d1).await()

            assertEquals("""{"counter":8}""", d1.toJson())
            assertEquals("""{"counter":8}""", d2.toJson())

            c1.detachDocument(d1).await()
            c2.detachDocument(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }
}
