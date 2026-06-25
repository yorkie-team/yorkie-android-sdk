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

import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.toDocKey
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeValidatorTest {

    @Test
    fun `group resolver maps group name to node types`() {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "block",
            ),
            Rule.TreeNodeRule(
                nodeType = "heading",
                content = "text*",
                marks = "",
                group = "block",
            ),
            Rule.TreeNodeRule(
                nodeType = "blockquote",
                content = "block+",
                marks = "",
                group = "block",
            ),
        )

        // when
        val resolver = buildGroupResolver(rules)

        // then
        assertEquals(listOf("paragraph", "heading", "blockquote"), resolver("block"))
    }

    @Test
    fun `group resolver returns the name itself when not a group`() {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "block",
            ),
        )

        // when
        val resolver = buildGroupResolver(rules)

        // then
        assertEquals(listOf("paragraph"), resolver("paragraph"))
        assertEquals(listOf("unknown"), resolver("unknown"))
    }

    @Test
    fun `group resolver supports multiple groups per node`() {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "block flow",
            ),
        )

        // when
        val resolver = buildGroupResolver(rules)

        // then
        assertEquals(listOf("paragraph"), resolver("block"))
        assertEquals(listOf("paragraph"), resolver("flow"))
    }

    @Test
    fun `validates a valid tree (doc gt paragraph gt text)`() = runTest {
        // given
        val rules = docParagraphRules()
        val tree = treeFor(
            element("doc") {
                element("paragraph") {
                    text { "hello" }
                }
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertTrue(result.valid)
        assertNull(result.error)
    }

    @Test
    fun `rejects unknown node type`() = runTest {
        // given
        val rules = docParagraphRules()
        val tree = treeFor(
            element("doc") {
                element("div") {
                    text { "hello" }
                }
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertFalse(result.valid)
        assertNotNull(result.error)
        val error = result.error.orEmpty()
        assertTrue(error.contains("Unknown node type"))
        assertTrue(error.contains("div"))
    }

    @Test
    fun `rejects content expression violation when required child missing`() = runTest {
        // given
        val rules = docParagraphRules()
        val tree = treeFor(element("doc"))

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertFalse(result.valid)
        assertTrue(result.error.orEmpty().contains("doc"))
    }

    @Test
    fun `validates with group resolver`() = runTest {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "doc",
                content = "block+",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "block",
            ),
            Rule.TreeNodeRule(
                nodeType = "heading",
                content = "text*",
                marks = "",
                group = "block",
            ),
        )
        val tree = treeFor(
            element("doc") {
                element("paragraph") { text { "hello" } }
                element("heading") { text { "world" } }
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `rejects when wrong child type for content expression`() = runTest {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "doc",
                content = "paragraph+",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "heading",
                content = "text*",
                marks = "",
                group = "",
            ),
        )
        val tree = treeFor(
            element("doc") {
                element("heading") { text { "hello" } }
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertFalse(result.valid)
        assertTrue(result.error.orEmpty().contains("doc"))
    }

    @Test
    fun `validates deeply nested trees`() = runTest {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "doc",
                content = "section+",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "section",
                content = "paragraph+",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "",
            ),
        )
        val tree = treeFor(
            element("doc") {
                element("section") {
                    element("paragraph") { text { "hello" } }
                }
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `detects errors in nested children`() = runTest {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "doc",
                content = "section+",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "section",
                content = "paragraph+",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "",
            ),
        )
        val tree = treeFor(
            element("doc") {
                element("section")
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertFalse(result.valid)
        assertTrue(result.error.orEmpty().contains("section"))
    }

    @Test
    fun `handles alternative content expressions`() = runTest {
        // given
        val rules = listOf(
            Rule.TreeNodeRule(
                nodeType = "doc",
                content = "(paragraph | heading)+",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "paragraph",
                content = "text*",
                marks = "",
                group = "",
            ),
            Rule.TreeNodeRule(
                nodeType = "heading",
                content = "text*",
                marks = "",
                group = "",
            ),
        )
        val tree = treeFor(
            element("doc") {
                element("paragraph") { text { "hello" } }
                element("heading") { text { "title" } }
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertTrue(result.valid)
    }

    @Test
    fun `skips mark validation when marks rule is empty`() = runTest {
        // given
        val rules = docParagraphRules()
        val tree = treeFor(
            element("doc") {
                element("paragraph") { text { "hello" } }
            },
        )

        // when
        val result = validateTreeAgainstSchema(tree, rules)

        // then
        assertTrue(result.valid)
    }

    private fun docParagraphRules() = listOf(
        Rule.TreeNodeRule(
            nodeType = "doc",
            content = "paragraph+",
            marks = "",
            group = "",
        ),
        Rule.TreeNodeRule(
            nodeType = "paragraph",
            content = "text*",
            marks = "",
            group = "",
        ),
    )

    /**
     * Creates an in-memory [CrdtTree] by setting [rootElement] on a fresh
     * [Document] and returning the underlying CRDT element.
     */
    private suspend fun treeFor(rootElement: JsonTree.ElementNode): CrdtTree {
        val document = Document(UUID.randomUUID().toString().toDocKey())
        document.updateAsync { root, _ ->
            root.setNewTree("t", rootElement)
        }.await()
        val crdtObject = document.getRootObject()
        return crdtObject["t"] as CrdtTree
    }
}
