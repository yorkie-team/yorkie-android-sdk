/*
 * Copyright 2026 The Yorkie Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.yorkie.document.schema

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode

/**
 * Result of validating a [CrdtTree] against a list of [Rule.TreeNodeRule].
 */
data class TreeValidationResult(
    val valid: Boolean,
    val error: String? = null,
)

private val WhitespaceRegex = Regex("\\s+")

/**
 * Builds a group resolver function from the given tree node rules.
 * The resolver maps a name to the list of node types that belong to that
 * group. If the name is not a group, returns the name itself, treating it
 * as a concrete node type.
 */
internal fun buildGroupResolver(treeNodes: List<Rule.TreeNodeRule>): (String) -> List<String> {
    val groupMap = HashMap<String, MutableList<String>>()
    for (node in treeNodes) {
        val group = node.group ?: continue
        if (group.isEmpty()) continue
        for (g in group.split(WhitespaceRegex).filter { it.isNotEmpty() }) {
            groupMap.getOrPut(g) { ArrayList() }.add(node.nodeType)
        }
    }
    return { name ->
        groupMap[name] ?: listOf(name)
    }
}

/**
 * Validates a [CrdtTree]'s structure against the given tree node rules.
 *
 * The check covers:
 * 1. Each node's type exists in the rules.
 * 2. Children match the content expression for non-text nodes.
 * 3. Text node marks are allowed by the parent's marks rule.
 */
internal fun validateTreeAgainstSchema(
    tree: CrdtTree,
    treeNodes: List<Rule.TreeNodeRule>,
): TreeValidationResult {
    val ruleMap = HashMap<String, Rule.TreeNodeRule>()
    for (node in treeNodes) {
        ruleMap[node.nodeType] = node
    }

    val resolver = buildGroupResolver(treeNodes)
    return validateNode(tree.root, ruleMap, resolver)
}

/**
 * Recursively validates a [CrdtTreeNode] against the rules.
 */
private fun validateNode(
    node: CrdtTreeNode,
    ruleMap: Map<String, Rule.TreeNodeRule>,
    resolver: (String) -> List<String>,
): TreeValidationResult {
    val rule = ruleMap[node.type] ?: return TreeValidationResult(
        valid = false,
        error = "Unknown node type: \"${node.type}\"",
    )

    // Skip further validation for text nodes — they are leaf nodes and are
    // validated by their parent's marks rule.
    if (node.isText) {
        return TreeValidationResult(valid = true)
    }

    val children = node.children

    for (child in children) {
        if (!child.isText && !ruleMap.containsKey(child.type)) {
            return TreeValidationResult(
                valid = false,
                error = "Unknown node type: \"${child.type}\"",
            )
        }
    }

    if (rule.content != null) {
        val childTypes = children.map { it.type }
        val expr = parseContentExpression(rule.content)
        val result = matchContentExpression(expr, childTypes, resolver)
        if (!result.valid) {
            return TreeValidationResult(
                valid = false,
                error = "Node \"${node.type}\": ${result.error}",
            )
        }
    }

    if (!rule.marks.isNullOrEmpty()) {
        val allowedMarks = rule.marks.split(WhitespaceRegex).filter { it.isNotEmpty() }
        val markResult = validateChildMarks(node, children, allowedMarks)
        if (!markResult.valid) {
            return markResult
        }
    }

    for (child in children) {
        if (!child.isText) {
            val result = validateNode(child, ruleMap, resolver)
            if (!result.valid) {
                return result
            }
        }
    }

    return TreeValidationResult(valid = true)
}

/**
 * Checks that text children of [parent] only have marks that are listed in
 * the allowed marks.
 */
private fun validateChildMarks(
    parent: CrdtTreeNode,
    children: List<CrdtTreeNode>,
    allowedMarks: List<String>,
): TreeValidationResult {
    for (child in children) {
        if (!child.isText) continue
        for (rhtNode in child.rhtNodes) {
            if (rhtNode.isRemoved) continue
            val markName = rhtNode.key
            if (markName !in allowedMarks) {
                return TreeValidationResult(
                    valid = false,
                    error = "Node \"${parent.type}\": text child has disallowed mark " +
                        "\"$markName\". Allowed marks: ${allowedMarks.joinToString(", ")}",
                )
            }
        }
    }
    return TreeValidationResult(valid = true)
}
