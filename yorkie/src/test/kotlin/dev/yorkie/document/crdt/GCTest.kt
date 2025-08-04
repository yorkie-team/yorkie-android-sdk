package dev.yorkie.document.crdt

import dev.yorkie.OpCode.TextCode
import dev.yorkie.OpCode.TreeCode
import dev.yorkie.Step
import dev.yorkie.TestCase
import dev.yorkie.TestOperation
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GCTest {

    @Test
    fun `should handle garbage collection for tree`() = runTest {
        val tests = listOf(
            TestCase(
                "style-style test",
                listOf(
                    Step(
                        TestOperation(TreeCode.Style, "b", "t"),
                        """<r><p b="t"></p></r>""",
                        0,
                    ),
                    Step(
                        TestOperation(TreeCode.Style, "b", "f"),
                        """<r><p b="f"></p></r>""",
                        0,
                    ),
                ),
            ),
            TestCase(
                "style-remove test",
                listOf(
                    Step(
                        TestOperation(TreeCode.Style, "b", "t"),
                        """<r><p b="t"></p></r>""",
                        0,
                    ),
                    Step(
                        TestOperation(TreeCode.RemoveStyle, "b", ""),
                        "<r><p></p></r>",
                        1,
                    ),
                ),
            ),
            TestCase(
                "remove-style test",
                listOf(
                    Step(
                        TestOperation(TreeCode.RemoveStyle, "b", ""),
                        "<r><p></p></r>",
                        1,
                    ),
                    Step(
                        TestOperation(TreeCode.Style, "b", "t"),
                        """<r><p b="t"></p></r>""",
                        0,
                    ),
                ),
            ),
            TestCase(
                "remove-remove test",
                listOf(
                    Step(
                        TestOperation(TreeCode.RemoveStyle, "b", ""),
                        "<r><p></p></r>",
                        1,
                    ),
                    Step(
                        TestOperation(TreeCode.RemoveStyle, "b", ""),
                        "<r><p></p></r>",
                        1,
                    ),
                ),
            ),
            TestCase(
                "style-delete test",
                listOf(
                    Step(
                        TestOperation(TreeCode.Style, "b", "t"),
                        """<r><p b="t"></p></r>""",
                        0,
                    ),
                    Step(
                        TestOperation(TreeCode.DeleteNode, "", ""),
                        "<r></r>",
                        1,
                    ),
                ),
            ),
            TestCase(
                "remove-delete test",
                listOf(
                    Step(
                        TestOperation(TreeCode.RemoveStyle, "b", ""),
                        "<r><p></p></r>",
                        1,
                    ),
                    Step(
                        TestOperation(TreeCode.DeleteNode, "b", "t"),
                        "<r></r>",
                        2,
                    ),
                ),
            ),
            TestCase(
                "remove-gc-delete test",
                listOf(
                    Step(
                        TestOperation(TreeCode.RemoveStyle, "b", ""),
                        "<r><p></p></r>",
                        1,
                    ),
                    Step(
                        TestOperation(TreeCode.GC, "", ""),
                        "<r><p></p></r>",
                        0,
                    ),
                    Step(
                        TestOperation(TreeCode.DeleteNode, "b", "t"),
                        "<r></r>",
                        1,
                    ),
                ),
            ),
        )
        tests.forEach { test ->
            val doc = Document(Document.Key(""))
            doc.updateAsync { root, _ ->
                root.setNewTree("t", element("r") { element("p") })
            }.await()

            fun JsonObject.rootTree() = getAs<JsonTree>("t")
            val versionVector = maxVectorOf(listOf())

            assertEquals("<r><p></p></r>", doc.getRoot().rootTree().toXml())
            test.steps.forEach { (op, expectedXml, garbageLen) ->
                doc.updateAsync { root, _ ->
                    when (op.code) {
                        TreeCode.RemoveStyle -> root.rootTree().removeStyle(0, 1, listOf(op.key))
                        TreeCode.Style -> root.rootTree().style(0, 1, mapOf(op.key to op.value))
                        TreeCode.DeleteNode -> root.rootTree().edit(0, 2)
                        TreeCode.GC -> {
                            doc.garbageCollect(versionVector)
                        }
                    }
                }.await()
                assertEquals(expectedXml, doc.getRoot().rootTree().toXml())
                assertEquals(garbageLen, doc.garbageLength)
            }

            doc.garbageCollect(versionVector)
            assertEquals(0, doc.garbageLength)
        }
    }

    @Test
    fun `should handle garbage collection for text`() = runTest {
        val tests = listOf(
            TestCase(
                "style-style test",
                listOf(
                    Step(
                        TestOperation(TextCode.Style, "b", "t"),
                        """[{"attrs":{"b":"t"},"val":"AB"}]""",
                        0,
                    ),
                    Step(
                        TestOperation(TextCode.Style, "b", "f"),
                        """[{"attrs":{"b":"f"},"val":"AB"}]""",
                        0,
                    ),
                ),
            ),
            TestCase(
                "style-delete test",
                listOf(
                    Step(
                        TestOperation(TextCode.Style, "b", "t"),
                        """[{"attrs":{"b":"t"},"val":"AB"}]""",
                        0,
                    ),
                    Step(
                        TestOperation(TextCode.DeleteNode, "b", ""),
                        "[]",
                        1,
                    ),
                ),
            ),
        )

        tests.forEach { test ->
            val doc = Document(Document.Key(""))
            doc.updateAsync { root, _ ->
                root.setNewText("t").edit(0, 0, "AB")
            }.await()

            fun JsonObject.rootText() = getAs<JsonText>("t")

            assertEquals("""[{"val":"AB"}]""", doc.getRoot().rootText().toJson())

            val versionVector = maxVectorOf(listOf())

            test.steps.forEach { (op, expectedJson, garbageLen) ->
                doc.updateAsync { root, _ ->
                    when (op.code) {
                        TextCode.Style -> root.rootText().style(0, 2, mapOf(op.key to op.value))
                        TextCode.DeleteNode -> root.rootText().edit(0, 2, "")
                        TextCode.GC -> doc.garbageCollect(versionVector)
                    }
                }.await()
                assertEquals(expectedJson, doc.getRoot().rootText().toJson())
                assertEquals(garbageLen, doc.garbageLength)
            }

            doc.garbageCollect(versionVector)
            assertEquals(0, doc.garbageLength)
        }
    }
}
