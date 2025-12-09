package dev.yorkie.document.schema

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree

data class ValidationResult(
    val valid: Boolean,
    val errors: List<ValidationError>,
)

data class ValidationError(
    val path: String,
    val message: String,
)

/**
 * `validateYorkieRuleset` validates the given data against the ruleset.
 */
fun validateYorkieRuleset(data: Any?, ruleset: List<Rule>): ValidationResult {
    val errors = ArrayList<ValidationError>()
    for (rule in ruleset) {
        val value = getValueByPath(data, rule.path)
        val result = validateValue(value, rule)
        if (!result.valid) {
            for (error in result.errors) {
                errors.add(error)
            }
        }
    }

    return ValidationResult(
        valid = errors.isEmpty(),
        errors = errors,
    )
}

/**
 * `getValueByPath` gets a value from the given object using the given path.
 */
internal fun getValueByPath(obj: Any?, path: String): Any? {
    if (!path.startsWith("$")) {
        throw Exception("Path must start with \$, got $path")
    }

    var current = obj
    val keys = path.split(".")
    for (i in 1 until keys.size) {
        val key = keys[i]
        if (current !is CrdtObject) {
            return null
        }
        current = current[key]
    }
    return current
}

/**
 * `getPrimitiveType` converts a string type to PrimitiveType.
 */
internal fun getPrimitiveType(type: String): CrdtPrimitive.Type {
    return when (type) {
        "null" -> {
            CrdtPrimitive.Type.Null
        }
        "boolean" -> {
            CrdtPrimitive.Type.Boolean
        }
        "integer" -> {
            CrdtPrimitive.Type.Integer
        }
        "long" -> {
            CrdtPrimitive.Type.Long
        }
        "double" -> {
            CrdtPrimitive.Type.Double
        }
        "string" -> {
            CrdtPrimitive.Type.String
        }
        "bytes" -> {
            CrdtPrimitive.Type.Bytes
        }
        "date" -> {
            CrdtPrimitive.Type.Date
        }
        else -> {
            throw Exception("Unknown primitive type: $type")
        }
    }
}

/**
 * `validateValue` validates a value against a rule.
 */
internal fun validateValue(value: Any?, rule: Rule): ValidationResult {
    when (rule.type) {
        in Rule.PrimitiveRule.Type.entries.map { it.value } -> {
            if (value !is CrdtPrimitive || value.type != getPrimitiveType(rule.type)) {
                return ValidationResult(
                    valid = false,
                    errors = listOf(
                        ValidationError(
                            path = rule.path,
                            message = "expected ${rule.type} at path ${rule.path}",
                        ),
                    ),
                )
            }
        }
        Rule.ObjectRule.TYPE -> {
            if (value !is CrdtObject) {
                return ValidationResult(
                    valid = false,
                    errors = listOf(
                        ValidationError(
                            path = rule.path,
                            message = "expected object at path ${rule.path}",
                        ),
                    ),
                )
            }
        }
        Rule.ArrayRule.TYPE -> {
            if (value !is CrdtArray) {
                return ValidationResult(
                    valid = false,
                    errors = listOf(
                        ValidationError(
                            path = rule.path,
                            message = "expected array at path ${rule.path}",
                        ),
                    ),
                )
            }
        }
        Rule.YorkieTypeRule.Type.TEXT.value -> {
            if (value !is CrdtText) {
                return ValidationResult(
                    valid = false,
                    errors = listOf(
                        ValidationError(
                            path = rule.path,
                            message = "expected yorkie.Text at path ${rule.path}",
                        ),
                    ),
                )
            }
        }
        Rule.YorkieTypeRule.Type.TREE.value -> {
            if (value !is CrdtTree) {
                return ValidationResult(
                    valid = false,
                    errors = listOf(
                        ValidationError(
                            path = rule.path,
                            message = "expected yorkie.Tree at path ${rule.path}",
                        ),
                    ),
                )
            }
        }
        Rule.YorkieTypeRule.Type.COUNTER.value -> {
            if (value !is CrdtCounter) {
                return ValidationResult(
                    valid = false,
                    errors = listOf(
                        ValidationError(
                            path = rule.path,
                            message = "expected yorkie.Counter at path ${rule.path}",
                        ),
                    ),
                )
            }
        }
        else -> {
            throw Exception("Unknown rule type: ${rule.type}")
        }
    }

    return ValidationResult(
        valid = true,
        errors = emptyList(),
    )
}
