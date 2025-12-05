package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class GCTest {
    private lateinit var target: Document

    @Before
    fun setUp() {
        target = Document(Document.Key(""))
    }

    @After
    fun tearDown() {
        target.close()
    }

    @Test
    fun `should collect garbage`() = runTest {
        assertJsonContentEquals("{}", target.toJson())

        target.updateAsync { root, _ ->
            root["1"] = 1
            root.setNewArray("2").apply {
                put(1)
                put(2)
                put(3)
            }
            root["3"] = 3
        }.await()
        assertJsonContentEquals("""{"1":1,"2":[1,2,3],"3":3}""", target.toJson())

        target.updateAsync { root, _ ->
            root.remove("2")
        }.await()
        assertJsonContentEquals("""{"1":1,"3":3}""", target.toJson())
        assertEquals(4, target.garbageLength)
        assertEquals(4, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should not collect garbage if disabled`() = runTest {
        target.close()
        target = Document(Document.Key(""), Document.Options(disableGC = true))

        target.updateAsync { root, _ ->
            root["1"] = 1
            root.setNewArray("2").apply {
                put(1)
                put(2)
                put(3)
            }
            root["3"] = 3
        }.await()
        assertJsonContentEquals("""{"1":1,"2":[1,2,3],"3":3}""", target.toJson())

        target.updateAsync { root, _ ->
            root.remove("2")
        }.await()
        assertJsonContentEquals("""{"1":1,"3":3}""", target.toJson())
        assertEquals(4, target.garbageLength)
        assertEquals(0, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(4, target.garbageLength)
    }

    @Test
    fun `should collect garbage for big array`() = runTest {
        val size = 10000

        target.updateAsync { root, _ ->
            root.setNewArray("1").apply {
                repeat(size) { i ->
                    put(i)
                }
            }
        }.await()

        target.updateAsync { root, _ ->
            root.remove("1")
        }.await()

        assertEquals(size + 1, target.garbageCollect(maxVectorOf(listOf())))
    }

    @Test
    fun `should collect garbage for nested elements`() = runTest {
        assertJsonContentEquals("{}", target.toJson())

        target.updateAsync { root, _ ->
            root.setNewArray("list").apply {
                put(1)
                put(2)
                put(3)
            }
        }.await()
        assertJsonContentEquals("""{"list":[1,2,3]}""", target.toJson())

        target.updateAsync { root, _ ->
            root.getAs<JsonArray>("list").removeAt(1)
        }.await()
        assertJsonContentEquals("""{"list":[1,3]}""", target.toJson())

        assertEquals(1, target.garbageLength)
        assertEquals(1, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should collect garbage for text node`() = runTest {
        target.updateAsync { root, _ ->
            root.setNewText("text")
        }.await()
        target.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(0, 0, "ABCD")
        }.await()
        target.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(0, 2, "12")
        }.await()

        assertEquals(1, target.garbageLength)
        target.garbageCollect(maxVectorOf(listOf()))
        assertEquals(0, target.garbageLength)

        target.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(2, 4, "")
        }.await()

        assertEquals(1, target.garbageLength)
    }

    @Test
    fun `should return correct gc count with already removed text node`() = runTest {
        assertJsonContentEquals("{}", target.toJson())

        target.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "ab")
                edit(0, 1, "c")
            }
        }.await()
        assertJsonContentEquals("""{"k1":[{"val":"c"},{"val":"b"}]}""", target.toJson())
        assertEquals(1, target.garbageLength)

        target.updateAsync { root, _ ->
            root.getAs<JsonText>("k1").edit(1, 2, "d")
        }.await()
        assertJsonContentEquals("""{"k1":[{"val":"c"},{"val":"d"}]}""", target.toJson())
        assertEquals(2, target.garbageLength)

        assertEquals(2, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should collect garbage for text node with attributes`() = runTest {
        assertJsonContentEquals("{}", target.toJson())

        var expectedMessage = """{"k1":[{"attrs":{"b":"1"},"val":"Hello "},{"val":"mario"}]}"""

        target.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "Hello world", mapOf("b" to "1"))
                edit(6, 11, "mario")
            }
        }.await()
        assertJsonContentEquals(expectedMessage, target.toJson())
        assertEquals(1, target.garbageLength)

        expectedMessage =
            """
                {"k1":[{"attrs":{"b":"1"},"val":"Hi"},{"attrs":{"b":"1"},"val":" "},{"val":"j"}
                ,{"attrs":{"b":"1"},"val":"ane"}]}
            """.trimIndent()

        target.updateAsync { root, _ ->
            val text = root.getAs<JsonText>("k1")
            text.edit(0, 5, "Hi", mapOf("b" to "1"))
            text.edit(3, 4, "j")
            text.edit(4, 8, "ane", mapOf("b" to "1"))
        }.await()
        assertJsonContentEquals(expectedMessage, target.toJson())

        val expectedGarbageLen = 4
        assertEquals(expectedGarbageLen, target.garbageLength)
        assertEquals(expectedGarbageLen, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should collect garbage for tree node`() = runTest {
        assertJsonContentEquals("{}", target.toJson())

        target.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("tn") {
                            text { "a" }
                            text { "b" }
                        }
                        element("tn") {
                            text { "cd" }
                        }
                    }
                },
            )
        }.await()

        fun JsonObject.tree() = getAs<JsonTree>("t")

        target.updateAsync { root, _ ->
            root.tree().editByPath(listOf(0, 0, 0), listOf(0, 0, 2), text { "gh" })
            assertEquals("<doc><p><tn>gh</tn><tn>cd</tn></p></doc>", root.tree().toXml())
        }.await()

        // [text(a), text(b)]
        assertEquals(2, target.garbageLength)
        assertEquals(2, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)

        target.updateAsync { root, _ ->
            root.tree().editByPath(listOf(0, 0, 0), listOf(0, 0, 2), text { "cv" })
            assertEquals("<doc><p><tn>cv</tn><tn>cd</tn></p></doc>", root.tree().toXml())
        }.await()

        // [text(gh)]
        assertEquals(1, target.garbageLength)
        assertEquals(1, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)

        target.updateAsync { root, _ ->
            root.tree().editByPath(
                listOf(0),
                listOf(1),
                element("p") {
                    element("tn") {
                        text { "ab" }
                    }
                },
            )
            assertEquals("<doc><p><tn>ab</tn></p></doc>", root.tree().toXml())
        }.await()

        // [p, tn, tn, text(cv), text(cd)]
        assertEquals(5, target.garbageLength)
        assertEquals(5, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should return correct gc count with already removed tree node`() = runTest {
        assertJsonContentEquals("{}", target.toJson())

        target.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("tn") {
                            text { "abc" }
                        }
                    }
                },
            )
        }.await()

        fun JsonObject.tree() = getAs<JsonTree>("t")

        assertEquals("<doc><p><tn>abc</tn></p></doc>", target.getRoot().tree().toXml())
        assertEquals(0, target.garbageLength)

        target.updateAsync { root, _ ->
            root.tree().edit(3, 4)
        }.await()
        assertEquals("<doc><p><tn>ac</tn></p></doc>", target.getRoot().tree().toXml())
        assertEquals(1, target.garbageLength)

        target.updateAsync { root, _ ->
            root.tree().edit(2, 4)
        }.await()
        assertEquals("<doc><p><tn></tn></p></doc>", target.getRoot().tree().toXml())
        assertEquals(3, target.garbageLength)

        assertEquals(3, target.garbageCollect(maxVectorOf(listOf())))
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should collect garbage for nested object`() = runTest {
        target.updateAsync { root, _ ->
            root.setNewObject("shape").setNewObject("point").apply {
                set("x", 0)
                set("y", 0)
            }
            root.remove("shape")
        }.await()
        assertEquals(4, target.garbageLength) // shape, point, x, y
        assertEquals(4, target.garbageCollect(maxVectorOf(listOf())))
    }

    @Test
    fun `should update gc size correctly after text garbage collection`() = runTest {
        // Initial state
        target.updateAsync { root, _ ->
            root.setNewText("text")
        }.await()
        val initialSize = target.getDocSize()
        assertEquals(DataSize(0, 0), initialSize.gc)

        // Add and then remove text to create garbage
        target.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(0, 0, "Hello world")
        }.await()
        target.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(6, 11, "")
        }.await()

        val sizeBeforeGC = target.getDocSize()
        assertEquals(10, sizeBeforeGC.gc.data)
        assertEquals(48, sizeBeforeGC.gc.meta)
        assertEquals(1, target.garbageLength)

        // Perform garbage collection
        val collected = target.garbageCollect(maxVectorOf(listOf(target.changeID.actor.value)))
        assertEquals(1, collected)

        // Verify gc size is properly reset after collection
        val sizeAfterGC = target.getDocSize()
        assertEquals(DataSize(0, 0), sizeAfterGC.gc)
        assertEquals(0, target.garbageLength)
    }

    @Test
    fun `should update gc size correctly after multiple text operations and gc`() = runTest {
        target.updateAsync { root, _ ->
            root.setNewText("text")
        }.await()

        // Create multiple text segments and then remove some
        target.updateAsync { root, _ ->
            val text = root.getAs<JsonText>("text")
            text.edit(0, 0, "ABC")
            text.edit(1, 2, "X")
            text.edit(2, 3, "")
        }.await()

        val sizeBeforeGC = target.getDocSize()
        val garbageLen = target.garbageLength

        assertEquals(4, sizeBeforeGC.gc.data)
        assertEquals(96, sizeBeforeGC.gc.meta)
        assertEquals(2, garbageLen) // B and C should be garbage

        // Perform garbage collection
        val collected = target.garbageCollect(maxVectorOf(listOf(target.changeID.actor.value)))
        assertEquals(garbageLen, collected)

        // Verify all gc size is cleared
        val sizeAfterGC = target.getDocSize()
        assertEquals(DataSize(0, 0), sizeAfterGC.gc)
        assertEquals(0, target.garbageLength)
    }
}
