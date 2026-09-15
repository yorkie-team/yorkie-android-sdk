package dev.yorkie.document

import dev.yorkie.document.json.JsonCounter
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the two-client out-of-int32 Long increase case from
 * `counter_test.ts` (JS SDK bb3c73dc, v0.7.15, JS #1312) as a JVM in-process
 * cross-sync test (spec 006 AC8). Pins the wrap invariant across replicas;
 * no production code changed for this port (Stage B no-code determination —
 * see [dev.yorkie.document.crdt.CrdtCounterTest]'s unit cases for the
 * single-replica pin).
 */
class CounterWrapConvergenceTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    @Test
    fun `converges on the wrapped value when an out-of-int32 Long is applied concurrently`() =
        runTest {
            val d1 = Document("test-doc")
            val d2 = Document("test-doc")
            d1.setActor(actor1)
            d2.setActor(actor2)

            d1.updateAsync { root, _ -> root.setNewCounter("age", 1) }.await()
            crossSync(d1, d2)

            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("age").increase(5_000_000_000L)
            }.await()
            crossSync(d1, d2)

            // 1 + 5_000_000_000 wraps to 705032705 under int32 arithmetic
            // (matches the Go SDK), same as the fixed JS SDK.
            assertEquals(705032705, d1.getRoot().getAs<JsonCounter>("age").value)
            assertEquals(
                d1.getRoot().getAs<JsonCounter>("age").value,
                d2.getRoot().getAs<JsonCounter>("age").value,
            )
        }
}
