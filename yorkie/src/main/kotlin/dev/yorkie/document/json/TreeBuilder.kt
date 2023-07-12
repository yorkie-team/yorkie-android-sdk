package dev.yorkie.document.json

import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.json.JsonTree.TextNode
import dev.yorkie.document.json.JsonTree.TreeNode

/**
 * Helper builder for [ElementNode]s.
 */
public class ElementNodeBuilder internal constructor(val type: String) {
    private val lazyAttributes = lazy { mutableMapOf<String, String>() }
    val attributes by lazyAttributes

    private val lazyChildren = lazy { mutableListOf<TreeNode>() }
    val children by lazyChildren

    /**
     * adds an attribute to the node.
     */
    public fun attr(init: () -> Pair<String, Any>) {
        with(init.invoke()) {
            attributes += first to second.toString()
        }
    }

    /**
     * adds attributes to the node.
     */
    public fun attrs(init: () -> Map<String, Any>) {
        with(init.invoke()) {
            attributes += mapValues { it.value.toString() }
        }
    }

    /**
     * adds an [ElementNode] as a child.
     */
    public fun element(type: String, init: (ElementNodeBuilder.() -> Unit)? = null) {
        children += elementInternal(type, init)
    }

    /**
     * adds a [TextNode] as a child.
     */
    public fun text(init: () -> String) {
        children += textInternal(init)
    }

    internal fun build(): ElementNode {
        return ElementNode(
            type,
            if (lazyAttributes.isInitialized()) attributes else emptyMap(),
            if (lazyChildren.isInitialized()) children else emptyList(),
        )
    }
}

/**
 * Creates an [ElementNode].
 */
public fun element(type: String, init: (ElementNodeBuilder.() -> Unit)? = null): ElementNode {
    return elementInternal(type, init)
}

private fun elementInternal(
    type: String,
    init: (ElementNodeBuilder.() -> Unit)?,
): ElementNode {
    val builder = ElementNodeBuilder(type)
    init?.let {
        builder.it()
    }
    return builder.build()
}

/**
 * Creates a [TextNode].
 */
public fun text(init: () -> String): TextNode = textInternal(init)

private fun textInternal(init: () -> String) = TextNode(init.invoke())
