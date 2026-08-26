package app.cloudsaver

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import app.cloudsaver.core.logic.OnboardingSteps
import app.cloudsaver.core.logic.OnboardingSteps.Step
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.media.OutputInventory
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.util.Permissions
import app.cloudsaver.work.Scheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The setup flow, driven end to end on a real device.
 *
 * Every control is found by the string the app itself ships, and every step is
 * asserted rather than photographed: if a label moves, a card stops rendering
 * or the header disagrees with [OnboardingSteps], the test fails here instead
 * of on someone's phone. Two of the cases below are regressions that were
 * reported by real users - the album detour that dumped people back at step 3,
 * and the album tick that did not survive a rotation.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingE2eTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.forThisDevice())

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    private var scenario: ActivityScenario<MainActivity>? = null

    /** The album MediaFixtures writes into, as the picker will label it. */
    private val fixtureAlbum: String get() = MediaFixtures.TEST_ALBUM.substringAfterLast('/')

    private fun s(id: Int): String = context.getString(id)

    // ---- lifecycle ---------------------------------------------------------

    @Before
    fun setUp() {
        MediaFixtures.cleanUp(context)
        clearOutputFolder()
        runBlocking {
            AppDb.get(context).clearAllTables()
            resetSetupState()
        }
        // The album step, the summary line and the trial card all say
        // something different when the gallery is empty, so setup is driven
        // against a gallery that really has an album in it.
        MediaFixtures.insertPhoto(
            context,
            name = "onboarding_fixture.jpg",
            seed = 11,
            captureMillis = 1_600_000_000_000L
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        // Finishing setup schedules real background work; leaving it running
        // would have it scan and stage during whatever test runs next.
        Scheduler.cancelAll(context)
        MediaFixtures.cleanUp(context)
        clearOutputFolder()
        runBlocking {
            AppDb.get(context).clearAllTables()
            resetSetupState()
        }
    }

    private suspend fun resetSetupState() {
        val repo = OptionsRepo.get(context)
        repo.setBool(OptionsRepo.K.ONBOARDING_DONE, false)
        repo.setInt(OptionsRepo.K.ONBOARDING_STEP, 0)
        repo.setStringSet(OptionsRepo.K.EXCLUDED_BUCKETS, emptySet())
    }

    private fun clearOutputFolder() {
        for (entry in OutputInventory(context).query().orEmpty()) {
            runCatching { context.contentResolver.delete(entry.uri, null, null) }
        }
        runCatching { File(context.getExternalFilesDir(null), "stage").deleteRecursively() }
    }

    private fun launch(): ActivityScenario<MainActivity> {
        scenario?.close()
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        compose.waitForIdle()
        return launched
    }

    private fun stored(): Options = runBlocking { OptionsRepo.get(context).current() }

    // ---- the step model, read from the app's own list -----------------------

    private fun titleOf(step: Step): String = s(
        when (step) {
            Step.WELCOME -> R.string.onb0_title
            Step.MEDIA -> R.string.onb1_title
            Step.ALBUMS -> R.string.onb_albums_title
            Step.NOTIFICATIONS -> R.string.onb2_title
            Step.BATTERY -> R.string.onb3_title
            Step.USAGE -> R.string.onb4_title
            Step.CLOUD -> R.string.onb5_title
            Step.READY -> R.string.onb_ready_title
        }
    )

    private fun counterOf(step: Step): String = context.getString(
        R.string.onb_step_counter,
        OnboardingSteps.humanNumber(step),
        OnboardingSteps.TOTAL
    )

    /**
     * Waits for [step] to be the one on screen, and checks the three things
     * that must hold on every card: the header counts this step the way
     * [OnboardingSteps] does, the position is stated exactly once, and the
     * card does not number itself in its own title.
     */
    private fun awaitStep(step: Step) {
        val counter = counterOf(step)
        val title = titleOf(step)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = STEP_TIMEOUT) {
            compose.onAllNodesWithText(counter).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithText(title).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(counter).performScrollTo().assertIsDisplayed()
        assertFalse(
            "the card for $step numbers itself in its title: \"$title\"",
            title.any(Char::isDigit)
        )
        // Where setup is has to survive the app being killed, so the stored
        // position must follow the visible one on every move.
        compose.waitUntil(timeoutMillis = STORE_TIMEOUT) {
            stored().onboardingStep == OnboardingSteps.indexOf(step)
        }
        assertEquals(
            "setup did not remember that it is on $step",
            OnboardingSteps.indexOf(step),
            stored().onboardingStep
        )
    }

    private fun tap(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * The control that moves this step on by exactly one, without leaving the
     * app. Where the label depends on device state the precondition is
     * asserted first, so a wrong label is reported as the wrong label rather
     * than as a missing node.
     */
    private fun advanceFrom(step: Step) {
        when (step) {
            Step.WELCOME -> tap(s(R.string.onb_start))

            Step.MEDIA -> {
                assertEquals(
                    "the permission rule must give this test full media access",
                    Permissions.MediaAccess.FULL,
                    Permissions.mediaAccess(context)
                )
                tap(s(R.string.onb_done_next))
            }

            Step.ALBUMS -> tap(s(R.string.onb_albums_confirm))

            Step.NOTIFICATIONS -> {
                assertTrue(
                    "notifications must already be granted or this step opens a dialog",
                    Permissions.hasNotifications(context)
                )
                tap(s(R.string.onb2_grant))
            }

            Step.BATTERY -> tap(s(R.string.onb_done_next))

            // The primary button here opens the system usage-access page; the
            // card's own "Done, next" is the one that stays in the app.
            Step.USAGE -> tap(s(R.string.onb_done_next))

            Step.CLOUD -> tap(s(R.string.onb_done_next))

            Step.READY -> tap(s(R.string.onb_ready_start))
        }
    }

    /** Walks from the first card to [target], asserting every card on the way. */
    private fun walkTo(target: Step) {
        var current = OnboardingSteps.ALL.first()
        awaitStep(current)
        while (current != target) {
            advanceFrom(current)
            current = OnboardingSteps.next(current)
            awaitStep(current)
        }
    }

    /**
     * Waits for the album list to arrive and returns it in the order the
     * picker renders it - which is the order [MediaScanner.buckets] returns,
     * the same call the screen makes.
     */
    private fun awaitAlbums(): List<String> {
        val albums = MediaScanner(context, AppDb.get(context)).buckets()
        assertTrue(
            "the fixture album must be offered in the picker, got $albums",
            fixtureAlbum in albums
        )
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT) {
            compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size == albums.size
        }
        compose.onNodeWithText(s(R.string.folders_loading)).assertDoesNotExist()
        compose.onNodeWithText(fixtureAlbum).assertExists()
        return albums
    }

    /** The tick box on the row for [album]. */
    private fun albumTick(albums: List<String>, album: String) =
        compose.onAllNodes(isToggleable())[albums.indexOf(album)]

    // ---- the steps themselves ----------------------------------------------

    /**
     * Every card renders, and the header agrees with the one list of steps.
     *
     * "Step 6 of 7" once sat above a card headed "5." because the header
     * counted the loop index while the copy carried its own number. Both are
     * checked against [OnboardingSteps] here, on every card.
     */
    @Test
    fun everyStepRendersAndItsHeaderAgreesWithTheStepList() {
        launch()
        var current = OnboardingSteps.ALL.first()
        for ((index, step) in OnboardingSteps.ALL.withIndex()) {
            assertEquals(
                "the step list and the header cannot disagree",
                index + 1,
                OnboardingSteps.humanNumber(step)
            )
            assertEquals("walked off the step list", step, current)
            awaitStep(step)
            if (!OnboardingSteps.isLast(step)) {
                advanceFrom(step)
                current = OnboardingSteps.next(step)
            }
        }
        // The walk really did end on the summary, with the tap that starts
        // everything and nothing before it.
        compose.onNodeWithText(s(R.string.onb_ready_start))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        assertFalse("nothing may start before the summary is confirmed", stored().onboardingDone)
    }

    /** Back walks the steps in reverse, one at a time, from the very last one. */
    @Test
    fun backFromEveryStepReturnsToTheOneBefore() {
        launch()
        walkTo(Step.READY)
        var current = Step.READY
        while (current != OnboardingSteps.ALL.first()) {
            val previous = OnboardingSteps.previous(current)
            tap(s(R.string.back))
            awaitStep(previous)
            current = previous
        }
    }

    /**
     * The first card has nowhere to go back to, so it offers no Back control
     * and the system gesture leaves the app rather than looping.
     */
    @Test
    fun theFirstStepHasNoBackControlAndSystemBackLeavesSetup() {
        val launched = launch()
        awaitStep(Step.WELCOME)
        compose.onNodeWithText(s(R.string.back)).assertDoesNotExist()

        device.pressBack()
        device.waitForIdle()
        val deadline = System.currentTimeMillis() + STEP_TIMEOUT
        while (System.currentTimeMillis() < deadline &&
            launched.state != Lifecycle.State.DESTROYED
        ) {
            device.waitForIdle()
        }
        assertEquals(
            "Back on the first setup card must leave the app, not trap the user",
            Lifecycle.State.DESTROYED,
            launched.state
        )
        // Nothing was decided, so nothing was recorded.
        assertFalse(stored().onboardingDone)
        assertEquals(0, stored().onboardingStep)
    }

    /**
     * The reported bug: correcting the albums from the summary used to drop
     * the user back at the album step for good, making them press through
     * notifications, battery, usage access and the cloud card a second time.
     * The detour must return to the summary.
     */
    @Test
    fun choosingAlbumsFromTheSummaryReturnsToTheSummary() {
        launch()
        walkTo(Step.ALBUMS)
        val albums = awaitAlbums()

        // Reached forwards, the album card is an ordinary step.
        compose.onNodeWithText(s(R.string.onb_albums_confirm)).assertExists()
        compose.onNodeWithText(s(R.string.onb_albums_back_to_summary)).assertDoesNotExist()

        // Untick everything, so the summary has something to complain about.
        tap(s(R.string.onb_albums_none))
        compose.waitUntil(timeoutMillis = STORE_TIMEOUT) {
            stored().excludedBuckets.containsAll(albums)
        }

        advanceFrom(Step.ALBUMS)
        walkThroughRemainingStepsFrom(Step.NOTIFICATIONS)
        awaitStep(Step.READY)

        // The summary says the setup would do nothing, and offers the fix.
        compose.onNodeWithText(s(R.string.onb_ready_no_albums))
            .performScrollTo()
            .assertIsDisplayed()
        val choose = compose.onAllNodesWithText(s(R.string.onb_ready_pick_albums))
        assertTrue(
            "the summary must offer a way back to the album list",
            choose.fetchSemanticsNodes().isNotEmpty()
        )
        choose[0].performScrollTo().performClick()

        // On the album list, and it says so: the button promises the summary.
        awaitStep(Step.ALBUMS)
        awaitAlbums()
        compose.onNodeWithText(s(R.string.onb_albums_back_to_summary))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onb_albums_confirm)).assertDoesNotExist()

        tap(s(R.string.onb_albums_all))
        tap(s(R.string.onb_albums_back_to_summary))

        // The whole point: back at the summary, not three steps earlier.
        awaitStep(Step.READY)
        compose.onNodeWithText(s(R.string.onb_ready_no_albums)).assertDoesNotExist()
        compose.waitUntil(timeoutMillis = STORE_TIMEOUT) {
            stored().excludedBuckets.isEmpty()
        }
    }

    /**
     * Leaving the detour any other way has to cancel it, or the next ordinary
     * visit to the album list would jump to the summary and skip four cards
     * the user had never seen.
     */
    @Test
    fun leavingTheAlbumDetourWithBackCancelsThePromiseToReturn() {
        launch()
        walkTo(Step.ALBUMS)
        val albums = awaitAlbums()
        tap(s(R.string.onb_albums_none))
        compose.waitUntil(timeoutMillis = STORE_TIMEOUT) {
            stored().excludedBuckets.containsAll(albums)
        }
        advanceFrom(Step.ALBUMS)
        walkThroughRemainingStepsFrom(Step.NOTIFICATIONS)
        awaitStep(Step.READY)
        compose.onAllNodesWithText(s(R.string.onb_ready_pick_albums))[0]
            .performScrollTo()
            .performClick()
        awaitStep(Step.ALBUMS)
        compose.onNodeWithText(s(R.string.onb_albums_back_to_summary)).assertExists()

        // Back out of the detour: the album card is an ordinary step again.
        tap(s(R.string.back))
        awaitStep(Step.MEDIA)
        advanceFrom(Step.MEDIA)
        awaitStep(Step.ALBUMS)
        compose.onNodeWithText(s(R.string.onb_albums_confirm)).assertExists()
        compose.onNodeWithText(s(R.string.onb_albums_back_to_summary)).assertDoesNotExist()
    }

    /**
     * The other reported bug: a tick survives the activity being recreated -
     * a rotation, a theme change, or Android rebuilding the process.
     */
    @Test
    fun aTickedAlbumStaysTickedAcrossActivityRecreation() {
        val launched = launch()
        walkTo(Step.ALBUMS)
        val albums = awaitAlbums()

        // Start from nothing ticked, then tick exactly the fixture album, so
        // the assertion is about a tick the user made and not about a default.
        tap(s(R.string.onb_albums_none))
        compose.waitUntil(timeoutMillis = STORE_TIMEOUT) {
            stored().excludedBuckets.containsAll(albums)
        }
        albumTick(albums, fixtureAlbum).assertIsOff()

        albumTick(albums, fixtureAlbum).performScrollTo().performClick()
        albumTick(albums, fixtureAlbum).assertIsOn()
        compose.waitUntil(timeoutMillis = STORE_TIMEOUT) {
            fixtureAlbum !in stored().excludedBuckets
        }

        launched.recreate()
        compose.waitForIdle()

        awaitStep(Step.ALBUMS)
        val afterAlbums = awaitAlbums()
        albumTick(afterAlbums, fixtureAlbum).assertIsOn()
        assertFalse(
            "the ticked album came back excluded after recreation",
            fixtureAlbum in stored().excludedBuckets
        )
        // And the albums the user left alone stayed left alone.
        for (album in afterAlbums.filter { it != fixtureAlbum }) {
            albumTick(afterAlbums, album).assertIsOff()
        }
    }

    /**
     * Finishing setup is remembered: the app opens on Home afterwards, not on
     * the welcome card.
     */
    @Test
    fun finishingSetupPersistsAndTheAppOpensOnHome() {
        launch()
        walkTo(Step.READY)
        assertFalse(stored().onboardingDone)

        tap(s(R.string.onb_ready_start))
        compose.waitUntil(timeoutMillis = STORE_TIMEOUT) { stored().onboardingDone }
        awaitHome()

        // The real check: a fresh activity, from cold.
        launch()
        awaitHome()
        compose.onNodeWithText(titleOf(Step.WELCOME)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.onb_tagline)).assertDoesNotExist()
        assertTrue(stored().onboardingDone)
    }

    /**
     * "Try it on a few photos" is the cheapest possible answer to "what will
     * this do to my photos", so it has to be on the summary and usable
     * whenever the app can actually read the gallery.
     */
    @Test
    fun theTrialIsOfferedAndEnabledWhenMediaAccessIsFull() {
        launch()
        walkTo(Step.READY)
        assertEquals(
            Permissions.MediaAccess.FULL,
            Permissions.mediaAccess(context)
        )

        compose.onNodeWithText(s(R.string.trial_title)).performScrollTo().assertIsDisplayed()
        // Neither of the two cards that replace the offer may be showing.
        compose.onNodeWithText(s(R.string.trial_needs_access)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.trial_needs_albums)).assertDoesNotExist()

        // The button names the number it can really deliver, so accept any of
        // the labels the card is allowed to end up with.
        val runLabels = buildList {
            add(s(R.string.trial_run))
            for (n in 1..AppViewModel.TRIAL_SIZE) {
                add(context.resources.getQuantityString(R.plurals.trial_action, n, n))
            }
        }
        compose.waitUntil(timeoutMillis = STEP_TIMEOUT) {
            runLabels.any {
                compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val shown = runLabels.first {
            compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(shown)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
    }

    // ---- helpers -----------------------------------------------------------

    /** Presses on from [from] to the summary, asserting each card. */
    private fun walkThroughRemainingStepsFrom(from: Step) {
        var current = from
        awaitStep(current)
        while (!OnboardingSteps.isLast(current)) {
            advanceFrom(current)
            current = OnboardingSteps.next(current)
            awaitStep(current)
        }
    }

    private fun awaitHome() {
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT) {
            compose.onAllNodesWithText(s(R.string.nav_home)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(s(R.string.nav_home)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.nav_storage)).assertIsDisplayed()
    }

    private companion object {
        const val STEP_TIMEOUT = 10_000L
        const val STORE_TIMEOUT = 10_000L
        const val LOAD_TIMEOUT = 30_000L
    }
}
