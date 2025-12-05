package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.Document
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonTextTest {

    @Test
    fun test_handle_edit_operations() = runBlocking {
        val doc = Document(Document.Key("test-doc"))
        assertEquals("{}", doc.toJson())

        doc.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "ABCD")
                edit(1, 3, "12")
            }
        }.await()

        assertEquals("""{"k1":[{"val":"A"},{"val":"12"},{"val":"D"}]}""", doc.toJson())
    }

    @Test
    fun test_handle_edit_operations2() = runBlocking {
        val doc = Document(Document.Key("test-doc"))
        assertEquals("{}", doc.toJson())

        doc.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "ABCD")
                edit(3, 3, "\n")
            }
        }.await()

        assertEquals("""{"k1":[{"val":"ABC"},{"val":"\n"},{"val":"D"}]}""", doc.toJson())
    }

    @Test
    fun test_handle_type_korean() = runBlocking {
        val doc = Document(Document.Key("test-doc"))
        assertEquals("{}", doc.toJson())

        doc.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "ㅎ")
                edit(0, 1, "하")
                edit(0, 1, "한")
                edit(0, 1, "하")
                edit(1, 1, "느")
                edit(1, 2, "늘")
            }
        }.await()

        assertEquals("""{"k1":[{"val":"하"},{"val":"늘"}]}""", doc.toJson())
    }

    @Test
    fun test_handle_text_delete_operations() = runBlocking {
        val doc = Document(Document.Key("test-doc"))

        doc.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "ABCD")
            }
        }.await()
        assertEquals("ABCD", doc.getRoot().getAs<JsonText>("k1").toString())

        doc.updateAsync { root, _ ->
            root.getAs<JsonText>("k1").edit(1, 3, "")
        }.await()
        assertEquals("AD", doc.getRoot().getAs<JsonText>("k1").toString())
    }

    @Test
    fun test_handle_text_empty_operations() = runBlocking {
        val doc = Document(Document.Key("test-doc"))

        doc.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "ABCD")
            }
        }.await()
        assertEquals("ABCD", doc.getRoot().getAs<JsonText>("k1").toString())

        doc.updateAsync { root, _ ->
            root.getAs<JsonText>("k1").edit(0, 4, "")
        }.await()
        assertEquals("", doc.getRoot().getAs<JsonText>("k1").toString())
    }

    @Test
    fun test_handle_edit_operations_with_two_clients() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "ABCD")
                }
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"ABCD"}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "1234")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"1234"}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_handle_concurrent_edit_operations() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(0, 0, "ABCD")
            }.await()
            assertJsonContentEquals("""{"k1":[{"val":"ABCD"}]}""", d1.toJson())

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(0, 0, "1234")
            }.await()
            assertJsonContentEquals("""{"k1":[{"val":"1234"}]}""", d2.toJson())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(2, 3, "XX")
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(2, 3, "YY")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(4, 5, "ZZ")
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(2, 3, "TT")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_concurrent_insertion_and_deletion() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "AB")
                }
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertJsonContentEquals("""{"k1":[{"val":"AB"}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(0, 2, "")
            }.await()
            assertJsonContentEquals("""{"k1":[]}""", d1.toJson())

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(1, 1, "C")
            }.await()
            assertJsonContentEquals("""{"k1":[{"val":"A"},{"val":"C"},{"val":"B"}]}""", d2.toJson())

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            assertJsonContentEquals("""{"k1":[{"val":"C"}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_handle_concurrent_block_deletions() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "123")
                    edit(3, 3, "456")
                    edit(6, 6, "789")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"val":"123"},{"val":"456"},{"val":"789"}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(1, 7, "")
            }.await()
            assertJsonContentEquals("""{"k1":[{"val":"1"},{"val":"89"}]}""", d1.toJson())

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(2, 5, "")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"12"},{"val":"6"},{"val":"789"}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
        }
    }

    @Test
    fun test_should_maintain_correct_weight_on_concurrent_editing() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "O")
                    edit(1, 1, "O")
                    edit(2, 2, "O")
                }
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(1, 2, "X")
                root.getAs<JsonText>("k1").edit(1, 2, "X")
                root.getAs<JsonText>("k1").edit(1, 2, "")
            }.await()

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(0, 3, "N")
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            assertEquals(1, d1.getRoot().getAs<JsonText>("k1").treeByIndex.length)
            assertEquals(1, d2.getRoot().getAs<JsonText>("k1").treeByIndex.length)
        }
    }

    @Test
    fun test_concurrent_insertions_on_plain_text() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(4, 4, "quick ")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"val":"quick "},{"val":"fox jumped."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(14, 14, " over the dog")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The fox jumped"},{"val":" over the dog"},{"val":"."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"val":"quick "},{"val":"fox jumped"},""" +
                    """{"val":" over the dog"},{"val":"."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_handle_text_edit_operations_with_attributes() = runBlocking {
        val doc = Document(Document.Key("test-doc"))
        assertEquals("{}", doc.toJson())

        doc.updateAsync { root, _ ->
            root.setNewText("k1").apply {
                edit(0, 0, "ABCD", mapOf("b" to "1"))
                edit(3, 3, "\n")
            }
        }.await()

        assertEquals(
            """{"k1":[{"attrs":{"b":"1"},"val":"ABC"},{"val":"\n"},""" +
                """{"attrs":{"b":"1"},"val":"D"}]}""",
            doc.toJson(),
        )
    }

    @Test
    fun test_concurrent_formatting_and_insertion() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(0, 15, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The fox jumped."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(4, 4, "brown ")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"val":"brown "},{"val":"fox jumped."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The "},{"val":"brown "},""" +
                    """{"attrs":{"bold":"true"},"val":"fox jumped."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_overlapping_formatting_bold() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(0, 7, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The fox"},{"val":" jumped."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(4, 15, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"attrs":{"bold":"true"},"val":"fox jumped."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The "},""" +
                    """{"attrs":{"bold":"true"},"val":"fox"},""" +
                    """{"attrs":{"bold":"true"},"val":" jumped."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_overlapping_different_formatting_bold_and_italic() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(0, 7, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The fox"},{"val":" jumped."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(4, 15, mapOf("italic" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"attrs":{"italic":"true"},"val":"fox jumped."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The "},""" +
                    """{"attrs":{"bold":"true","italic":"true"},"val":"fox"},""" +
                    """{"attrs":{"italic":"true"},"val":" jumped."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_conflicting_overlaps_highlighting() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(0, 7, mapOf("highlight" to "red"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"highlight":"red"},"val":"The fox"},{"val":" jumped."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(4, 15, mapOf("highlight" to "blue"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"attrs":{"highlight":"blue"},"val":"fox jumped."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"highlight":"red"},"val":"The "},""" +
                    """{"attrs":{"highlight":"blue"},"val":"fox"},""" +
                    """{"attrs":{"highlight":"blue"},"val":" jumped."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_conflicting_overlaps_bold_1() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(0, 15, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The fox jumped."}]}""",
                d1.toJson(),
            )

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(4, 15, mapOf("bold" to "false"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The "},""" +
                    """{"attrs":{"bold":"false"},"val":"fox jumped."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(8, 15, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The fox "},{"attrs":{"bold":"true"},"val":"jumped."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The "},""" +
                    """{"attrs":{"bold":"false"},"val":"fox "},""" +
                    """{"attrs":{"bold":"true"},"val":"jumped."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_conflicting_overlaps_bold_2() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(0, 15, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The fox jumped."}]}""",
                d1.toJson(),
            )

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(4, 15, mapOf("bold" to "false"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The "},""" +
                    """{"attrs":{"bold":"false"},"val":"fox jumped."}]}""",
                d1.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(8, 15, mapOf("bold" to "true"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"bold":"true"},"val":"The "},""" +
                    """{"attrs":{"bold":"false"},"val":"fox "},""" +
                    """{"attrs":{"bold":"true"},"val":"jumped."}]}""",
                d2.toJson(),
            )

            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_multiple_instances_of_the_same_mark() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"The fox jumped."}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(0, 7, mapOf("comment" to "Alice's comment"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"comment":"Alice's comment"},"val":"The fox"},""" +
                    """{"val":" jumped."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").style(4, 15, mapOf("comment" to "Bob's comment"))
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},""" +
                    """{"attrs":{"comment":"Bob's comment"},"val":"fox jumped."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"attrs":{"comment":"Alice's comment"},"val":"The "},""" +
                    """{"attrs":{"comment":"Bob's comment"},"val":"fox"},""" +
                    """{"attrs":{"comment":"Bob's comment"},"val":" jumped."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_text_insertion_at_span_boundaries_bold() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                    style(4, 14, mapOf("bold" to "true"))
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"attrs":{"bold":"true"},"val":"fox jumped"},""" +
                    """{"val":"."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(4, 4, "quick ")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"val":"quick "},""" +
                    """{"attrs":{"bold":"true"},"val":"fox jumped"},{"val":"."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(14, 14, " over the dog")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"attrs":{"bold":"true"},"val":"fox jumped"},""" +
                    """{"val":" over the dog"},{"val":"."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"val":"quick "},""" +
                    """{"attrs":{"bold":"true"},"val":"fox jumped"},""" +
                    """{"val":" over the dog"},{"val":"."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_text_insertion_at_span_boundaries_link() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "The fox jumped.")
                    style(4, 14, mapOf("link" to "https://www.google.com/search?q=jumping+fox"))
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},""" +
                    """{"attrs":{"link":"https://www.google.com/search?q=jumping+fox"},""" +
                    """"val":"fox jumped"},{"val":"."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(4, 4, "quick ")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"val":"quick "},""" +
                    """{"attrs":{"link":"https://www.google.com/search?q=jumping+fox"},""" +
                    """"val":"fox jumped"},{"val":"."}]}""",
                d1.toJson(),
            )

            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(14, 14, " over the dog")
            }.await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},""" +
                    """{"attrs":{"link":"https://www.google.com/search?q=jumping+fox"},""" +
                    """"val":"fox jumped"},{"val":" over the dog"},{"val":"."}]}""",
                d2.toJson(),
            )

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(
                """{"k1":[{"val":"The "},{"val":"quick "},""" +
                    """{"attrs":{"link":"https://www.google.com/search?q=jumping+fox"},""" +
                    """"val":"fox jumped"},{"val":" over the dog"},{"val":"."}]}""",
                d1.toJson(),
            )
            assertJsonContentEquals(d1.toJson(), d2.toJson())
        }
    }

    @Test
    fun test_handle_deletion_of_nested_nodes() = runBlocking {
        val doc = Document(Document.Key("test-doc"))

        doc.updateAsync { root, _ ->
            root.setNewText("text")
        }.await()

        val commands = listOf(
            Triple(0, 0, "ABC"),
            Triple(3, 3, "DEF"),
            Triple(2, 4, "1"),
            Triple(1, 4, "2"),
        )

        for ((from, to, content) in commands) {
            doc.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(from, to, content)
            }.await()
        }

        // Final result should be "A2F"
        assertEquals("A2F", doc.getRoot().getAs<JsonText>("text").toString())
    }

    @Test
    fun test_handle_deletion_of_last_nodes() = runBlocking {
        val doc = Document(Document.Key("test-doc"))

        doc.updateAsync { root, _ ->
            root.setNewText("text")
        }.await()

        val commands = listOf(
            Triple(0, 0, "A"),
            Triple(1, 1, "B"),
            Triple(2, 2, "C"),
            Triple(3, 3, "DE"),
            Triple(5, 5, "F"),
            Triple(6, 6, "GHI"),
            // delete no last node
            Triple(9, 9, ""),
            // delete one last node with split
            Triple(8, 9, ""),
            // delete one last node without split
            Triple(6, 8, ""),
            // delete last nodes with split
            Triple(4, 6, ""),
            // delete last nodes without split
            Triple(2, 4, ""),
            // delete last nodes containing the first
            Triple(0, 2, ""),
        )

        for ((from, to, content) in commands) {
            doc.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(from, to, content)
            }.await()
        }

        // Final result should be empty
        assertEquals("", doc.getRoot().getAs<JsonText>("text").toString())
    }

    @Test
    fun test_handle_deletion_with_boundary_nodes_already_removed() = runBlocking {
        val doc = Document(Document.Key("test-doc"))

        doc.updateAsync { root, _ ->
            root.setNewText("text")
        }.await()

        val commands = listOf(
            Triple(0, 0, "1A1BCXEF1"),
            Triple(8, 9, ""),
            Triple(2, 3, ""),
            // ABCXEF
            Triple(0, 1, ""),
            // delete A with two removed boundaries
            Triple(0, 1, ""),
            // delete B with removed left boundary
            Triple(0, 1, ""),
            // delete F with removed right boundary
            Triple(3, 4, ""),
            Triple(1, 2, ""),
            // delete CE with removed inner node X
            Triple(0, 2, ""),
        )

        for ((from, to, content) in commands) {
            doc.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(from, to, content)
            }.await()
        }

        // Final result should be empty
        assertEquals("", doc.getRoot().getAs<JsonText>("text").toString())
    }

    @Test
    fun test_causal_deletion_convergence() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            // Insert initial text on c1
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "abcd")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"abcd"}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            // First deletion (bc) by c1
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(1, 3, "")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()

            // Second deletion (ad) by c2
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(0, 2, "")
            }.await()

            // Final sync and ensure convergence
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertJsonContentEquals(d1.toJson(), d2.toJson())
            assertJsonContentEquals("""{"k1":[]}""", d1.toJson())
        }
    }

    @Test
    fun test_concurrent_deletion_lww_behavior() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            // Insert initial text on c1
            d1.updateAsync { root, _ ->
                root.setNewText("k1").apply {
                    edit(0, 0, "abcd")
                }
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertJsonContentEquals("""{"k1":[{"val":"abcd"}]}""", d1.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            // Concurrent deletions
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(1, 3, "")
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("k1").edit(0, 4, "")
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            // All visible content should be removed
            assertJsonContentEquals("""{"k1":[]}""", d1.toJson())
            assertJsonContentEquals("""{"k1":[]}""", d2.toJson())
            assertJsonContentEquals(d1.toJson(), d2.toJson())

            for (n in d1.getRoot().getAs<JsonText>("k1").target.rgaTreeSplit) {
                assertEquals(
                    expected = true,
                    actual = n.removedAt != null,
                )
            }

            for (n in d2.getRoot().getAs<JsonText>("k1").target.rgaTreeSplit) {
                assertEquals(
                    expected = true,
                    actual = n.removedAt != null,
                )
            }

            c2.syncAsync().await()
            c1.syncAsync().await()

            val timeStamp = HashSet<TimeTicket>()
            for (n in d1.getRoot().getAs<JsonText>("k1").target.rgaTreeSplit) {
                if (n.removedAt != null) {
                    timeStamp.add(n.removedAt!!)
                }
            }

            for (n in d2.getRoot().getAs<JsonText>("k1").target.rgaTreeSplit) {
                if (n.removedAt != null) {
                    timeStamp.add(n.removedAt!!)
                }
            }

            assertEquals(
                expected = 1,
                actual = timeStamp.size,
            )
        }
    }
}
