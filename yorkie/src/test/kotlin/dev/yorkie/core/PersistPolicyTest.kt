package dev.yorkie.core

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Port of yorkie-js-sdk's `persist_policy_test.ts` (`aaa5cb15`/#1354).
 * Pins [shouldCompact]'s figures and the strict `>` boundary on both rules — a `>=` here would
 * compact a document exactly at the floor, which the JS rationale explicitly rules out.
 */
class PersistPolicyTest {

    @Test
    fun `12a a small log against a large snapshot does not compact`() {
        assertFalse(shouldCompact(PersistState(2_770_000, 70_000, 200)))
    }

    @Test
    fun `12b the same log against a much smaller snapshot does compact`() {
        assertTrue(shouldCompact(PersistState(15_000, 70_000, 200)))
    }

    @Test
    fun `12c a tiny document with a tiny log does not compact`() {
        assertFalse(shouldCompact(PersistState(500, 2_000, 5)))
    }

    @Test
    fun `12d a tiny document whose log exceeds the floor does compact`() {
        assertTrue(shouldCompact(PersistState(500, MinLogBytes + 1, 5)))
    }

    @Test
    fun `12e a change count over MaxReplay compacts regardless of bytes`() {
        assertTrue(shouldCompact(PersistState(10_000_000, 1_000, MaxReplay + 1)))
    }

    @Test
    fun `12f a change count at MaxReplay does not compact on its own`() {
        assertFalse(shouldCompact(PersistState(10_000_000, 1_000, MaxReplay)))
    }

    @Test
    fun `12g an all-zero state does not compact`() {
        assertFalse(shouldCompact(PersistState(0, 0, 0)))
    }

    @Test
    fun `boundary logBytes exactly at MinLogBytes with a tiny snapshot does not compact`() {
        assertFalse(
            shouldCompact(
                PersistState(snapshotBytes = 10, logBytes = MinLogBytes, changeCount = 0),
            ),
        )
    }

    @Test
    fun `boundary logBytes one over MinLogBytes with a tiny snapshot does compact`() {
        assertTrue(
            shouldCompact(
                PersistState(snapshotBytes = 10, logBytes = MinLogBytes + 1, changeCount = 0),
            ),
        )
    }

    @Test
    fun `boundary changeCount exactly at MaxReplay does not compact on its own`() {
        assertFalse(
            shouldCompact(PersistState(snapshotBytes = 0, logBytes = 0, changeCount = MaxReplay)),
        )
    }

    @Test
    fun `boundary changeCount one over MaxReplay does compact`() {
        assertTrue(
            shouldCompact(
                PersistState(snapshotBytes = 0, logBytes = 0, changeCount = MaxReplay + 1),
            ),
        )
    }
}
