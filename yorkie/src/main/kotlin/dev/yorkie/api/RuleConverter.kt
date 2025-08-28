package dev.yorkie.api

import dev.yorkie.schema.Rule

internal typealias PBRule = dev.yorkie.api.v1.Rule

fun List<PBRule>.fromSchemaRules(): List<Rule> {
    return map {
        when (it.type) {
            in Rule.PrimitiveRule.Type.entries.map { it.value } -> {
                Rule.PrimitiveRule(
                    path = it.path,
                    type = it.type,
                )
            }

            Rule.ObjectRule.TYPE -> {
                Rule.ObjectRule(
                    path = it.path,
                    properties = emptyList(),
                    optional = emptyList(),
                )
            }

            Rule.ArrayRule.TYPE -> {
                Rule.ArrayRule(
                    path = it.path,
                    items = emptyList(),
                )
            }

            in Rule.YorkieTypeRule.Type.entries.map { it.value } -> {
                Rule.YorkieTypeRule(
                    path = it.path,
                    type = it.type,
                )
            }

            Rule.EnumRule.TYPE -> {
                Rule.EnumRule(
                    path = it.path,
                    values = emptyList(),
                )
            }

            Rule.UnionRule.TYPE -> {
                Rule.UnionRule(
                    path = it.path,
                )
            }

            else -> {
                throw Exception("Unknown rule type: ${it.type}")
            }
        }
    }
}
