package dev.yorkie.document.change

import kotlin.math.max

/**
 * [CheckPoint] is used to determine the changes sent and received by the client.
 * This is immutable.
 */
internal data class CheckPoint(
    val serverSeq: Long,
    val clientSeq: Int,
) {

    /**
     * Creates a new instance with increased client sequence.
     */
    fun increaseClientSeq(increase: Int): CheckPoint {
        return if (increase == 0) {
            this
        } else {
            copy(clientSeq = clientSeq + increase)
        }
    }

    /**
     * Creates a new instance with the given [CheckPoint] if it is greater
     * than the values of internal properties.
     */
    fun forward(checkPoint: CheckPoint): CheckPoint {
        return if (this == checkPoint) {
            this
        } else {
            val serverSeq = max(serverSeq, checkPoint.serverSeq)
            val clientSeq = max(clientSeq, checkPoint.clientSeq)
            CheckPoint(serverSeq, clientSeq)
        }
    }

    companion object {
        val InitialCheckPoint = CheckPoint(0, 0)
    }
}
