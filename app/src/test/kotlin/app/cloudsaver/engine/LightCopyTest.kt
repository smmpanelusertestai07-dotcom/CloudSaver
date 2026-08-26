package app.cloudsaver.engine

import app.cloudsaver.core.logic.Defaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replace-with-light must be able to keep its promise even after the cloud
 * app has collected and deleted the optimised copy - the normal state of
 * affairs by the time anyone frees up space. The copy is then remade from the
 * original at the current quality setting, and NOTHING is put in front of
 * Android's removal dialog until the landed copy has been read back, hashed
 * and opened. A remade copy is the user's file in the user's album: it never
 * enters staging, the release queue, the ledger or the scanner.
 */
class LightCopyTest {

    private val engine =
        File("src/main/kotlin/app/cloudsaver/engine/ReclaimEngine.kt").readText()
    private val screen =
        File("src/main/kotlin/app/cloudsaver/ui/screens/ReclaimScreen.kt").readText()

    @Test
    fun `the light copies album is outside everything the scanner may touch`() {
        // Pure check, not a source grep: the exact path the engine writes to,
        // in every shape MediaStore reports it.
        assertTrue(Defaults.isAppOwnedPath(Defaults.KEPT_DIR))
        assertTrue(Defaults.isAppOwnedPath("${Defaults.KEPT_DIR}/"))
        assertTrue(Defaults.isAppOwnedPath("${Defaults.KEPT_DIR}/anything/"))
        assertFalse(Defaults.isAppOwnedPath("Pictures/Light copies backup/"))
    }

    @Test
    fun `a missing local copy is remade from the original with the current preset`() {
        val source = engine.substringAfter("private suspend fun pinSource")
            .substringBefore("private suspend fun writeVerified")
        assertTrue(
            "the remake must use the current quality setting",
            source.contains("Presets.spec(options.preset)")
        )
        assertTrue(source.contains("PhotoCompressor.compress"))
        assertTrue(source.contains("VideoCompressor.compress"))
        assertTrue(
            "a gone original means nothing to remake from - never a guess",
            source.contains("if (row.originalMissing) return null")
        )
    }

    @Test
    fun `every source of bytes is verified by hash before it is trusted`() {
        val source = engine.substringAfter("private suspend fun pinSource")
            .substringBefore("private suspend fun writeVerified")
        // The stage file and the folder copy are only accepted when they
        // still hash to what was recorded at stage time.
        assertTrue(source.contains("sha == recorded"))
        // Two empty files hash identically; that match proves nothing.
        assertTrue(engine.contains("EMPTY_SHA256"))
    }

    @Test
    fun `the landed copy is read back, hashed and opened before publish`() {
        val write = engine.substringAfter("private suspend fun writeVerified")
            .substringBefore("private fun mimeForName")
        val verifyAt = write.indexOf("landed != src.sha256")
        val decodeAt = write.indexOf("looksDecodable")
        val publishAt = write.indexOf("put(MediaStore.MediaColumns.IS_PENDING, 0)")
        val recordAt = write.indexOf("keptUri = target.toString()")
        assertTrue("read-back hash must exist", verifyAt >= 0)
        assertTrue("decode probe must exist", decodeAt >= 0)
        assertTrue("publish must exist", publishAt >= 0)
        assertTrue(
            "verification must come before the copy is published or recorded",
            verifyAt < publishAt && decodeAt < publishAt && publishAt < recordAt
        )
        assertTrue(
            "a failed verification must delete the broken row",
            write.contains("resolver.delete(target")
        )
    }

    @Test
    fun `the removal request is only built after every copy is proved`() {
        // In the engine: a row whose copy cannot be made drops out, named,
        // before its original ever reaches the uris handed to Android.
        val prepare = engine.substringAfter("suspend fun prepare")
            .substringBefore("suspend fun finish")
        val failAt = prepare.indexOf("light_copy_failed")
        val addAt = prepare.indexOf("uris += original")
        assertTrue(failAt >= 0 && addAt >= 0 && failAt < addAt)
        assertTrue(
            "the failed row must leave the batch",
            prepare.substring(failAt, addAt).contains("continue")
        )
        // In the view model: prepare() runs before any consent is asked for.
        val vm = File("src/main/kotlin/app/cloudsaver/ui/ReclaimViewModel.kt").readText()
        val start = vm.substringAfter("fun start(permanent")
        val preparedAt = start.indexOf("engine.prepare")
        val askedAt = start.indexOf("beginConsent(ready.uris")
        assertTrue("start() no longer prepares the batch", preparedAt >= 0)
        assertTrue("start() no longer asks for consent", askedAt >= 0)
        assertTrue("consent is asked for before the copies are proved", preparedAt < askedAt)
    }

    @Test
    fun `a remade copy never enters the pipeline`() {
        val region = engine.substringAfter("suspend fun pinLightCopy")
            .substringBefore("suspend fun removeCopiesOnly")
        // The only database write in the whole pin path is the keptUri stamp.
        val writes = Regex("""db\.items\(\)\.update""").findAll(region).count()
        assertEquals("pinning may record keptUri and nothing else", 1, writes)
        assertTrue(region.contains("keptUri = target.toString()"))
        for (banned in listOf("ItemState.", "stagePath =", "outputName =",
                "outputUri =", "Releaser", "insertBatch", "ledger()")) {
            assertFalse(
                "the pin path must not touch the pipeline ($banned)",
                region.contains(banned)
            )
        }
        assertTrue(
            "the copy lands in the user's album, nowhere else",
            region.contains("Defaults.KEPT_DIR")
        )
    }

    @Test
    fun `a dropped item is named on screen, with the reason`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("name=\"skip_light_copy_failed\""))
        assertTrue(strings.contains("name=\"reclaim_mode_replace_note\""))
        assertTrue(screen.contains("skipReasonLabel"))
        assertTrue(screen.contains("reclaim_mode_replace_note"))
    }
}
