package dev.yorkie.api

import dev.yorkie.api.v1.watchHeartbeat
import dev.yorkie.api.v1.watchInitialization
import dev.yorkie.api.v1.watchRequest
import dev.yorkie.api.v1.watchResponse
import dev.yorkie.core.watchIdleTimeoutMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the absent-field decode shape for the Watch-heartbeat proto additions (`c69e6bda`/#1472):
 * an init frame built by an OLDER client/server that never set `heartbeat_interval_ms` decodes
 * to proto3's default `0`, which [watchIdleTimeoutMs] treats as "never idle" -- the receive loop
 * keeps today's fixed `streamTimeout` behavior unchanged (`Client.kt`'s `createDocumentWatchJob`/
 * `createChannelWatchJob`). There is no dedicated Watch converter in `api/` -- frames are
 * consumed as raw protobuf directly in `Client.kt` -- so this is a decode-shape test on the
 * generated proto types themselves, not a hand-written converter.
 */
class WatchFrameConverterTest {

    @Test
    fun `an init frame without heartbeat_interval_ms decodes to 0`() {
        val initialization = watchInitialization { }

        assertEquals(0L, initialization.heartbeatIntervalMs)
        assertEquals(0L, watchIdleTimeoutMs(initialization.heartbeatIntervalMs))
    }

    @Test
    fun `a WatchResponse with no body set reports neither initialization, event, nor heartbeat`() {
        val response = watchResponse { }

        assertFalse(response.hasInitialization())
        assertFalse(response.hasEvent())
        assertFalse(response.hasHeartbeat())
    }

    @Test
    fun `a heartbeat frame round-trips through the oneof as itself`() {
        val response = watchResponse { heartbeat = watchHeartbeat { } }

        assertTrue(response.hasHeartbeat())
        assertFalse(response.hasInitialization())
        assertFalse(response.hasEvent())
    }

    /**
     * An older-client request shape -- one built before `c1a03877`/#1483 added
     * `heartbeat_interval_ms` -- never sets the field at all, so it decodes to proto3's default
     * `0` exactly like a request that deliberately asked for none; the server cannot tell the
     * two apart, which is by design (#1483's own commit message: "0 ... is also what an SDK
     * older than the heartbeat leaves here").
     */
    @Test
    fun `a WatchRequest without heartbeat_interval_ms decodes to 0`() {
        val request = watchRequest { }

        assertEquals(0L, request.heartbeatIntervalMs)
    }
}
