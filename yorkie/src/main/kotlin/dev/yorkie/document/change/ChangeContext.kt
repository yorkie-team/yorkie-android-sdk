package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.GCPair
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize

/**
 * Used to record the context of modification when editing a document.
 * Each time we add an operation, a new time ticket is issued.
 * Finally returns a [Change] after the modification has been completed.
 */
internal class ChangeContext(
    val prevId: ChangeID,
    val root: CrdtRoot,
    val message: String? = null,
) {
    var presenceChange: PresenceChange? = null
    private val operations: MutableList<Operation> = mutableListOf()
    private var delimiter: UInt = TimeTicket.INITIAL_DELIMITER

    /**
     * returns the next ID of this context. It will be set to the
     * document for the next change.returns the next ID of this context.
     */
    private val nextId: ChangeID = prevId.next()

    /**
     * Returns whether this context has change or not.
     */
    val hasChange: Boolean
        get() = operations.isNotEmpty() || presenceChange != null

    /**
     *  Returns the last time ticket issued in this context.
     */
    val lastTimeTicket: TimeTicket
        get() = nextId.createTimeTicket(delimiter)

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
     * `toChange` creates a new instance of Change in this context.
     */
    fun toChange(): Change {
        val id = if (isPresenceOnlyChange()) {
            prevId.next(true)
        } else {
            nextId
        }
        return Change(
            id = id,
            operations = operations,
            presenceChange = presenceChange,
            message = message,
        )
    }

    /**
     * Creates a [TimeTicket] to be used to create a new [Operation].
     */
    fun issueTimeTicket(): TimeTicket {
        delimiter++
        return nextId.createTimeTicket(delimiter)
    }

    /**
     * `getNextID` returns the next ID of this context. It will be set to the
     * document for the next change.returns the next ID of this context.
     */
    fun getNextId(): ChangeID {
        return if (isPresenceOnlyChange()) {
            prevId
                .next(true)
                .setLamport(prevId.lamport)
                .setVersionVector(prevId.versionVector)
        } else {
            nextId
        }
    }

    /**
     * `isPresenceOnlyChange` returns whether this context is only for presence
     * change or not.
     */
    fun isPresenceOnlyChange(): Boolean {
        return this.operations.isEmpty()
    }

    /**
     * `acc` accumulates the given DataSize to Live size of the root.
     */
    fun acc(diff: DataSize) {
        root.acc(diff)
    }
}
