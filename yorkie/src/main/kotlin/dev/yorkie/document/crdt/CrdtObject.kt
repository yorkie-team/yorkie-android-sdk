package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

// TODO : Make this class to implement [CrdtContainer]
internal class CrdtObject<T : CrdtElement>(private val createdAt: TimeTicket, private val memberNodes: RhtPQMap<T>) {

    /**
     * `keyOf` returns a key of RHTPQMap based on the given creation time.
     */
    fun subPathOf(createdAt: TimeTicket) {
        memberNodes.subPathOf(createdAt)
    }

    /**
     * `purge` physically purges child element.
     */
    fun purge(value: T) {
        memberNodes.purge(value)
    }

    /**
     * `set` sets the given element of the given key.
     */
    fun set(key: String, value: T): T? {
        return memberNodes.set(key, value)
    }

    /**
     * `delete` deletes the element of the given key.
     */
    fun delete(createdAt: TimeTicket, executedAt: TimeTicket): T {
        return memberNodes.delete(createdAt, executedAt);
    }

    /**
     * `deleteByKey` deletes the element of the given key and execution time.
     */
    fun deleteByKey(key: String, executedAt: TimeTicket): T {
        return memberNodes.deleteByKey(key, executedAt)
    }

    /**
     * `get` returns the value of the given key.
     */
    fun get(key: String): CrdtElement {
        return memberNodes[key]
    }

    /**
     * `has` returns whether the element exists of the given key or not.
     */
    fun has(key: String): Boolean {
        return memberNodes.has(key)
    }
}
