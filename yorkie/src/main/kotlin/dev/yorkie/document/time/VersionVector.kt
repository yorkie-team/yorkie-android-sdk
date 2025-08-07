package dev.yorkie.document.time

/**
 * `VersionVector` is a vector clock used to detect relationships between changes,
 * determining whether they are causally related or concurrent.
 * It is similar to vector clocks but synced with the Lamport timestamp of the change.
 */

class VersionVector(vectorMap: Map<String, Long> = emptyMap()) {
    val vectorMap: MutableMap<String, Long> = mutableMapOf()

    init {
        this.vectorMap.putAll(vectorMap)
    }

    /**
     * Sets the Lamport timestamp for the given actor.
     */
    fun set(actorID: String, lamport: Long) {
        vectorMap[actorID] = lamport
    }

    /**
     * Gets the Lamport timestamp for the given actor.
     */
    fun get(actorID: String): Long? = vectorMap[actorID]

    /**
     * Returns the maximum Lamport value from the vector.
     */
    fun maxLamport(): Long = vectorMap.values.maxOrNull() ?: 0

    /**
     * Returns a new `VersionVector` consisting of the maximum values of each vector.
     */
    fun max(other: VersionVector): VersionVector {
        val maxVector = mutableMapOf<String, Long>()

        other.vectorMap.forEach { (actorID, lamport) ->
            val currentLamport = vectorMap[actorID]
            maxVector[actorID] = if (currentLamport != null && currentLamport > lamport) {
                currentLamport
            } else {
                lamport
            }
        }

        vectorMap.forEach { (actorID, lamport) ->
            val otherLamport = other.vectorMap[actorID]
            maxVector[actorID] = if (otherLamport != null && otherLamport > lamport) {
                otherLamport
            } else {
                lamport
            }
        }

        return VersionVector(maxVector)
    }

    /**
     * Returns true if `vector[other.actorID.value]` is greater than or equal to the given ticket's Lamport.
     */
    fun afterOrEqual(other: TimeTicket): Boolean {
        val lamport = vectorMap[other.actorID.value]
        return lamport != null && lamport >= other.lamport
    }

    /**
     * Returns a new `VersionVector` consisting of entries filtered by the given `VersionVector`.
     */
    fun filter(versionVector: VersionVector): VersionVector {
        val filtered = versionVector.vectorMap.keys.mapNotNull { actorID ->
            vectorMap[actorID]?.let { actorID to it }
        }.toMap().toMutableMap()

        return VersionVector(filtered)
    }

    /**
     * Returns the size of the `VersionVector`.
     */
    fun size(): Int = vectorMap.size

    /**
     * Deep copy of this `VersionVector`.
     */
    fun deepCopy(): VersionVector = VersionVector(vectorMap.toMutableMap())

    /**
     * `unset` removes the version for the given actor from the VersionVector.
     */
    fun unset(actorID: String) {
        vectorMap.remove(actorID)
    }

    /**
     * Allows iteration over the `VersionVector`.
     */
    operator fun iterator(): Iterator<Map.Entry<String, Long>> = vectorMap.entries.iterator()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VersionVector) return false
        return this.vectorMap == other.vectorMap
    }

    override fun hashCode(): Int {
        return vectorMap.hashCode()
    }

    companion object {
        /**
         * `INITIAL_VERSION_VECTOR` is the initial version vector.
         */
        val INITIAL_VERSION_VECTOR = VersionVector()
    }
}
