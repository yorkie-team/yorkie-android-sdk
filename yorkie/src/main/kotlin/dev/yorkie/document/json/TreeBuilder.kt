package dev.yorkie.document.json

import dev.yorkie.document.json.JsonTree.ElementNode
import dev.yorkie.document.json.JsonTree.TextNode
import dev.yorkie.document.json.JsonTree.TreeNode
import kotlin.DeprecationLevel.ERROR

public object TreeBuilder {

    public interface Scope {

        public fun attr(init: ClosingScope.() -> Pair<String, Any>)

        public fun attrs(init: ClosingScope.() -> Map<String, Any>)

        public fun element(type: String, init: (ElementNodeBuilder.() -> Unit)? = null)

        public fun text(init: ClosingScope.() -> String)
    }

    @Suppress("DeprecatedCallableAddReplaceWith")
    public class ClosingScope private constructor() : Scope {

        @Deprecated(message = ERROR_MESSAGE, level = ERROR)
        public override fun attr(init: ClosingScope.() -> Pair<String, Any>) = Unit

        @Deprecated(message = ERROR_MESSAGE, level = ERROR)
        public override fun attrs(init: ClosingScope.() -> Map<String, Any>) = Unit

        @Deprecated(message = ERROR_MESSAGE, level = ERROR)
        public override fun element(type: String, init: (ElementNodeBuilder.() -> Unit)?) = Unit

        @Deprecated(message = ERROR_MESSAGE, level = ERROR)
        public override fun text(init: ClosingScope.() -> String) = Unit

        companion object {
            private const val ERROR_MESSAGE =
                "It is not allowed to create nodes or attributes at this scope."

            internal val INSTANCE = ClosingScope()
        }
    }

    /**
     * Helper builder for [ElementNode]s.
     */
    public class ElementNodeBuilder internal constructor(val type: String) : Scope {
        private val lazyAttributes = lazy { mutableMapOf<String, String>() }
        val attributes by lazyAttributes

        private val lazyChildren = lazy { mutableListOf<TreeNode>() }
        val children by lazyChildren

        /**
         * adds an attribute to the node.
         */
        public override fun attr(init: ClosingScope.() -> Pair<String, Any>) {
            with(ClosingScope.INSTANCE.init()) {
                attributes += first to second.toString()
            }
        }

        /**
         * adds attributes to the node.
         */
        public override fun attrs(init: ClosingScope.() -> Map<String, Any>) {
            with(ClosingScope.INSTANCE.init()) {
                attributes += mapValues { it.value.toString() }
            }
        }

        /**
         * adds an [ElementNode] as a child.
         */
        public override fun element(type: String, init: (ElementNodeBuilder.() -> Unit)?) {
            children += elementInternal(type, init)
        }

        /**
         * adds a [TextNode] as a child.
         */
        public override fun text(init: ClosingScope.() -> String) {
            children += textInternal(init)
        }

        internal fun build(): ElementNode {
            return ElementNode(
                type,
                if (lazyChildren.isInitialized()) children else emptyList(),
                if (lazyAttributes.isInitialized()) attributes else emptyMap(),
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
    public fun text(init: ClosingScope.() -> String): TextNode = textInternal(init)

    private fun textInternal(init: ClosingScope.() -> String): TextNode {
        return TextNode(ClosingScope.INSTANCE.init())
    }
}
