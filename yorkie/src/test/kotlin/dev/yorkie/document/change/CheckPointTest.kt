package dev.yorkie.document.change

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckPointTest {

    @Test
    fun `toTestString should format serverSeq and clientSeq`() {
        val checkPoint = CheckPoint(serverSeq = 7, clientSeq = 2u)

        assertEquals("serverSeq=7, clientSeq=2", checkPoint.toTestString())
    }
}
