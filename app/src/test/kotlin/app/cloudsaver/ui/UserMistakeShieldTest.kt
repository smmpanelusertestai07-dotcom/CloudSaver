package app.cloudsaver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The user-mistake shield: every warning that protects someone from their own
 * reasonable-but-wrong move exists in resources AND is wired to a screen.
 *
 * A string nobody renders and a card whose string was deleted both pass a
 * compile; each has shipped somewhere as "we warned about that". One test
 * holds both ends of every warning so neither can drift away alone.
 */
class UserMistakeShieldTest {

    private val strings = File("src/main/res/values/strings.xml").readText()
    private fun screen(name: String) =
        File("src/main/kotlin/app/cloudsaver/ui/screens/$name").readText()

    // ---- foreign files in the upload folder (DD2.1) -------------------------

    @Test
    fun `foreign files are counted, shown and explained - never touched`() {
        // Resources: the chip, the Activity note, the FAQ line.
        assertTrue(strings.contains("name=\"chip_foreign\""))
        assertTrue(strings.contains("name=\"foreign_files_note\""))
        assertTrue(
            "faq_a2 must promise the app never touches files it did not create",
            strings.contains("never moves, renames or removes a file it did not create")
        )

        // Wiring: the engine counts on every pass, Home shows the chip.
        val engine = File("src/main/kotlin/app/cloudsaver/engine/MaintainEngine.kt").readText()
        assertTrue(engine.contains("step(\"foreign\")"))
        assertTrue(engine.contains("!ScanSources.isPipelineName"))
        val foreignFn = engine.substringAfter("private suspend fun foreignFiles")
            .substringBefore("private suspend fun")
        assertFalse(
            "the foreign-files pass must have no way to act on the files",
            foreignFn.contains("delete") || foreignFn.contains("update(") ||
                foreignFn.contains(".uri")
        )
        val home = screen("HomeScreen.kt")
        assertTrue(home.contains("chip_foreign"))
        assertTrue(
            "the chip must lead to the explanation",
            home.substringAfter("chip_foreign").substringBefore("}")
                .contains("HELP_FAQ")
        )
    }

    @Test
    fun `old-install cleanup can only ever offer this pipeline's own leftovers`() {
        val vm = File("src/main/kotlin/app/cloudsaver/ui/AppViewModel.kt").readText()
        // The shipped hole: `fp == null` (a name this app never writes -
        // i.e. the user's own file) landed in the Remove-button card.
        assertTrue(
            "only pipeline-named files may be offered for cleanup",
            vm.contains("fp != null && fp !in knownFps")
        )
        assertFalse(vm.contains("fp == null || fp !in knownFps"))
    }

    // ---- the free-up blind spot (DD2.2) -------------------------------------

    @Test
    fun `the free-up confirmation always carries the check-your-cloud warning`() {
        assertTrue(strings.contains("name=\"reclaim_blindspot\""))
        assertTrue(strings.contains("name=\"reclaim_blindspot_generic\""))
        assertTrue(strings.contains("name=\"reclaim_open_cloud\""))

        val reclaim = screen("ReclaimScreen.kt")
        assertTrue(reclaim.contains("reclaim_blindspot"))
        assertTrue(
            "the sheet must offer the door, not just the advice",
            reclaim.contains("CloudApps.launch")
        )
        assertTrue(
            "every original-removing batch must pass through the sheet",
            reclaim.contains("mode != ReclaimRules.Mode.COPIES_ONLY ||")
        )
    }

    // ---- kept light copies (DD2.3) ------------------------------------------

    @Test
    fun `kept copies explain themselves once, and the double-copy trap always`() {
        assertTrue(strings.contains("name=\"kept_card_title\""))
        assertTrue(strings.contains("name=\"kept_card_body\""))
        assertTrue(strings.contains("name=\"kept_intro_backup\""))
        assertTrue(
            "faq_a11 must name the double-copy trap",
            strings.contains("backing the album up would store it twice")
        )

        val kept = screen("KeptCopiesScreen.kt")
        assertTrue(kept.contains("kept_card_title"))
        assertTrue(kept.contains("acknowledgeKeptCard"))
        assertTrue(kept.contains("kept_intro_backup"))
        val vm = File("src/main/kotlin/app/cloudsaver/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("KEPT_CARD_SEEN"))
    }

    // ---- the warnings that were already there (DD2.4) -----------------------

    @Test
    fun `the standing warnings did not quietly disappear`() {
        // (a) the camera-folder question during setup
        assertTrue(strings.contains("name=\"double_backup_title\""))
        // (b) a quality change applies to new items only
        assertTrue(strings.contains("name=\"applies_to_new_only\""))
        assertTrue(File("src/main/kotlin/app/cloudsaver/ui/screens/HelpScreens.kt")
            .readText().contains("applies_to_new_only"))
        // (c) files the cloud app downloads back are recognised, not re-done
        assertTrue(
            "faq_a7 must cover copies the cloud downloads back",
            strings.contains("downloads a copy back")
        )
    }

    // ---- the history that keeps itself (DD1) --------------------------------

    @Test
    fun `settings says first that the history saves itself`() {
        assertTrue(strings.contains("name=\"opt_history_auto\""))
        assertTrue(
            "the line must name both shared paths",
            strings.contains("Download/.cloudsaver") &&
                strings.contains("Documents/.cloudsaver")
        )
        assertTrue(screen("OptionsScreen.kt").contains("opt_history_auto"))
        assertTrue(
            "faq_a14 must say the manual backup is only an extra copy",
            strings.contains("only for an extra copy")
        )
    }
}
