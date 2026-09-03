package app.cloudsaver.ui

import app.cloudsaver.core.logic.ReclaimRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No confirmation dialog is ever handed the whole selection.
 *
 * Android documents no maximum for a trash or delete request, so nothing
 * fails loudly when one is too big: the URIs travel into a PendingIntent
 * through a binder transaction with a size ceiling, and a dialog listing a
 * thousand filenames is not a confirmation anybody reads before agreeing to
 * it. Both are silent failures, one technical and one human, so the split
 * into chunks is structural and this test keeps it that way.
 */
class ConsentBatchTest {

    private val ui = File("src/main/kotlin/app/cloudsaver/ui")
    private val main = File("src/main/kotlin/app/cloudsaver")

    /**
     * Everything that produces a removal dialog: the two system calls, and
     * the helper that wraps them. The helper counts, because a caller that
     * hands it the whole selection is the same unbounded request with one
     * more function in between - which is exactly how the duplicates path
     * stayed unbounded after the first three were fixed.
     */
    private val dialogCalls = Regex(
        """MediaStore\.create(Trash|Delete)Request\(|(?<!fun )\brequestFor\("""
    )

    /** The functions allowed to make one, each of which receives a chunk. */
    private val allowed = setOf(
        "requestFor", "requestNextConsent", "nextDuplicateChunk",
        "nextRestoreChunk", "nextDeleteChunk"
    )

    /** Name of the function a given line sits inside. */
    private fun enclosingFun(lines: List<String>, index: Int): String {
        for (i in index downTo 0) {
            val m = Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(""").find(lines[i])
            if (m != null) return m.groupValues[1]
        }
        return "<top level>"
    }

    @Test
    fun `every removal dialog is built from a chunk, never from the selection`() {
        val sites = mutableListOf<String>()
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                if (dialogCalls.containsMatchIn(line)) {
                    sites += "${file.name}:${enclosingFun(lines, i)}"
                }
            }
        }
        assertTrue("no consent dialog is built anywhere - the flow is gone", sites.isNotEmpty())
        val strays = sites.filter { it.substringAfter(':') !in allowed }
        assertTrue(
            "these build a dialog outside the chunked path: $strays",
            strays.isEmpty()
        )
    }

    @Test
    fun `each entry point splits its selection before asking for anything`() {
        // Four flows can be handed an unbounded list by the UI: removing
        // originals, putting them back, clearing the extra copies, and
        // clearing leftover work files.
        val entries = listOf(
            Triple("ReclaimViewModel.kt", "beginConsent", "originals"),
            Triple("ReclaimViewModel.kt", "restore", "restore from the trash"),
            Triple("ReclaimViewModel.kt", "removeDuplicateExtras", "duplicate extras"),
            Triple("AppViewModel.kt", "requestDelete", "leftover files")
        )
        for ((fileName, function, what) in entries) {
            val source = File(ui, fileName).readText()
            val body = source.substringAfter("fun $function(", "")
            assertTrue("$fileName has no $function() any more", body.isNotEmpty())
            // Code, not prose. The window exists to say "the split happens
            // early, before anything is confirmed" - counting comment
            // characters towards it means a maintainer who documents the rule
            // breaks it, which is how this fired on a three-line comment while
            // ReclaimRules.batches() sat right where it always had.
            val head = body.lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
                .take(1400)
            assertTrue(
                "$what is confirmed without being split first ($function)",
                head.contains("ReclaimRules.batches(")
            )
        }
    }

    @Test
    fun `a refusal part way through keeps only what was actually agreed`() {
        // The old code assumed the whole batch went through the moment the
        // dialog returned OK. With several dialogs that assumption writes
        // down deletions that never happened.
        val reclaim = File(ui, "ReclaimViewModel.kt").readText()
        assertTrue(
            "the reclaim result must be built from the confirmed chunks",
            reclaim.contains("val deleted = consentConfirmed.toSet()")
        )
        assertTrue(
            "history must only mark back the chunks the user allowed",
            reclaim.contains("items.filter { it.contentUri in restoreGranted }")
        )
        val app = File(ui, "AppViewModel.kt").readText()
        assertTrue(
            "the delete callback must receive the agreed chunks",
            app.contains("finishDelete(agreed)")
        )
        assertTrue(
            "the duplicate count must be what the user allowed, not what was offered",
            reclaim.contains("val count = dupeGranted.size")
        )
        assertTrue(
            "a finished flow must not leave a sender to be relaunched",
            app.substringAfter("fun onDeleteDialogResult(").take(300)
                .contains("deleteIntent.value = null")
        )
    }

    @Test
    fun `the chunk size is the app's own choice and does not claim to be Android's`() {
        val rules = File(main, "core/logic/ReclaimRules.kt").readText()
        val doc = rules.substringBefore("const val MAX_URIS_PER_REQUEST")
            .substringAfterLast("/**")
        val falseClaims = Regex(
            """(?i)(MediaStore|Android|the (platform|system)) (refuses|rejects|caps|limits|allows)""" +
                """|(maximum|limit) (imposed|set|defined) by"""
        )
        assertTrue(
            "the comment states a platform rule that Android does not document",
            !falseClaims.containsMatchIn(doc)
        )
        assertTrue(
            "a chunk has to be small enough to read and big enough to be worth it",
            ReclaimRules.MAX_URIS_PER_REQUEST in 100..1000
        )
    }

    @Test
    fun `splitting never loses, reorders or oversizes a selection`() {
        for (count in listOf(0, 1, 499, 500, 501, 1000, 4321)) {
            val input = (1..count).map { "uri$it" }
            val chunks = ReclaimRules.batches(input)
            assertEquals("nothing may be lost", input, chunks.flatten())
            assertTrue(
                "a chunk went over the size at count=$count",
                chunks.all { it.size <= ReclaimRules.MAX_URIS_PER_REQUEST }
            )
            assertTrue("an empty chunk asks for nothing", chunks.none { it.isEmpty() })
        }
        assertTrue("an empty selection asks nothing", ReclaimRules.batches(emptyList<String>()).isEmpty())
        // A nonsense size must not spin forever building empty chunks.
        assertEquals(3, ReclaimRules.batches(listOf("a", "b", "c"), 0).size)
    }
}
