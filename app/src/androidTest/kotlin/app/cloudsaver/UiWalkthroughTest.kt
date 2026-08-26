package app.cloudsaver

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.media.MediaScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks the real UI on the device and photographs every screen, so the design
 * can be reviewed from CI artifacts after each change.
 *
 * It also asserts. The previous version wrapped every navigation step in
 * runCatching, which made it a tour rather than a test: two of its screens had
 * been looking in the wrong place for months - the calculator was expected on
 * Home when it is reached from Storage, and the largest-files list was
 * expected on Storage when it is reached from the Free up space hub - and the
 * suite passed the whole time. Nothing here is allowed to fail quietly now.
 */
private const val SHOT_DIR = "Pictures/CSTestShots/"

@RunWith(AndroidJUnit4::class)
class UiWalkthroughTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.forThisDevice())

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    private fun s(id: Int): String = target.getString(id)

    @Before
    fun seedGallery() {
        // Several cards only exist once there is something to act on, so the
        // tour needs a real gallery behind it or it photographs empty states
        // and proves nothing about the screens that matter.
        MediaFixtures.cleanUp(target)
        runBlocking {
            AppDb.get(target).clearAllTables()
            for (i in 1..3) {
                MediaFixtures.insertPhoto(
                    target, name = "tour_photo_$i.jpg", seed = i, captureMillis = 1_600_000_000_000L
                )
            }
            MediaScanner(target, AppDb.get(target)).scan()
        }
    }

    @After
    fun clearGallery() {
        MediaFixtures.cleanUp(target)
        runBlocking { AppDb.get(target).clearAllTables() }
    }

    /**
     * Android 11+ hides /sdcard/Android/data from adb, so screenshots are
     * published through MediaStore into Pictures/CSTestShots, which adb can
     * pull. (The pipeline only ever looks at Pictures/CloudSaver, so these
     * never interfere with it.)
     */
    private fun publish(name: String, png: ByteArray) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, SHOT_DIR)
        }
        val collection = android.provider.MediaStore.Images.Media
            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = target.contentResolver.insert(collection, values) ?: return
        target.contentResolver.openOutputStream(uri)?.use { it.write(png) }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        val temp = File(target.cacheDir, "$name.png")
        if (device.takeScreenshot(temp) && temp.exists()) {
            publish(name, temp.readBytes())
            temp.delete()
        }
    }

    /** Scrolls a labelled row into view and taps it. Fails loudly if absent. */
    private fun ComposeTestRule.openRow(label: String) {
        onNode(hasText(label, substring = true)).performScrollTo().performClick()
        waitForIdle()
    }

    /**
     * Asserts the label we expect to have landed on is on screen.
     *
     * onFirst matters: a tab's own name is drawn in the bar as well as on the
     * screen it opens, so "at most one node" is simply false for every tab in
     * the app. What is being asserted is that the label is present and
     * displayed, not that it is unique.
     */
    private fun ComposeTestRule.assertOn(label: String) {
        onAllNodes(hasText(label, substring = true)).onFirst().assertIsDisplayed()
    }

    /**
     * Asserts a bottom-bar tab is the selected one.
     *
     * This is what "we navigated" actually means; finding the tab's text
     * proves only that the bar is drawn, which it always is.
     */
    private fun ComposeTestRule.assertTabSelected(label: String) {
        onNode(hasText(label) and isSelectable() and isSelected()).assertExists()
    }

    private fun setOnboardingDone(done: Boolean) = runBlocking {
        val repo = OptionsRepo.get(target)
        repo.setBool(OptionsRepo.K.ONBOARDING_DONE, done)
        repo.setInt(OptionsRepo.K.ONBOARDING_STEP, 0)
    }

    private fun setTheme(mode: String) = runBlocking {
        OptionsRepo.get(target).setString(OptionsRepo.K.THEME, mode)
    }

    /**
     * Photographs the main screens with the dark palette forced on.
     *
     * Material 3 leaves LocalContentColor to Surface rather than to
     * MaterialTheme, so a missing one paints unstyled text black - which looks
     * perfectly fine in light mode and is invisible in dark. Only a dark-mode
     * shot catches that, and the emulator runs light by default.
     */
    @Test
    fun everyMainScreenRendersInDarkTheme() {
        setOnboardingDone(true)
        setTheme("DARK")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                shoot("50-dark-home")
                compose.onNodeWithText(s(R.string.nav_storage)).performClick()
                compose.assertTabSelected(s(R.string.nav_storage))
                shoot("51-dark-storage")
                compose.onNodeWithText(s(R.string.nav_options)).performClick()
                shoot("52-dark-settings")
                compose.onNodeWithText(s(R.string.nav_files)).performClick()
                shoot("53-dark-files")
            }
        } finally {
            setTheme("SYSTEM")
        }
    }

    /** Renders the launcher icon itself so the logo can be reviewed too. */
    @Test
    fun appIconRenders() {
        val sizes = listOf(192, 432)
        for (size in sizes) {
            val drawable = target.getDrawable(R.mipmap.ic_launcher)!!
            val bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val bytes = java.io.ByteArrayOutputStream().also {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            publish("00-app-icon-$size", bytes)
            bitmap.recycle()
        }
        // The monochrome layer must exist for themed icons on Android 13+.
        val mono = target.getDrawable(R.drawable.ic_launcher_monochrome)
        assertNotNull(mono)
    }

    @Test
    fun onboardingLooksRight() {
        setOnboardingDone(false)
        ActivityScenario.launch(MainActivity::class.java).use {
            shoot("10-onboarding-welcome")
            compose.onNodeWithText(s(R.string.onb_start)).performClick()
            compose.waitForIdle()
            shoot("11-onboarding-permission")
            // Media access is already granted by the rule, so the permission
            // step is satisfied and its continue button must be there.
            compose.onNodeWithText(s(R.string.onb_done_next)).performClick()
            compose.waitForIdle()
            shoot("12-onboarding-step")
        }
    }

    @Test
    fun everyMainScreenRenders() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            shoot("20-home")

            compose.onNodeWithText(s(R.string.nav_files)).performClick()
            compose.assertTabSelected(s(R.string.nav_files))
            shoot("21-files")

            compose.onNodeWithText(s(R.string.nav_storage)).performClick()
            compose.assertTabSelected(s(R.string.nav_storage))
            shoot("22-storage")

            compose.onNodeWithText(s(R.string.nav_options)).performClick()
            shoot("23-settings-top")
            // Scroll through the long settings list to catch layout problems.
            compose.onNode(hasText(s(R.string.opt_group_quality), substring = true))
                .performScrollTo().assertIsDisplayed()
            shoot("24-settings-quality")
            compose.onNode(hasText(s(R.string.opt_group_backup_restore), substring = true))
                .performScrollTo().assertIsDisplayed()
            shoot("25-settings-backup")
        }
    }

    /** The calculator is reached from Storage, not from Home. */
    @Test
    fun calculatorOpensFromStorage() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(s(R.string.nav_storage)).performClick()
            compose.openRow(s(R.string.calc_title))
            compose.assertOn(s(R.string.calc_title))
            shoot("26-calculator")
        }
    }

    @Test
    fun activityScreenRenders() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            // Activity lives under Settings now, with the other reference
            // material, rather than competing for room on Home.
            compose.onNodeWithText(s(R.string.nav_options)).performClick()
            compose.openRow(s(R.string.nav_activity))
            compose.assertOn(s(R.string.nav_activity))
            shoot("27-activity")
        }
    }

    @Test
    fun helpSectionIsReachableFromSettings() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(s(R.string.nav_options)).performClick()
            compose.onNode(hasText(s(R.string.opt_group_help), substring = true))
                .performScrollTo().assertIsDisplayed()
            shoot("28-settings-help")
        }
    }

    /** The largest-files list is reached through the Free up space hub. */
    @Test
    fun largestFilesOpensFromTheFreeUpHub() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(s(R.string.nav_storage)).performClick()
            compose.openRow(s(R.string.hub_title))
            compose.assertOn(s(R.string.hub_title))
            shoot("29a-free-space-hub")
            compose.openRow(s(R.string.find_biggest))
            compose.assertOn(s(R.string.find_biggest))
            shoot("29-biggest-space-users")
        }
    }

    @Test
    fun encryptedBackupDialogOpens() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(s(R.string.nav_options)).performClick()
            compose.openRow(s(R.string.transfer_export))
            shoot("30-backup-password-dialog")
        }
    }
}
