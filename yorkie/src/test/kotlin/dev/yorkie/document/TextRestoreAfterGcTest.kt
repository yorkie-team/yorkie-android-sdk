package dev.yorkie.document

import dev.yorkie.document.json.JsonText
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val ACTOR = "000000000000000000000001"

/**
 * Ports `270ffc66` (recreate purged text runs in order on restore, not
 * reversed) as JVM unit tests (AC6).
 *
 * Regression for the reversed-text undo (wafflebase#629). Typing character
 * by character makes each character its own single-char insertion. Deleting
 * a contiguous run and then undoing AFTER the tombstones were GC-purged
 * forces every character down restore's recreate path. Because no
 * character shares an insertion with any other, none of the same-insertion
 * anchor rungs fire; each recreated fragment must chain after the one
 * placed just before it, or the run comes back reversed ("my name" ->
 * "eman ym"). Un-tombstoning (no GC) already preserved order, which is why
 * this only reproduced once the run was purged.
 */
class TextRestoreAfterGcTest {

    @Test
    fun `recreates a purged multi-insertion run in document order on undo`() = runTest {
        val doc = Document("text-restore-after-gc")
        doc.setActor(ACTOR)
        doc.updateAsync { root, _ -> root.setNewText("t") }.await()

        val s = "hello my name is"
        for (i in s.indices) {
            val c = s[i].toString()
            doc.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(i, i, c) }.await()
        }
        assertEquals(s, doc.getRoot().getAs<JsonText>("t").toString())

        doc.updateAsync { root, _ ->
            root.getAs<JsonText>(
                "t",
            ).edit(6, 13, "")
        }.await() // "my name"
        assertEquals("hello  is", doc.getRoot().getAs<JsonText>("t").toString())

        val purged = doc.garbageCollect(maxVectorOf(listOf(ACTOR)))
        assertTrue(purged > 0, "the deleted run should be purged")

        doc.history.undoAsync().await()
        assertEquals(
            s,
            doc.getRoot().getAs<JsonText>("t").toString(),
            "a purged run must be recreated in document order, not reversed",
        )
    }

    @Test
    fun `single-insertion run is unaffected`() = runTest {
        val doc = Document("text-restore-after-gc-single")
        doc.setActor(ACTOR)
        doc.updateAsync { root, _ -> root.setNewText("t") }.await()
        doc.updateAsync { root, _ ->
            root.getAs<JsonText>("t").edit(0, 0, "hello my name is")
        }.await()
        doc.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(6, 13, "") }.await()
        doc.garbageCollect(maxVectorOf(listOf(ACTOR)))

        doc.history.undoAsync().await()
        assertEquals("hello my name is", doc.getRoot().getAs<JsonText>("t").toString())
    }
}
