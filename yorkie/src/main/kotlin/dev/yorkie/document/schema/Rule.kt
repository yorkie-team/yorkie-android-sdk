package dev.yorkie.document.schema

sealed interface Rule {
    val path: String
    val type: String

    data class PrimitiveRule(
        override val path: String,
        override val type: String,
    ) : Rule {
        enum class Type(val value: String) {
            BOOLEAN("boolean"),
            INTEGER("integer"),
            DOUBLE("double"),
            LONG("long"),
            STRING("string"),
            DATE("date"),
            BYTES("bytes"),
            NULL("null"),
        }
    }

    data class ObjectRule(
        override val path: String,
        override val type: String = TYPE,
        val properties: List<String>,
        val optional: List<String>,
    ) : Rule {
        companion object {
            const val TYPE: String = "object"
        }
    }

    data class ArrayRule(
        override val path: String,
        override val type: String = TYPE,
        val items: List<Item>,
    ) : Rule {
        data class Item(
            val type: String,
            val properties: List<String>,
        )

        companion object {
            const val TYPE = "array"
        }
    }

    data class YorkieTypeRule(
        override val path: String,
        override val type: String,
        val treeNodes: List<TreeNodeRule>? = null,
    ) : Rule {
        enum class Type(val value: String) {
            TEXT("yorkie.Text"),
            TREE("yorkie.Tree"),
            COUNTER("yorkie.Counter"),
            OBJECT("yorkie.Object"),
            ARRAY("yorkie.Array"),
        }
    }

    /**
     * Represents a single tree node rule used to validate the structure of
     * a [dev.yorkie.document.crdt.CrdtTree] against the schema. Mirrors the
     * `TreeNodeRule` type from the yorkie-js-sdk schema package.
     */
    data class TreeNodeRule(
        val nodeType: String,
        val content: String? = null,
        val marks: String? = null,
        val group: String? = null,
    )

    data class EnumRule(
        override val path: String,
        override val type: String = TYPE,
        // TODO: because can not define condition to choose type so temporarily place Any
        //  need to define type like in JS (string, int, bool)
        val values: List<Any>,
    ) : Rule {
        companion object {
            const val TYPE = "enum"
        }
    }

    data class UnionRule(
        override val path: String,
        override val type: String = TYPE,
    ) : Rule {
        companion object {
            const val TYPE = "union"
        }
    }
}
