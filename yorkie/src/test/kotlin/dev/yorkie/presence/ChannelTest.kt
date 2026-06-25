package dev.yorkie.presence

import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
