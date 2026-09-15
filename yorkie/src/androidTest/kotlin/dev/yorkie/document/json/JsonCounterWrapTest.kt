package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ports the two-client out-of-int32 Long increase case from
 * `test/integration/counter_test.ts` (JS SDK bb3c73dc, v0.7.15, JS #1312)
 * against the real Yorkie server. The in-memory twin
 * (`CounterWrapConvergenceTest`) pins the arithmetic; this pins the wire: the
 * Long delta must survive `IncreaseOperation` -> protobuf -> server -> peer
 * untruncated, so both clients agree with the Go server's int32 result.
 */
@RunWith(AndroidJUnit4::class)
class JsonCounterWrapTest {

    @Test
    fun test_out_of_int32_long_increase_wraps_identically_over_the_wire() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ -> root.setNewCounter("age", 1) }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonCounter>("age").increase(5_000_000_000L)
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // 1 + 5_000_000_000 wraps to 705032705 under int32 arithmetic (the
            // literal upstream asserts), on the sender and on the peer alike.
            assertEquals(705032705, d1.getRoot().getAs<JsonCounter>("age").value)
            assertEquals(705032705, d2.getRoot().getAs<JsonCounter>("age").value)
            assertEquals(d1.toJson(), d2.toJson())
        }
    }
}
