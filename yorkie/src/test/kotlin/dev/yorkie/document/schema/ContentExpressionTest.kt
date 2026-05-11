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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentExpressionTest {

    private val identityResolver: (String) -> List<String> = { listOf(it) }

    @Test
    fun `parses a single node type`() {
        // given
        val expr = parseContentExpression("paragraph")

        // then
        assertEquals(ContentExpr.Node("paragraph"), expr)
    }

    @Test
    fun `parses one-or-more quantifier`() {
        // given
        val expr = parseContentExpression("paragraph+")

        // then
        assertEquals(
            ContentExpr.Repeat(
                child = ContentExpr.Node("paragraph"),
                min = 1,
                max = Int.MAX_VALUE,
            ),
            expr,
        )
    }

    @Test
    fun `parses zero-or-more quantifier`() {
        // given
        val expr = parseContentExpression("text*")

        // then
        assertEquals(
            ContentExpr.Repeat(
                child = ContentExpr.Node("text"),
                min = 0,
                max = Int.MAX_VALUE,
            ),
            expr,
        )
    }

    @Test
    fun `parses alternatives`() {
        // given
        val expr = parseContentExpression("paragraph | heading")

        // then
        assertTrue(expr is ContentExpr.Alternative)
        assertEquals(2, (expr as ContentExpr.Alternative).children.size)
    }

    @Test
    fun `parses parenthesized alternatives with quantifier`() {
        // given
        val expr = parseContentExpression("(paragraph | heading)+")

        // then
        assertTrue(expr is ContentExpr.Repeat)
        assertEquals(1, (expr as ContentExpr.Repeat).min)
    }

    @Test
    fun `matches single required node`() {
        // given
        val expr = parseContentExpression("paragraph")

        // when
        val result = matchContentExpression(expr, listOf("paragraph"), identityResolver)

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `rejects when required node is missing`() {
        // given
        val expr = parseContentExpression("paragraph+")

        // when
        val result = matchContentExpression(expr, emptyList(), identityResolver)

        // then
        assertFalse(result.valid)
        assertNotNull(result.error)
    }

    @Test
    fun `matches one or more paragraphs`() {
        // given
        val expr = parseContentExpression("paragraph+")

        // when
        val result = matchContentExpression(
            expr,
            listOf("paragraph", "paragraph"),
            identityResolver,
        )

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `matches empty children with zero-or-more`() {
        // given
        val expr = parseContentExpression("text*")

        // when
        val result = matchContentExpression(expr, emptyList(), identityResolver)

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `matches alternatives`() {
        // given
        val expr = parseContentExpression("(paragraph | heading)+")

        // when
        val result = matchContentExpression(
            expr,
            listOf("paragraph", "heading", "paragraph"),
            identityResolver,
        )

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `resolves group names via resolver`() {
        // given
        val expr = parseContentExpression("block+")
        val resolver: (String) -> List<String> = { name ->
            when (name) {
                "block" -> listOf("paragraph", "heading")
                else -> listOf(name)
            }
        }

        // when
        val result = matchContentExpression(
            expr,
            listOf("paragraph", "heading"),
            resolver,
        )

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `rejects unexpected child after greedy match`() {
        // given
        val expr = parseContentExpression("paragraph+")

        // when
        val result = matchContentExpression(
            expr,
            listOf("paragraph", "heading"),
            identityResolver,
        )

        // then
        assertFalse(result.valid)
    }

    @Test
    fun `empty expression matches no children`() {
        // given
        val expr = parseContentExpression("")

        // when
        val result = matchContentExpression(expr, emptyList(), identityResolver)

        // then
        assertTrue(result.valid)
    }
}
