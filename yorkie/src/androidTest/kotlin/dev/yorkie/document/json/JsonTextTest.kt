package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.withTwoClientsAndDocuments
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonTextTest {

    @Test
    fun test_concurrent_insertion_and_deletion() {
        withTwoClientsAndDocuments { c1, c2, d1, d2, _ ->
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
}
