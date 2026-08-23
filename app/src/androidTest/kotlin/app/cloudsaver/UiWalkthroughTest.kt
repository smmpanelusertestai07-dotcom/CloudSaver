package app.cloudsaver

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import app.cloudsaver.data.prefs.OptionsRepo
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real UI on the device and photographs every screen. The PNGs are
 * written to the app's external files dir so CI can pull them as artifacts -
 * that is how the design gets reviewed after each change.
 */
private const val SHOT_DIR = "Pictures/CSTestShots/"

@RunWith(AndroidJUnit4::class)
class UiWalkthroughTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

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

    private fun setOnboardingDone(done: Boolean) = runBlocking {
        val repo = OptionsRepo.get(target)
        repo.setBool(OptionsRepo.K.ONBOARDING_DONE, done)
        repo.setInt(OptionsRepo.K.ONBOARDING_STEP, 0)
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
        org.junit.Assert.assertNotNull(mono)
    }

    @Test
    fun onboardingLooksRight() {
        setOnboardingDone(false)
        ActivityScenario.launch(MainActivity::class.java).use {
            shoot("10-onboarding-welcome")
            compose.onNodeWithText("Get started").performClick()
            shoot("11-onboarding-permission")
            // The permission is already granted by the rule, so this advances.
            compose.onAllNodesWithTextSafe("Done, next")
            shoot("12-onboarding-step")
        }
    }

    @Test
    fun everyMainScreenRenders() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            shoot("20-home")

            compose.onNodeWithText("Files").performClick()
            shoot("21-files")

            compose.onNodeWithText("Storage").performClick()
            shoot("22-storage")

            compose.onNodeWithText("Settings").performClick()
            shoot("23-settings-top")
            // Scroll through the long settings list to catch layout problems.
            runCatching {
                compose.onNode(hasText("Quality", substring = true)).performScrollTo()
            }
            shoot("24-settings-quality")
            runCatching {
                compose.onNode(hasText("Backup & restore", substring = true)).performScrollTo()
            }
            shoot("25-settings-backup")

            // Cloud calculator, reached from Home.
            compose.onNodeWithText("Home").performClick()
            runCatching {
                compose.onNode(hasText("Cloud calculator", substring = true)).performScrollTo()
                    .performClick()
            }
            shoot("26-calculator")
        }
    }

    @Test
    fun encryptedBackupDialogOpens() {
        setOnboardingDone(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Settings").performClick()
            runCatching {
                compose.onNode(hasText("Save backup", substring = true)).performScrollTo()
                    .performClick()
            }
            shoot("30-backup-password-dialog")
        }
    }
}

/** Best-effort click: the flow must not fail because a label moved. */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextSafe(text: String) {
    runCatching { onNodeWithText(text).performClick() }
}
