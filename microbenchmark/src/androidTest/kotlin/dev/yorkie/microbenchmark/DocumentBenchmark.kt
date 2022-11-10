package dev.yorkie.microbenchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DocumentBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun construct() {
        benchmarkRule.measureRepeated {
            Document(Document.Key("d1"))
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

                docs[0].updateAsync("updates k1") {
                    it["k1"] = "v1"
                }.await()

                assert(docs[0].toJson() != docs[1].toJson())
                assert(docs[1].toJson() == docs[2].toJson())
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
                document.updateAsync {
                    it["k1"] = "v1"
                    it.setNewObject("k2").also { obj ->
                        obj["k4"] = "v4"
                    }
                    it.setNewArray("k3").also { array ->
                        array.put("v5")
                        array.put("v6")
                    }
                }.await()
                assert(document.toJson() == expected)
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
                document.updateAsync("updates k1, k2, k3") {
                    it["k1"] = "v1"
                    it.setNewObject("k2").also { obj ->
                        obj["k4"] = "v4"
                    }
                    it.setNewArray("k3").also { array ->
                        array.put("v5")
                        array.put("v6")
                    }
                }.await()
                assert(document.toJson() == expected)

                expected = runWithTimingDisabled {
                    """{"k1":"v1","k3":["v5","v6"]}"""
                }
                document.updateAsync("deletes k2") {
                    it.remove("k2")
                }.await()
                assert(document.toJson() == expected)
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
                document.updateAsync {
                    it["k1"] = "v1"
                    it["k1"] = "v2"
                }.await()
                assert(document.toJson() == """{"k1":"v2"}""")
            }
        }
    }
}
