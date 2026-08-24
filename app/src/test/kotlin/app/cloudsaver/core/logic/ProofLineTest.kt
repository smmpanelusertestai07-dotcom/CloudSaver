package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofLineTest {

    @Test
    fun `each grade of evidence has its own plain answer`() {
        assertEquals(
            ProofLine.Kind.CLOUD_REMOVED_COPY,
            ProofLine.forItem(Evidence.CONFIRMED_EXACT, isDuplicateExtra = false)
        )
        assertEquals(
            ProofLine.Kind.UPLOAD_SIZE_MATCHED,
            ProofLine.forItem(Evidence.CONFIRMED_PACED, isDuplicateExtra = false)
        )
        assertEquals(
            ProofLine.Kind.TIME_ONLY,
            ProofLine.forItem(Evidence.AGED, isDuplicateExtra = false)
        )
        assertEquals(
            ProofLine.Kind.WAITING,
            ProofLine.forItem(Evidence.NONE, isDuplicateExtra = false)
        )
    }

    @Test
    fun `an identical twin outranks every upload claim`() {
        // It is the only proof that does not depend on the cloud app behaving.
        for (evidence in Evidence.entries) {
            assertEquals(
                evidence.name,
                ProofLine.Kind.IDENTICAL_COPY_KEPT,
                ProofLine.forItem(evidence, isDuplicateExtra = true)
            )
        }
    }

    @Test
    fun `time alone never authorises a removal`() {
        assertFalse(ProofLine.allowsRemoval(ProofLine.Kind.TIME_ONLY))
        assertFalse(ProofLine.allowsRemoval(ProofLine.Kind.WAITING))
        assertTrue(ProofLine.allowsRemoval(ProofLine.Kind.CLOUD_REMOVED_COPY))
        assertTrue(ProofLine.allowsRemoval(ProofLine.Kind.UPLOAD_SIZE_MATCHED))
        assertTrue(ProofLine.allowsRemoval(ProofLine.Kind.IDENTICAL_COPY_KEPT))
    }

    @Test
    fun `a selection is split into what may go and what may not`() {
        val (allowed, refused) = ProofLine.partition(
            listOf(
                1L to ProofLine.Kind.CLOUD_REMOVED_COPY,
                2L to ProofLine.Kind.WAITING,
                3L to ProofLine.Kind.IDENTICAL_COPY_KEPT,
                4L to ProofLine.Kind.TIME_ONLY
            )
        )
        assertEquals(listOf(1L, 3L), allowed)
        assertEquals(listOf(2L, 4L), refused)
    }

    @Test
    fun `the tally counts each kind for the confirmation sentence`() {
        val tally = ProofLine.tally(
            listOf(
                ProofLine.Kind.CLOUD_REMOVED_COPY,
                ProofLine.Kind.CLOUD_REMOVED_COPY,
                ProofLine.Kind.UPLOAD_SIZE_MATCHED
            )
        )
        assertEquals(2, tally[ProofLine.Kind.CLOUD_REMOVED_COPY])
        assertEquals(1, tally[ProofLine.Kind.UPLOAD_SIZE_MATCHED])
        assertEquals(null, tally[ProofLine.Kind.WAITING])
    }
}
