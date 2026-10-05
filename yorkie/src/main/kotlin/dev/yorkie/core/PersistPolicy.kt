package dev.yorkie.core

/**
 * The floor below which a log never triggers compaction. Without it the ratio alone would
 * make the smallest documents the busiest: a 500-byte document would compact after two
 * changes. Ported from yorkie-js-sdk `persist-policy.ts` (`aaa5cb15`/#1354).
 */
@Suppress("ktlint:standard:property-naming")
internal const val MinLogBytes = 64 * 1024

/**
 * The share of the snapshot's size the log may reach before it is worth re-snapshotting.
 * Ported from yorkie-js-sdk `persist-policy.ts` (`aaa5cb15`/#1354).
 */
@Suppress("ktlint:standard:property-naming")
internal const val LogRatio = 0.5

/**
 * Bounds how many changes a restore has to replay. This is a latency budget rather than a
 * storage one, which is why it is separate from the byte rule. Ported from yorkie-js-sdk
 * `persist-policy.ts` (`aaa5cb15`/#1354).
 */
@Suppress("ktlint:standard:property-naming")
internal const val MaxReplay = 1000

/**
 * What the compaction decision is made from. Ported from yorkie-js-sdk `persist-policy.ts`
 * (`aaa5cb15`/#1354).
 *
 * @param snapshotBytes size of the stored snapshot the log is appended to.
 * @param logBytes total size of the appended change log.
 * @param changeCount number of appended changes.
 */
internal class PersistState(
    public val snapshotBytes: Int,
    public val logBytes: Int,
    public val changeCount: Int,
)

/**
 * Decides whether the appended log has grown enough to be worth replacing with a fresh
 * snapshot. Ported from yorkie-js-sdk `persist-policy.ts` (`aaa5cb15`/#1354).
 *
 * The threshold is **relative to the snapshot**, not a constant, because the point at which
 * appending stops paying is a function of what it is appended to. Measured, an 8,000-cell
 * sheet's log reaches its snapshot's size after roughly 6,300 edits while a 5,000-character
 * note's does after roughly 50 — two orders of magnitude apart, so no single count or byte
 * figure serves both.
 *
 * Making it relative also removes the pathological case by construction. A small document
 * compacts often, which is harmless precisely because its snapshot is small and serializes
 * in about a millisecond; a large one compacts rarely, so its expensive serialization is
 * divided across thousands of edits. Cost and threshold scale with the same quantity, so
 * "compacts frequently *and* expensively" is unreachable.
 */
internal fun shouldCompact(state: PersistState): Boolean {
    if (state.changeCount > MaxReplay) {
        return true
    }
    return state.logBytes > maxOf(MinLogBytes.toDouble(), state.snapshotBytes * LogRatio)
}
