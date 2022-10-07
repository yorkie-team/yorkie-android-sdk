package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtObject] represents object datatype, but unlike regular JSON, it has time
 * tickets which is created by logical clock.
 */
internal class CrdtObject private constructor(
    createdAt: TimeTicket,
    private val memberNodes: RhtPQMap<CrdtElement>,
) : CrdtContainer(createdAt) {
    val Rht
        get() = memberNodes

    /**
     * Returns the sub path of the given element.
     */
    override fun subPathOf(createdAt: TimeTicket): String? {
        return memberNodes.subPathOf(createdAt)
    }

    // NOTE(7hong13): the original comment from js-sdk is as follows:
    // `purge` physically purges child element.
    /**
     * Physically deletes the given [element].
     */
    override fun delete(element: CrdtElement) {
        memberNodes.delete(element)
    }

    /**
     * Sets the given element of the given key.
     */
    fun set(key: String, value: CrdtElement): CrdtElement? {
        return memberNodes.set(key, value)
    }

    /**
     * Removes the element of the given key.
     */
    override fun remove(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        return memberNodes.remove(createdAt, executedAt)
    }

    /**
     * Removes the element of the given key and execution time.
     */
    fun removeByKey(key: String, executedAt: TimeTicket): CrdtElement {
        return memberNodes.removeByKey(key, executedAt)
    }

    /**
     * Returns the value of the given key.
     */
    fun get(key: String): CrdtElement {
        return memberNodes[key]
    }

    /**
     * Returns whether the element exists of the given key or not.
     */
    fun has(key: String): Boolean {
        return memberNodes.has(key)
    }

    /**
     * Returns the JSON encoding of this object.
     */
    override fun toJson(): String {
        TODO("Not yet implemented")
    }

    // NOTE(7hong13): the original comment from js-sdk is as follows:
    // `toJS` return the javascript object of this object.
    /**
     * Returns the JavaScript object of this [CrdtObject].
     */
    fun toJS(): Any {
        TODO("To be implemented when it's actually needed")
    }

    /**
     * Returns array of this object.
     */
    fun getKeys(): List<String> {
        TODO("To be implemented when it's actually needed")
    }

    /**
     * Returns the sorted JSON encoding of this object
     */
    override fun toSortedJson(): String {
        TODO("To be implemented when it's actually needed")
    }

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtElement {
        TODO("To be implemented when it's actually needed")
    }

    /**
     * Returns the descendants of this object by traversing.
     */
    override fun getDescendants(callback: (CrdtElement, CrdtContainer) -> Boolean) {
        memberNodes.forEach {
            val element = it.value
            if (callback(element, this)) return

            if (element is CrdtContainer) {
                element.getDescendants(callback)
            }
        }
    }

    companion object {
        // NOTE(7hong13): the original comment from js-sdk is as follows:
        // `create` creates a new instance of Object.
        /**
         * Creates a new instance of [CrdtObject].
         */
        fun create(createdAt: TimeTicket): CrdtObject {
            return CrdtObject(createdAt, RhtPQMap.create())
        }
    }
}
