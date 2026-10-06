package app.entesaver.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trial card shows the trial, not a list of file names.
 *
 * The owner's phone showed three wrapped names and two numbers, with no
 * way to see the result and no way to be rid of the copies once seen. Each
 * row now carries the picture, opens the before-and-after, and the card
 * says where the copies are and offers to remove them.
 */
class TrialCardTest {

    private val main = File("src/main/kotlin/app/entesaver/ui")
    private val card = File(main, "components/TrialCard.kt").readText()
    private val home = File(main, "screens/HomeScreen.kt").readText()

    @Test
    fun `each trial row shows the picture and opens the comparison`() {
        assertTrue(card.contains("private fun TrialRow("))
        assertTrue("the row loads the item's own thumbnail", card.contains("trialThumb(item.row?.contentUri)"))
        assertTrue("the name is shortened in the middle, never wrapped", card.contains("TextOverflow.MiddleEllipsis"))
        assertTrue("the row says it can be tapped", card.contains("R.string.trial_tap_compare"))
        assertTrue("Home opens the comparison sheet", home.contains("onOpen = { item -> compare = item.row }"))
        assertTrue(home.contains("CompareSheet(row = row, onDismiss = { compare = null })"))
    }

    @Test
    fun `the copies can be removed, and removing them puts the items back`() {
        assertTrue("the card says the copies go away by themselves", card.contains("R.string.trial_goes_away"))
        assertTrue("and offers to remove them now", card.contains("R.string.trial_discard"))
        assertTrue(home.contains("onDiscard = { vm.discardTrial() }"))
        val discard = File(main, "AppViewModel.kt").readText()
            .substringAfter("fun discardTrial()").substringBefore("\n    }\n")
        assertTrue("only a staged trial copy is touched", discard.contains("current.state != ItemState.STAGED.name"))
        assertTrue("the copy is deleted", discard.contains("File(path).delete()"))
        assertTrue("the item goes back to new", discard.contains("state = ItemState.NEW.name"))
        for (cleared in listOf("stagePath = null", "outputName = null", "outputBytes = null", "outputSha256 = null")) {
            assertTrue("$cleared, or the next run trusts a file that is gone", discard.contains(cleared))
        }
        // Left behind, the pixel counts kept "detail kept" counting copies
        // that had been thrown away.
        assertTrue(discard.contains("srcPixels = 0") && discard.contains("outPixels = 0"))
        assertTrue(
            "never while a run is publishing the same file",
            discard.contains("Locks.release.withLock {")
        )
        assertTrue("the card goes away", discard.contains("trialIds.value = emptySet()"))
        assertTrue("and stays gone after a restart", discard.contains("TrialRecord.write(ctx, emptySet())"))
    }

    @Test
    fun `the trial outlives the process and runs once`() {
        val vm = File(main, "AppViewModel.kt").readText()
        // In memory only, the card and its Remove button vanished whenever
        // the phone closed the app in the background, while the copies
        // stayed inside it with nothing left that could remove them.
        assertTrue(vm.contains("viewModelScope.launch(Dispatchers.IO) { ids.value = TrialRecord.read(ctx) }"))
        val run = vm.substringAfter("fun startTestRun()").substringBefore("\n    }\n")
        assertTrue(run.contains("TrialRecord.write(ctx, ids)"))
        assertTrue(
            "a second trial would orphan the first one's copies",
            run.contains("if (testRunning.value || trialIds.value.isNotEmpty()) return")
        )
        assertTrue(
            "and the card offers no second run once it has results",
            card.contains("} else if (results.isNullOrEmpty()) {")
        )
        // The card shows what is still inside the app: once a real run has
        // moved the copies to the upload folder it has nothing left to say.
        assertTrue(vm.contains("rows.filter { it.state == ItemState.STAGED.name && it.stagePath != null }"))
        val setup = File(main, "screens/OnboardingScreen.kt").readText()
        assertTrue("setup opens a result too", setup.contains("onOpen = { item -> compare = item.row }"))
        assertTrue("and can remove the copies it made", setup.contains("onDiscard = { vm.discardTrial() }"))
    }
}
