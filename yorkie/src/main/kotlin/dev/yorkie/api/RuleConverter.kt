package dev.yorkie.api

import dev.yorkie.schema.Rule

internal typealias PBRule = dev.yorkie.api.v1.Rule

fun List<PBRule>.fromSchemaRules(): List<Rule> {
    return map {
        when (it.type) {
            "string", "boolean", "integer", "double", "long", "date", "bytes", "null" -> {
                Rule.PrimitiveRule(
                    path = it.path,
                    type = it.type,
                )
            }

            "object" -> {
                Rule.ObjectRule(
                    path = it.path,
                    type = it.type,
                    properties = emptyList(),
                    optional = emptyList(),
                )
            }

            "array" -> {
                Rule.ArrayRule(
                    path = it.path,
                    type = it.type,
                )
            }

            "yorkie.Text", "yorkie.Tree", "yorkie.Counter", "yorkie.Object", "yorkie.Array" -> {
                Rule.YorkieTypeRule(
                    path = it.path,
                    type = it.type,
                )
            }

            else -> {
                throw Exception("Unknown rule type: ${it.type}")
            }
        }
    }
}
