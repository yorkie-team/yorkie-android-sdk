package dev.yorkie.microbenchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket.Companion.MaxTimeTicket
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun construct() {
        benchmarkRule.measureRepeated {
            Document(Document.Key("d1")).also {
                it.close()
            }
        }
    }

    @Test
    fun equals() {
        benchmarkRule.measureRepeated {
            runTest {
                val docs = runWithTimingDisabled {
                    listOf(
                        Document(Document.Key("d1")),
                        Document(Document.Key("d2")),
                        Document(Document.Key("d3")),
                    )
                }

                docs[0].updateAsync("updates k1") { root, _ ->
                    root["k1"] = "v1"
                }.await()

                assert(docs[0].toJson() != docs[1].toJson())
                assert(docs[1].toJson() == docs[2].toJson())
                docs.forEach(Document::close)
            }
        }
    }

    @Test
    fun nested_updates() {
        benchmarkRule.measureRepeated {
            runTest {
                val expected = runWithTimingDisabled {
                    """{"k1":"v1","k2":{"k4":"v4"},"k3":["v5","v6"]}"""
                }
                val document = runWithTimingDisabled {
                    Document(Document.Key("d1"))
                }
                document.updateAsync { root, _ ->
                    root["k1"] = "v1"
                    root.setNewObject("k2").also { obj ->
                        obj["k4"] = "v4"
                    }
                    root.setNewArray("k3").also { array ->
                        array.put("v5")
                        array.put("v6")
                    }
                }.await()
                assert(document.toJson() == expected)
                document.close()
            }
        }
    }

    @Test
    fun delete() {
        benchmarkRule.measureRepeated {
            runTest {
                val document = runWithTimingDisabled {
                    Document(Document.Key("d1"))
                }
                var expected = runWithTimingDisabled {
                    """{"k1":"v1","k2":{"k4":"v4"},"k3":["v5","v6"]}"""
                }
                document.updateAsync("updates k1, k2, k3") { root, _ ->
                    root["k1"] = "v1"
                    root.setNewObject("k2").also { obj ->
                        obj["k4"] = "v4"
                    }
                    root.setNewArray("k3").also { array ->
                        array.put("v5")
                        array.put("v6")
                    }
                }.await()
                assert(document.toJson() == expected)

                expected = runWithTimingDisabled {
                    """{"k1":"v1","k3":["v5","v6"]}"""
                }
                document.updateAsync("deletes k2") { root, _ ->
                    root.remove("k2")
                }.await()
                assert(document.toJson() == expected)
                document.close()
            }
        }
    }

    @Test
    fun obj() {
        benchmarkRule.measureRepeated {
            runTest {
                val document = runWithTimingDisabled {
                    Document(Document.Key("d1"))
                }
                document.updateAsync { root, _ ->
                    root["k1"] = "v1"
                    root["k1"] = "v2"
                }.await()
                assert(document.toJson() == """{"k1":"v2"}""")
                document.close()
            }
        }
    }

    @Test
    fun tree_100() {
        benchmarkTree(100)
    }

    @Test
    fun tree_1000() {
        benchmarkTree(1000)
    }

    @Test
    fun tree_delete_100() {
        benchmarkTreeDeleteAll(100)
    }

    @Test
    fun tree_delete_1000() {
        benchmarkTreeDeleteAll(1000)
    }

    @Test
    fun tree_split_gc_100() {
        benchmarkTreeSplitGC(100)
    }

    @Test
    fun tree_split_gc_1000() {
        benchmarkTreeSplitGC(1000)
    }

    @Test
    fun tree_edit_gc_100() {
        benchmarkTreeEditGC(100)
    }

    @Test
    fun tree_edit_gc_1000() {
        benchmarkTreeEditGC(1000)
    }

    private fun benchmarkTree(size: Int) {
        benchmarkRule.measureRepeated {
            runTest {
                val document = runWithTimingDisabled {
                    Document(Document.Key("d1"))
                }
                document.updateAsync { root, _ ->
                    root.setNewTree("tree", element("doc") { element("p") }).apply {
                        for (i in 1..size) {
                            edit(i, i, text { "a" })
                        }
                    }
                }.await()
                document.close()
            }
        }
    }

    private fun benchmarkTreeDeleteAll(size: Int) {
        benchmarkRule.measureRepeated {
            runTest {
                val document = runWithTimingDisabled {
                    Document(Document.Key("d1"))
                }
                document.updateAsync { root, _ ->
                    root.setNewTree("tree", element("doc") { element("p") }).apply {
                        for (i in 1..size) {
                            edit(i, i, text { "a" })
                        }
                        edit(1, size + 1)
                    }
                }.await()
                val expected = runWithTimingDisabled {
                    "<doc><p></p></doc>"
                }
                assert(document.getRoot().getAs<JsonTree>("tree").toXml() == expected)
                document.close()
            }
        }
    }

    private fun benchmarkTreeSplitGC(size: Int) {
        benchmarkRule.measureRepeated {
            runTest {
                val document = runWithTimingDisabled {
                    Document(Document.Key("d1"))
                }
                document.updateAsync { root, _ ->
                    root.setNewTree(
                        "tree",
                        element("doc") {
                            element("p") {
                                text { "a".repeat(size) }
                            }
                        },
                    )
                }.await()

                document.updateAsync { root, _ ->
                    for (i in 1..size) {
                        root.getAs<JsonTree>("tree").edit(i, i + 1, text { "b" })
                    }
                }.await()

                assert(document.garbageLength == size)
                assert(document.garbageCollect(MaxTimeTicket) == size)
                assert(document.garbageLength == 0)
                document.close()
            }
        }
    }

    private fun benchmarkTreeEditGC(size: Int) {
        benchmarkRule.measureRepeated {
            runTest {
                val document = runWithTimingDisabled {
                    Document(Document.Key("d1"))
                }
                document.updateAsync { root, _ ->
                    root.setNewTree("tree", element("doc") { element("p") }).apply {
                        for (i in 1..size) {
                            edit(i, i, text { "a" })
                        }
                    }
                }.await()

                document.updateAsync { root, _ ->
                    for (i in 1..size) {
                        root.getAs<JsonTree>("tree").edit(i, i + 1, text { "b" })
                    }
                }.await()

                assert(document.garbageLength == size)
                assert(document.garbageCollect(MaxTimeTicket) == size)
                assert(document.garbageLength == 0)
                document.close()
            }
        }
    }
}
