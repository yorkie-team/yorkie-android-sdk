package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo

/**
 * [CrdtElement] represents element type containing logical clock.
 */
internal abstract class CrdtElement(
    val createdAt: TimeTicket,
    movedAt: TimeTicket? = null,
    var removedAt: TimeTicket? = null,
) {
    abstract fun toJson(): String

    abstract fun toSortedJson(): String

    abstract fun deepCopy(): CrdtElement

    val id: TimeTicket
        get() = createdAt

    val isRemoved: Boolean
        get() = removedAt != null

    var movedAt: TimeTicket? = movedAt
        set(value) {
            if (value > field) {
                field = value
            }
        }

    fun remove(removedAt: TimeTicket?): Boolean {
        if (createdAt < removedAt && this.removedAt < removedAt) {
            this.removedAt = removedAt
            return true
        }
        return false
    }
}
