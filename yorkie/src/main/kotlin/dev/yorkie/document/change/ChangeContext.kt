package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.GCPair
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.TimeTicket

/**
 * Used to record the context of modification when editing a document.
 * Each time we add an operation, a new time ticket is issued.
 * Finally returns a [Change] after the modification has been completed.
 */
internal data class ChangeContext(
    val id: ChangeID,
    val root: CrdtRoot,
    val message: String? = null,
    var presenceChange: PresenceChange? = null,
    private val operations: MutableList<Operation> = mutableListOf(),
    private var delimiter: UInt = TimeTicket.INITIAL_DELIMITER,
) {
    /**
     * Returns whether this context has change or not.
     */
    val hasChange: Boolean
        get() = operations.isNotEmpty() || presenceChange != null

    /**
     *  Returns the last time ticket issued in this context.
     */
    val lastTimeTicket: TimeTicket
        get() = id.createTimeTicket(delimiter)

    /**
     * Pushes the given operation to this context.
     */
    fun push(operation: Operation) {
        operations.add(operation)
    }

    /**
     * Registers the given element to the root.
     */
    fun registerElement(element: CrdtElement, parent: CrdtContainer) {
        root.registerElement(element, parent)
    }

    /**
     * Registers removed element for garbage collection.
     */
    fun registerRemovedElement(element: CrdtElement) {
        root.registerRemovedElement(element)
    }

    /**
     *  Registers the given pair to hash table.
     */
    fun registerGCPair(pair: GCPair<*>) {
        root.registerGCPair(pair)
    }

    /**
     * Creates a new instance of [Change] in this context.
     */
    fun getChange(): Change {
        return Change(id, operations, presenceChange, message)
    }

    /**
     * Creates a [TimeTicket] to be used to create a new [Operation].
     */
    fun issueTimeTicket(): TimeTicket {
        delimiter++
        return id.createTimeTicket(delimiter)
    }
}
