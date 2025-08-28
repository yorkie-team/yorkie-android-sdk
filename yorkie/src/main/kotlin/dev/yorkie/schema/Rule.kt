package dev.yorkie.schema

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
    ) : Rule {
        enum class Type(val value: String) {
            TEXT("yorkie.Text"),
            TREE("yorkie.Tree"),
            COUNTER("yorkie.Counter"),
            OBJECT("yorkie.Object"),
            ARRAY("yorkie.Array"),
        }
    }

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
