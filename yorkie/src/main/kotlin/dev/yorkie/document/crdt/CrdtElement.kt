package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtElement] represents element type containing logical clock.
 */
internal abstract class CrdtElement(
    val createdAt: TimeTicket,
    movedAt: TimeTicket? = null,
    removedAt: TimeTicket? = null,
) {
    val id: TimeTicket
        get() = createdAt

    val isRemoved: Boolean
        get() = removedAt != null

    var movedAt: TimeTicket? = movedAt
        set(value) {
            if (field?.let { value != null && value > it } != false) {
                field = value
            }
        }

    var removedAt: TimeTicket? = removedAt
        set(value) {
            if (field.let { value != null && value > createdAt && (it == null || value > it) }) {
                field = value
            }
        }
}
