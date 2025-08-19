package dev.yorkie.schema

sealed interface Rule {
    val path: String
    val type: String

    data class PrimitiveRule(
        override val path: String,
        override val type: String,
    ) : Rule

    data class ObjectRule(
        override val path: String,
        override val type: String,
        val properties: List<String>,
        val optional: List<String>,
    ) : Rule

    data class ArrayRule(
        override val path: String,
        override val type: String,
    ) : Rule

    data class YorkieTypeRule(
        override val path: String,
        override val type: String,
    ) : Rule
}
