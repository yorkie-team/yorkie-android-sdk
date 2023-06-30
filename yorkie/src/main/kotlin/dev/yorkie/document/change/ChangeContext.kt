package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtContainer
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtGCElement
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.time.TimeTicket

/**
 * Used to record the context of modification when editing a document.
 * Each time we add an operation, a new time ticket is issued.
 * Finally returns a [Change] after the modification has been completed.
 */
internal data class ChangeContext(
    val id: ChangeID,
    val root: CrdtRoot,
    val message: String?,
    private val _operations: MutableList<Operation> = mutableListOf(),
    var delimiter: Int = TimeTicket.INITIAL_DELIMITER,
) {
    val operations: List<Operation>
        get() = _operations.toList()

    /**
     * Returns whether this context has operations or not.
     */
    val hasOperations: Boolean
        get() = operations.isNotEmpty()

    /**
     *  Returns the last time ticket issued in this context.
     */
    val lastTimeTicket: TimeTicket
        get() = id.createTimeTicket(delimiter)

    /**
     * Pushes the given operation to this context.
     */
    fun push(operation: Operation) {
        _operations.add(operation)
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
     * Registers text element that has removed nodes for garbage collection.
     */
    fun registerRemovedNodeTextElement(text: CrdtText) {
        root.registerTextWithGarbage(text)
    }

    /**
     * Registers GC element has removed node for garbage collection.
     */
    fun registerElementHasRemovedNodes(elem: CrdtGCElement) {
        root.registerElementHasRemovedNodes(elem)
    }

    /**
     * Creates a new instance of [Change] in this context.
     */
    fun getChange(): Change {
        return Change(id, operations, message)
    }

    /**
     * Creates a [TimeTicket] to be used to create a new [Operation].
     */
    fun issueTimeTicket(): TimeTicket {
        delimiter++
        return id.createTimeTicket(delimiter)
    }
}
