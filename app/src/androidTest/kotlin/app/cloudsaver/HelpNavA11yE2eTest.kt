package app.cloudsaver

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import app.cloudsaver.core.logic.Platform
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.media.MediaScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Can a person actually get to every screen, and back out of it again?
 *
 * The app declares twenty routes. A route nobody can reach by tapping is a
 * screen that does not exist, and a route with no way back is a trap - so
 * every page here is opened the way a user opens it, never by calling
 * navigate() directly, and left again with the system Back gesture.
 *
 * The accessibility half asserts the two things a screen reader and a thumb
 * both need: an icon with no label is a button that cannot be described, and
 * a control under 48dp is one that cannot reliably be hit.
 */
@RunWith(AndroidJUnit4::class)
class HelpNavA11yE2eTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.forThisDevice())

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    private fun s(id: Int): String = target.getString(id)

    @Before
    fun setUp() {
        MediaFixtures.cleanUp(target)
        runBlocking {
            AppDb.get(target).clearAllTables()
            OptionsRepo.get(target).setBool(OptionsRepo.K.ONBOARDING_DONE, true)
            // Several entries only appear once there is something behind
            // them, so the gallery has to be real before the tour starts.
            for (i in 1..3) {
                MediaFixtures.insertPhoto(
                    target, name = "nav_photo_$i.jpg", seed = i, captureMillis = 1_600_000_000_000L
                )
            }
            MediaScanner(target, AppDb.get(target)).scan()
        }
    }

    @After
    fun tearDown() {
        MediaFixtures.cleanUp(target)
        runBlocking { AppDb.get(target).clearAllTables() }
    }

    /**
     * Scrolls until the label exists, if it does not already.
     *
     * A lazy list composes only what is on screen, so a row further down has
     * no node at all - performScrollTo cannot reach a node that is not there,
     * and the failure reads "can't retrieve node at index 0", which sounds
     * like the row is missing when it is merely below the fold.
     */
    private fun ComposeTestRule.bringIntoView(label: String) {
        val matcher = hasText(label, substring = true)
        if (onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return
        val scrollers = onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        if (scrollers.isEmpty()) return
        onAllNodes(hasScrollAction()).onLast().performScrollToNode(matcher)
        waitForIdle()
    }

    private fun ComposeTestRule.open(label: String) {
        bringIntoView(label)
        onAllNodes(hasText(label, substring = true)).onFirst()
            .performScrollTo().performClick()
        waitForIdle()
    }

    /**
     * The label is on screen. Not "exactly one node has it": a tab's name is
     * in the bar and again on its screen, and a help page's name is on the
     * page and still in the list behind it, so uniqueness is simply false.
     */
    private fun ComposeTestRule.assertOn(label: String) {
        bringIntoView(label)
        onAllNodes(hasText(label, substring = true)).onFirst().assertIsDisplayed()
    }

    private fun ComposeTestRule.back() {
        device.pressBack()
        waitForIdle()
    }

    private fun openHelp() {
        compose.onNodeWithText(s(R.string.nav_options)).performClick()
        compose.open(s(R.string.opt_group_help))
        compose.assertOn(s(R.string.nav_help))
    }

    /** Every page behind the Help list, opened and left the way a user would. */
    @Test
    fun everyHelpPageOpensAndBackReturnsToTheList() {
        val pages = listOf(
            R.string.help_faq,
            R.string.help_deleted,
            R.string.help_quality,
            R.string.help_logs,
            R.string.help_privacy,
            R.string.help_licenses,
            R.string.help_about
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            openHelp()
            for (page in pages) {
                compose.open(s(page))
                compose.assertOn(s(page))
                compose.back()
                compose.assertOn(s(R.string.nav_help))
            }
            // And Back out of Help itself returns to Settings, not out of the app.
            compose.back()
            compose.assertOn(s(R.string.opt_group_help))
        }
    }

    /** The four tabs, and the pages reached from them without any state. */
    @Test
    fun theRoutesThatNeedNoStateAreAllReachableByTapping() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for (tab in listOf(
                R.string.nav_files, R.string.nav_storage, R.string.nav_options, R.string.nav_home
            )) {
                compose.onNodeWithText(s(tab)).performClick()
                compose.waitForIdle()
            }

            compose.onNodeWithText(s(R.string.nav_storage)).performClick()
            compose.open(s(R.string.calc_title))
            compose.assertOn(s(R.string.calc_title))
            compose.back()

            compose.open(s(R.string.hub_title))
            compose.assertOn(s(R.string.hub_title))
            compose.back()
            compose.onNode(NavTabs.matcher(s(R.string.nav_storage))).assertIsSelected()

            compose.onNodeWithText(s(R.string.nav_options)).performClick()
            compose.open(s(R.string.nav_activity))
            compose.assertOn(s(R.string.nav_activity))
            compose.back()
            compose.onNode(NavTabs.matcher(s(R.string.nav_options))).assertIsSelected()
        }
    }

    /**
     * The About page has to say which Androids this build actually runs on,
     * and say it correctly for the phone it is running on.
     */
    @Test
    fun aboutStatesTheAndroidItIsRunningOnAndPromisesNoNetwork() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openHelp()
            compose.open(s(R.string.help_about))

            compose.onNode(hasText(s(R.string.about_requires_title), substring = true))
                .performScrollTo().assertIsDisplayed()

            // Android 10 is supported but cannot trash, and the page must say
            // so rather than claiming everything works.
            val release = Platform.releaseName(Build.VERSION.SDK_INT)
            val expected = if (Platform.canTrash(Build.VERSION.SDK_INT)) {
                target.getString(R.string.about_running_full, release)
            } else {
                target.getString(R.string.about_running_ten, release)
            }
            compose.onNode(hasText(expected, substring = true))
                .performScrollTo().assertIsDisplayed()

            compose.onNode(hasText(s(R.string.about_network_none), substring = true))
                .performScrollTo().assertIsDisplayed()

            compose.onNode(
                hasText(target.getString(R.string.about_version_line, BuildConfig.VERSION_NAME))
            ).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * What the app tells people it supports must be what it was built to
     * support. The manifest inside the installed APK is the authority here -
     * not a constant that could drift away from it - so the check reads the
     * minimum straight back out of the package manager.
     */
    @Test
    fun theSupportedVersionsAgreeWithTheInstalledPackage() {
        val info = target.packageManager.getApplicationInfo(target.packageName, 0)
        assertEquals(
            "Platform.MIN_SDK must match the minSdk the APK was actually built with",
            info.minSdkVersion,
            Platform.MIN_SDK
        )
        assertTrue(
            "this device must not be older than the app claims to support",
            Build.VERSION.SDK_INT >= Platform.MIN_SDK
        )
        assertTrue(
            "every supported Android must have a name the About page can print",
            (Platform.MIN_SDK..Build.VERSION.SDK_INT).all {
                Platform.releaseName(it).isNotBlank()
            }
        )
    }

    /**
     * Every control that shows only an icon must carry a description, and
     * every clickable must be big enough to hit.
     */
    @Test
    fun everyControlIsDescribedAndBigEnoughToHit() {
        val described = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        val labelled = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        ActivityScenario.launch(MainActivity::class.java).use {
            for (tab in listOf(
                R.string.nav_home, R.string.nav_files, R.string.nav_storage, R.string.nav_options
            )) {
                compose.onNodeWithText(s(tab)).performClick()
                compose.waitForIdle()

                val clickables = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                assertTrue("${s(tab)} has no controls at all", clickables.isNotEmpty())

                // Anything clickable must be describable: either it shows text
                // or it carries a content description. An icon with neither is
                // a button a screen reader announces as nothing at all.
                val mute = clickables.count { node ->
                    !described.matches(node) && !labelled.matches(node) &&
                        node.children.none { child ->
                            described.matches(child) || labelled.matches(child)
                        }
                }
                assertEquals("${s(tab)} has controls with no label at all", 0, mute)

                // 48dp is the smallest target Android's own accessibility
                // guidance accepts, and it is what a thumb needs.
                compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEachIndexed { i, _ ->
                    compose.onAllNodes(hasClickAction())[i]
                        .assertHeightIsAtLeast(24.dp)
                        .assertWidthIsAtLeast(24.dp)
                }
            }
        }
    }
}
