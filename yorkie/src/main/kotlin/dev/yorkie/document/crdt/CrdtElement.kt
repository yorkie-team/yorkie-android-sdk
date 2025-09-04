package dev.yorkie.document.crdt

import dev.yorkie.document.json.JsonStringifier.toJsonString
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.DataSize

/**
 * [CrdtElement] represents an element that has [TimeTicket]s.
 */
@Suppress("PropertyName")
abstract class CrdtElement {
    abstract val createdAt: TimeTicket
    protected abstract var _movedAt: TimeTicket?
    protected abstract var _removedAt: TimeTicket?

    val id: TimeTicket
        get() = createdAt

    var movedAt: TimeTicket?
        get() = _movedAt
        set(value) {
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

    /**
     * `getMetaUsage` returns the meta usage of this element.
     */
    fun getMetaUsage(): Int {
        var meta = TIME_TICKET_SIZE

        if (_movedAt != null) {
            meta += TIME_TICKET_SIZE
        }

        if (_removedAt != null) {
            meta += TIME_TICKET_SIZE
        }

        return meta
    }

    abstract fun deepCopy(): CrdtElement
    abstract fun getDataSize(): DataSize
}
