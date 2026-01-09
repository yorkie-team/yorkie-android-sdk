package dev.yorkie.document.schema

import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.toDocKey
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesetValidatorTest {
    @Test
    fun `should validate primitive type correctly`() = runTest {
        val ruleset = listOf(
            Rule.ObjectRule(
                path = "\$",
                type = "object",
                properties = listOf(
                    "field1",
                    "field2",
                    "field3",
                    "field4",
                    "field5",
                    "field6",
                    "field7",
                    "field8",
                ),
                optional = emptyList(),
            ),
            Rule.PrimitiveRule(
                path = "\$.field1",
                type = "null",
            ),
            Rule.PrimitiveRule(
                path = "\$.field2",
                type = "boolean",
            ),
            Rule.PrimitiveRule(
                path = "\$.field3",
                type = "integer",
            ),
            Rule.PrimitiveRule(
                path = "\$.field4",
                type = "double",
            ),
            Rule.PrimitiveRule(
                path = "\$.field5",
                type = "long",
            ),
            Rule.PrimitiveRule(
                path = "\$.field6",
                type = "string",
            ),
            Rule.PrimitiveRule(
                path = "\$.field7",
                type = "date",
            ),
            Rule.PrimitiveRule(
                path = "\$.field8",
                type = "bytes",
            ),
        )

        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document = Document(documentKey)
        document.updateAsync { root, _ ->
            root["field1"] = null
            root["field2"] = true
            root["field3"] = 123
            root["field4"] = 123.456
            root["field5"] = Long.MAX_VALUE
            root["field6"] = "test"
            root["field7"] = Date()
            root["field8"] = byteArrayOf(1, 2, 3)
        }.await()
        assertTrue(validateYorkieRuleset(document.getRootObject(), ruleset).valid)

        document.updateAsync { root, _ ->
            root["field1"] = false
            root["field2"] = 123
            root["field3"] = 123.456
            root["field4"] = Long.MAX_VALUE
            root["field5"] = "test"
            root["field6"] = Date()
            root["field7"] = byteArrayOf(1, 2, 3)
            root["field8"] = null
        }.await()
        val result = validateYorkieRuleset(document.getRootObject(), ruleset)
        assertFalse(result.valid)
        assertEquals(
            expected = listOf(
                ValidationError(
                    path = "\$.field1",
                    message = "expected null at path \$.field1",
                ),
                ValidationError(
                    path = "\$.field2",
                    message = "expected boolean at path \$.field2",
                ),
                ValidationError(
                    path = "\$.field3",
                    message = "expected integer at path \$.field3",
                ),
                ValidationError(
                    path = "\$.field4",
                    message = "expected double at path \$.field4",
                ),
                ValidationError(
                    path = "\$.field5",
                    message = "expected long at path \$.field5",
                ),
                ValidationError(
                    path = "\$.field6",
                    message = "expected string at path \$.field6",
                ),
                ValidationError(
                    path = "\$.field7",
                    message = "expected date at path \$.field7",
                ),
                ValidationError(
                    path = "\$.field8",
                    message = "expected bytes at path \$.field8",
                ),
            ),
            actual = result.errors,
        )
    }

    @Test
    fun `should validate object type correctly`() = runTest {
        val ruleset = listOf(
            Rule.ObjectRule(
                path = "\$.user",
                type = "object",
                properties = listOf("name"),
                optional = emptyList(),
            ),
        )

        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document = Document(documentKey)
        document.updateAsync { root, _ ->
            val user = root.setNewObject("user")
            user["name"] = "test"
        }.await()
        assertTrue(validateYorkieRuleset(document.getRootObject(), ruleset).valid)

        document.updateAsync { root, _ ->
            root["user"] = "not an object"
        }.await()
        val result = validateYorkieRuleset(document.getRootObject(), ruleset)
        assertFalse(result.valid)
        assertEquals(
            expected = listOf(
                ValidationError(
                    path = "\$.user",
                    message = "expected object at path \$.user",
                ),
            ),
            actual = result.errors,
        )
    }

    @Test
    fun `should validate array type correctly`() = runTest {
        val ruleset = listOf(
            Rule.ArrayRule(
                path = "\$.items",
                type = "array",
                items = emptyList(),
            ),
        )

        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document = Document(documentKey)
        document.updateAsync { root, _ ->
            val items = root.setNewArray("items")
            items.put(1)
            items.put(2)
            items.put(3)
        }.await()
        assertTrue(validateYorkieRuleset(document.getRootObject(), ruleset).valid)

        document.updateAsync { root, _ ->
            root["items"] = "not an array"
        }.await()
        val result = validateYorkieRuleset(document.getRootObject(), ruleset)
        assertTrue(!result.valid)
        assertEquals(
            expected = listOf(
                ValidationError(
                    path = "\$.items",
                    message = "expected array at path \$.items",
                ),
            ),
            actual = result.errors,
        )
    }

    @Test
    fun `should validate nested paths correctly`() = runTest {
        val ruleset = listOf(
            Rule.ObjectRule(
                path = "\$",
                type = "object",
                properties = listOf("user"),
                optional = emptyList(),
            ),
            Rule.ObjectRule(
                path = "\$.user",
                type = "object",
                properties = listOf("name", "age"),
                optional = emptyList(),
            ),
            Rule.PrimitiveRule(
                path = "\$.user.name",
                type = "string",
            ),
            Rule.PrimitiveRule(
                path = "\$.user.age",
                type = "integer",
            ),
        )

        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document = Document(documentKey)
        document.updateAsync { root, _ ->
            val user = root.setNewObject("user")
            user["name"] = "test"
            user["age"] = 25
        }.await()
        assertTrue(validateYorkieRuleset(document.getRootObject(), ruleset).valid)

        document.updateAsync { root, _ ->
            val user = root.getAs<JsonObject>("user")
            user["name"] = 123
        }.await()
        val result = validateYorkieRuleset(document.getRootObject(), ruleset)
        assertTrue(!result.valid)
        assertEquals(
            expected = listOf(
                ValidationError(
                    path = "\$.user.name",
                    message = "expected string at path \$.user.name",
                ),
            ),
            actual = result.errors,
        )
    }

    @Test
    fun `should handle yorkie types correctly`() = runTest {
        val ruleset = listOf(
            Rule.ObjectRule(
                path = "\$",
                type = "object",
                properties = listOf("text", "tree", "counter"),
                optional = emptyList(),
            ),
            Rule.YorkieTypeRule(
                path = "\$.text",
                type = "yorkie.Text",
            ),
            Rule.YorkieTypeRule(
                path = "\$.tree",
                type = "yorkie.Tree",
            ),
            Rule.YorkieTypeRule(
                path = "\$.counter",
                type = "yorkie.Counter",
            ),
        )

        val documentKey = UUID.randomUUID().toString().toDocKey()
        val document = Document(documentKey)
        document.updateAsync { root, _ ->
            root.setNewText("text")
            root.setNewTree("tree", element("doc"))
            root.setNewCounter("counter", 0)
        }.await()
        assertTrue(validateYorkieRuleset(document.getRootObject(), ruleset).valid)

        document.updateAsync { root, _ ->
            root["text"] = "text"
            root["tree"] = "doc"
            root["counter"] = 1
        }.await()
        val result = validateYorkieRuleset(document.getRootObject(), ruleset)
        assertTrue(!result.valid)
        assertEquals(
            expected = listOf(
                ValidationError(
                    path = "\$.text",
                    message = "expected yorkie.Text at path \$.text",
                ),
                ValidationError(
                    path = "\$.tree",
                    message = "expected yorkie.Tree at path \$.tree",
                ),
                ValidationError(
                    path = "\$.counter",
                    message = "expected yorkie.Counter at path \$.counter",
                ),
            ),
            actual = result.errors,
        )
    }
}
