package dev.yorkie.presence

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ChannelTest {

    @Test
    fun `updateSessionCount ignores stale sequence number`() {
        // given
        val channel = Channel("key")
        channel.updateSessionCount(5L, 10L)

        // when
        val updated = channel.updateSessionCount(3L, 9L)

        // then
        assertFalse(updated)
        assertEquals(5L, channel.getSessionCount())
    }

    @Test
    fun `updateSessionCount accepts seq zero as initialization bypass`() {
        // given
        val channel = Channel("key")
        channel.updateSessionCount(5L, 10L)

        // when
        val updated = channel.updateSessionCount(2L, 0L)

        // then
        assertTrue(updated)
        assertEquals(2L, channel.getSessionCount())
    }

    @Test
    fun `hasLocalChanges always returns false`() {
        assertFalse(Channel("key").hasLocalChanges())
    }

    @Test
    fun `getSessionId returns null before any session id is set`() {
        assertNull(Channel("key").getSessionId())
    }

    @Test
    fun `setSessionId accepts null to clear a previously set session id`() {
        // given
        val channel = Channel("key")
        channel.setSessionId("session-1")
        assertEquals("session-1", channel.getSessionId())

        // when
        channel.setSessionId(null)

        // then
        assertNull(channel.getSessionId())
    }
}
