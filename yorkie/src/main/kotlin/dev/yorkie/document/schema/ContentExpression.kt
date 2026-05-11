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

/**
 * Represents a parsed ProseMirror-compatible content expression.
 *
 * Content expressions define what children a node can contain using the grammar:
 * ```
 *   expr       -> sequence ('|' sequence)*     // alternatives
 *   sequence   -> element+                     // sequence
 *   element    -> atom quantifier?             // element + quantifier
 *   atom       -> name | '(' expr ')'          // node type or group
 *   quantifier -> '+' | '*' | '?'              // 1+, 0+, 0-1
 * ```
 */
internal sealed class ContentExpr {
    data class Node(val nodeType: String) : ContentExpr()

    data class Sequence(val children: List<ContentExpr>) : ContentExpr()

    data class Alternative(val children: List<ContentExpr>) : ContentExpr()

    data class Repeat(
        val child: ContentExpr,
        val min: Int,
        val max: Int,
    ) : ContentExpr()
}

private enum class TokenType {
    Name,
    Plus,
    Star,
    Question,
    Pipe,
    LParen,
    RParen,
}

private data class Token(val type: TokenType, val value: String)

/**
 * Splits a content expression string into tokens.
 */
private fun tokenize(expr: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    while (i < expr.length) {
        val ch = expr[i]
        if (ch.isWhitespace()) {
            i++
            continue
        }
        when (ch) {
            '+' -> {
                tokens.add(Token(TokenType.Plus, "+"))
                i++
            }
            '*' -> {
                tokens.add(Token(TokenType.Star, "*"))
                i++
            }
            '?' -> {
                tokens.add(Token(TokenType.Question, "?"))
                i++
            }
            '|' -> {
                tokens.add(Token(TokenType.Pipe, "|"))
                i++
            }
            '(' -> {
                tokens.add(Token(TokenType.LParen, "("))
                i++
            }
            ')' -> {
                tokens.add(Token(TokenType.RParen, ")"))
                i++
            }
            else -> {
                val sb = StringBuilder()
                while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_')) {
                    sb.append(expr[i])
                    i++
                }
                if (sb.isEmpty()) {
                    throw IllegalArgumentException(
                        "Unexpected character '$ch' at position $i in content expression",
                    )
                }
                tokens.add(Token(TokenType.Name, sb.toString()))
            }
        }
    }
    return tokens
}

private data class ParseResult(val expr: ContentExpr, val pos: Int)

/**
 * Parses alternatives separated by `'|'`.
 */
private fun parseAlternatives(tokens: List<Token>, start: Int): ParseResult {
    val seqs = ArrayList<ContentExpr>()
    var result = parseSequence(tokens, start)
    seqs.add(result.expr)
    while (result.pos < tokens.size && tokens[result.pos].type == TokenType.Pipe) {
        result = parseSequence(tokens, result.pos + 1)
        seqs.add(result.expr)
    }
    return if (seqs.size == 1) {
        ParseResult(seqs[0], result.pos)
    } else {
        ParseResult(ContentExpr.Alternative(seqs), result.pos)
    }
}

/**
 * Parses a sequence of elements.
 */
private fun parseSequence(tokens: List<Token>, start: Int): ParseResult {
    val elements = ArrayList<ContentExpr>()
    var pos = start
    while (
        pos < tokens.size &&
        tokens[pos].type != TokenType.Pipe &&
        tokens[pos].type != TokenType.RParen
    ) {
        val result = parseElement(tokens, pos)
        elements.add(result.expr)
        pos = result.pos
    }
    return if (elements.size == 1) {
        ParseResult(elements[0], pos)
    } else {
        ParseResult(ContentExpr.Sequence(elements), pos)
    }
}

/**
 * Parses an atom optionally followed by a quantifier.
 */
private fun parseElement(tokens: List<Token>, start: Int): ParseResult {
    val atom = parseAtom(tokens, start)
    var expr = atom.expr
    var pos = atom.pos
    if (pos < tokens.size) {
        when (tokens[pos].type) {
            TokenType.Plus -> {
                expr = ContentExpr.Repeat(expr, min = 1, max = Int.MAX_VALUE)
                pos++
            }
            TokenType.Star -> {
                expr = ContentExpr.Repeat(expr, min = 0, max = Int.MAX_VALUE)
                pos++
            }
            TokenType.Question -> {
                expr = ContentExpr.Repeat(expr, min = 0, max = 1)
                pos++
            }
            else -> Unit
        }
    }
    return ParseResult(expr, pos)
}

/**
 * Parses a name or a parenthesized sub-expression.
 */
private fun parseAtom(tokens: List<Token>, start: Int): ParseResult {
    if (start >= tokens.size) {
        throw IllegalArgumentException("Unexpected end of content expression")
    }
    val token = tokens[start]
    return when (token.type) {
        TokenType.LParen -> {
            val inner = parseAlternatives(tokens, start + 1)
            if (inner.pos >= tokens.size || tokens[inner.pos].type != TokenType.RParen) {
                throw IllegalArgumentException(
                    "Unmatched parenthesis in content expression",
                )
            }
            ParseResult(inner.expr, inner.pos + 1)
        }
        TokenType.Name -> {
            ParseResult(ContentExpr.Node(token.value), start + 1)
        }
        else -> {
            throw IllegalArgumentException(
                "Expected node type name but got '${token.value}' in content expression",
            )
        }
    }
}

/**
 * Parses a ProseMirror-compatible content expression string into a
 * [ContentExpr] AST.
 *
 * Examples:
 * - `"paragraph+"` matches one or more paragraphs.
 * - `"text*"` matches zero or more text nodes.
 * - `"heading paragraph+"` matches one heading then one or more paragraphs.
 * - `"paragraph | heading"` matches one paragraph or one heading.
 * - `"(paragraph | heading)+"` matches one or more of paragraph or heading.
 * - `"block+"` matches one or more nodes from the `block` group.
 */
internal fun parseContentExpression(expr: String): ContentExpr {
    val trimmed = expr.trim()
    if (trimmed.isEmpty()) {
        return ContentExpr.Sequence(emptyList())
    }
    val tokens = tokenize(trimmed)
    val result = parseAlternatives(tokens, 0)
    if (result.pos < tokens.size) {
        throw IllegalArgumentException(
            "Unexpected token '${tokens[result.pos].value}' at position ${result.pos} " +
                "in content expression",
        )
    }
    return result.expr
}

/**
 * Result of matching a parsed content expression against child node types.
 */
internal data class ContentMatchResult(
    val valid: Boolean,
    val error: String? = null,
)

/**
 * Attempts to match child types starting at any of the given positions
 * against the expression. Returns the set of all reachable positions
 * after matching, enabling backtracking for ambiguous expressions like `a* a`.
 */
private fun matchExpr(
    expr: ContentExpr,
    types: List<String>,
    positions: Set<Int>,
    resolver: (String) -> List<String>,
): Set<Int> {
    return when (expr) {
        is ContentExpr.Node -> {
            val allowed = resolver(expr.nodeType)
            val result = HashSet<Int>()
            for (pos in positions) {
                if (pos < types.size && types[pos] in allowed) {
                    result.add(pos + 1)
                }
            }
            result
        }
        is ContentExpr.Sequence -> {
            var current = positions
            for (child in expr.children) {
                current = matchExpr(child, types, current, resolver)
                if (current.isEmpty()) {
                    return current
                }
            }
            current
        }
        is ContentExpr.Alternative -> {
            val result = HashSet<Int>()
            for (child in expr.children) {
                result.addAll(matchExpr(child, types, positions, resolver))
            }
            result
        }
        is ContentExpr.Repeat -> {
            val min = expr.min
            val max = expr.max
            var current = positions
            val reachable = HashSet<Int>()
            if (min == 0) {
                reachable.addAll(current)
            }
            var count = 1
            while (count <= max) {
                val next = matchExpr(expr.child, types, current, resolver)
                val newPositions = HashSet<Int>()
                for (p in next) {
                    if (!reachable.contains(p) || count < min) {
                        newPositions.add(p)
                    }
                }
                if (newPositions.isEmpty()) break
                current = newPositions
                if (count >= min) {
                    reachable.addAll(current)
                }
                count++
            }
            reachable
        }
    }
}

/**
 * Matches a list of child type names against a parsed content expression.
 * Uses [groupResolver] to resolve group names (like `"block"`) to a list of
 * concrete node type names.
 *
 * Returns a result with `valid = true` if the children match, or
 * `valid = false` with an error message otherwise.
 */
internal fun matchContentExpression(
    expr: ContentExpr,
    childTypes: List<String>,
    groupResolver: (String) -> List<String>,
): ContentMatchResult {
    val positions = matchExpr(expr, childTypes, setOf(0), groupResolver)
    if (positions.contains(childTypes.size)) {
        return ContentMatchResult(valid = true)
    }
    if (positions.isEmpty()) {
        return ContentMatchResult(
            valid = false,
            error = "Children do not match content expression",
        )
    }
    return ContentMatchResult(
        valid = false,
        error = "Unexpected child at position ${positions.max()}",
    )
}
