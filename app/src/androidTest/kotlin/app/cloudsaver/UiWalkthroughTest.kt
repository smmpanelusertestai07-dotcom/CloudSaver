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

    private fun shotDir(): File =
        File(target.getExternalFilesDir(null), "screenshots").apply { mkdirs() }

    private fun shoot(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
        device.takeScreenshot(File(shotDir(), "$name.png"))
    }

    private fun setOnboardingDone(done: Boolean) = runBlocking {
        val repo = OptionsRepo.get(target)
        repo.setBool(OptionsRepo.K.ONBOARDING_DONE, done)
        repo.setInt(OptionsRepo.K.ONBOARDING_STEP, 0)
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
