package dev.yorkie.document.crdt

import dev.yorkie.document.json.JsonStringifier.toJsonString
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo

/**
 * [CrdtElement] represents element type containing logical clock.
 */
@Suppress("PropertyName")
internal abstract class CrdtElement {
    abstract val createdAt: TimeTicket
    protected abstract var _movedAt: TimeTicket?
    protected abstract var _removedAt: TimeTicket?

    val id: TimeTicket
        get() = createdAt

    var movedAt: TimeTicket?
        get() = _movedAt
        private set(value) {
            _movedAt = value
        }

    var removedAt: TimeTicket?
        get() = _removedAt
        private set(value) {
            _removedAt = value
        }

    val isRemoved: Boolean
        get() = removedAt != null

    fun move(movedAt: TimeTicket?): Boolean {
        if (movedAt > this.movedAt) {
            this.movedAt = movedAt
            return true
        }
        return false
    }

    fun remove(removedAt: TimeTicket?): Boolean {
        if (createdAt < removedAt && this.removedAt < removedAt) {
            this.removedAt = removedAt
            return true
        }
        return false
    }

    public fun toJson(): String {
        return toJsonString()
    }

    abstract fun deepCopy(): CrdtElement
}
