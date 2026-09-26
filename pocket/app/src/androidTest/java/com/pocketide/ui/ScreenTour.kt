package com.pocketide.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketide.core.ThemeMode
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.screens.computer.ComputerScreen
import com.pocketide.ui.screens.data.YourDataScreen
import com.pocketide.ui.screens.help.HelpPageScreen
import com.pocketide.ui.screens.help.HelpScreen
import com.pocketide.ui.screens.home.HomeScreen
import com.pocketide.ui.screens.onboarding.SignInScreen
import com.pocketide.ui.screens.onboarding.WelcomeScreen
import com.pocketide.ui.screens.settings.SettingsScreen
import com.pocketide.ui.screens.usage.UsageScreen
import com.pocketide.ui.theme.PocketTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pictures of the app's screens as a new owner meets them, for the release notes and the owner.
 *
 * Runs only when asked (`am instrument -e tour true`), so the normal on-device run stays quick.
 * Each screen is drawn on this phone's real app state (signed out: the emulator has no GitHub
 * account) and saved as a PNG in the app's own files, under tour/, where CI reads it with run-as.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTour {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val graph get() = compose.activity.graph

    @Before
    fun onlyWhenAsked() {
        assumeTrue("Runs only with -e tour true", InstrumentationRegistry.getArguments().getString("tour") == "true")
    }

    @Test fun welcome() = shoot("01-welcome") { WelcomeScreen(onRead = {}, onContinue = {}) }

    @Test fun welcomeDark() = shoot("01-welcome-dark", ThemeMode.DARK) { WelcomeScreen(onRead = {}, onContinue = {}) }

    @Test fun signIn() = shoot("02-sign-in") { SignInScreen(graph, onRead = {}) }

    @Test fun home() = shoot("03-home") { HomeScreen(onOpenComputer = {}, onNewProject = {}, onOpenRepo = {}, onUsage = {}, onHelp = {}) }

    @Test fun homeDark() = shoot("03-home-dark", ThemeMode.DARK) {
        HomeScreen(onOpenComputer = {}, onNewProject = {}, onOpenRepo = {}, onUsage = {}, onHelp = {})
    }

    @Test fun computer() = shoot("04-computer") { ComputerScreen(agentToShow = null, onAgentShown = {}, onHome = {}, onHelp = {}) }

    @Test fun usage() = shoot("05-usage") { UsageScreen(onHelp = {}) }

    @Test fun settings() = shoot("06-settings") { SettingsScreen(onYourData = {}, onHelp = {}, onHelpPage = {}) }

    @Test fun yourData() = shoot("07-your-data") { YourDataScreen(onBack = {}, onHelpPage = {}) }

    @Test fun help() = shoot("08-help") { HelpScreen(onBack = {}, onOpen = {}) }

    @Test fun helpComputer() = shoot("09-help-computer") { HelpPageScreen(id = "computer", onBack = {}, onOpen = {}) }

    @Test fun terms() = shoot("10-terms") { HelpPageScreen(id = DocsContent.TERMS_ID, onBack = {}, onOpen = {}) }

    private fun shoot(name: String, mode: ThemeMode = ThemeMode.LIGHT, screen: @Composable () -> Unit) {
        compose.setContent {
            PocketTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { screen() }
            }
        }
        compose.waitForIdle()
        // Agent icons and GitHub's answers arrive over the network; give them a moment.
        SystemClock.sleep(SETTLE_MS)
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "tour").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SETTLE_MS = 2_500L
    }
}
