package dev.yorkie.util

import java.util.PriorityQueue

/**
 * Simple extension of [PriorityQueue] to implement a max priority queue.
 * Although extension is not necessary, this is done to clearly specify that we are using a max priority queue.
 */
internal class MaxPriorityQueue<E : Comparable<E>>(
    initialCapacity: Int = DEFAULT_INITIAL_CAPACITY,
) : PriorityQueue<E>(initialCapacity, reverseOrder()) {

    companion object {
        private const val DEFAULT_INITIAL_CAPACITY = 11
    }
}
