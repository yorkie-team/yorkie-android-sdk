package dev.yorkie.util

import java.util.function.Predicate
import java.util.function.UnaryOperator

/**
 * This is a specialized [MutableList] for [IndexTree].
 * It is intended to cache active tree nodes list for better performance.
 */
data class IndexTreeNodeList<E : IndexTreeNode<E>>(
    private val delegate: MutableList<E>,
) : MutableList<E> by delegate {
    private var _activeChildren: MutableList<E>? = null

    val activeChildren: List<E>
        get() = _activeChildren ?: delegate.filterNotTo(ArrayList(size)) { it.isRemoved }.also {
            _activeChildren = it
        }

    init {
        delegate.forEach {
            if (!it.isRemoved) {
                it.setRemovedListener()
            }
        }
    }

    override fun add(element: E): Boolean {
        if (!element.isRemoved) {
            _activeChildren?.add(element)
            element.setRemovedListener()
        }
        return delegate.add(element)
    }

    override fun add(index: Int, element: E) {
        if (!element.isRemoved) {
            _activeChildren = null
            element.setRemovedListener()
        }
        return delegate.add(index, element)
    }

    override fun addAll(index: Int, elements: Collection<E>): Boolean {
        if (elements.any { !it.isRemoved }) {
            _activeChildren = null
            elements.forEach {
                it.setRemovedListener()
            }
        }
        return delegate.addAll(index, elements)
    }

    override fun addAll(elements: Collection<E>): Boolean {
        elements.forEach {
            if (!it.isRemoved) {
                _activeChildren?.add(it)
                it.setRemovedListener()
            }
        }
        return delegate.addAll(elements)
    }

    override fun clear() {
        _activeChildren?.clear()
        delegate.clear()
    }

    override fun remove(element: E): Boolean {
        return delegate.remove(element).also {
            if (it && !element.isRemoved) {
                _activeChildren?.remove(element)
            }
        }
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        return delegate.removeAll(elements).also {
            if (it) {
                _activeChildren?.removeAll(elements)
            }
        }
    }

    override fun removeAt(index: Int): E {
        return delegate.removeAt(index).also {
            if (!it.isRemoved) {
                it.onRemovedListener = null
                _activeChildren?.remove(it)
            }
        }
    }

    override fun removeIf(filter: Predicate<in E>): Boolean {
        var removed = false
        val each = iterator()
        while (each.hasNext()) {
            val next = each.next()
            if (filter.test(next)) {
                each.remove()
                removed = true
                if (!next.isRemoved) {
                    next.onRemovedListener = null
                    _activeChildren?.remove(next)
                }
            }
        }
        return removed
    }

    override fun replaceAll(operator: UnaryOperator<E>) {
        val newActiveChildren = mutableListOf<E>()
        val li = delegate.listIterator()
        while (li.hasNext()) {
            val prev = li.next()
            val replacer = operator.apply(prev)
            prev.onRemovedListener = null
            if (!replacer.isRemoved) {
                newActiveChildren.add(replacer)
                replacer.setRemovedListener()
            }
            li.set(replacer)
        }
        _activeChildren = newActiveChildren
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        return delegate.retainAll(elements).also {
            if (it) {
                _activeChildren = null
            }
        }
    }

    override fun set(index: Int, element: E): E {
        if (!element.isRemoved) {
            element.setRemovedListener()
            _activeChildren = null
        }
        return delegate.set(index, element)
    }

    override fun sort(c: Comparator<in E>?) {
        if (c == null) {
            return
        }
        _activeChildren = null
        delegate.sortWith(c)
    }

    private fun E.setRemovedListener() {
        onRemovedListener = IndexTreeNode.OnRemovedListener {
            _activeChildren?.remove(it)
            it.onRemovedListener = null
        }
    }

    /**
     * Re-integrates [element] into the active-children cache after it
     * transitions from removed back to live (identity-preserving restore,
     * e.g. [dev.yorkie.document.crdt.CrdtTreeNode.unremove]). [onRemoved]
     * only ever wires the forward (live -> removed) transition, so a
     * cached, already-computed [activeChildren] list would otherwise keep
     * excluding [element] forever once it is un-tombstoned.
     *
     * Invalidates the cache outright rather than splicing [element] into a
     * potentially stale ordering (the next access recomputes it from
     * [delegate] in true child order), and re-installs the eviction
     * listener so a later re-removal of [element] (e.g. redo) evicts it
     * correctly again.
     */
    internal fun onUnremoved(element: E) {
        _activeChildren = null
        element.setRemovedListener()
    }
}
