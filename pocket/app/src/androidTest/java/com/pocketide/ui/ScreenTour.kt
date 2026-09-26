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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketide.agents.OfficialAgents
import com.pocketide.core.ThemeMode
import com.pocketide.docs.ChatHomes
import com.pocketide.docs.DocBlock
import com.pocketide.docs.DocSection
import com.pocketide.docs.DocsContent
import com.pocketide.model.LockReason
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.activity.ActivityScreen
import com.pocketide.ui.screens.agents.MoreAgentsScreen
import com.pocketide.ui.screens.chats.ChatsScreen
import com.pocketide.ui.screens.computer.ComputerScreen
import com.pocketide.ui.screens.data.YourDataScreen
import com.pocketide.ui.screens.help.HelpScreen
import com.pocketide.ui.screens.home.HomeScreen
import com.pocketide.ui.screens.lock.AppLockScreen
import com.pocketide.ui.screens.lock.LockScreen
import com.pocketide.ui.screens.onboarding.ComputerStepScreen
import com.pocketide.ui.screens.onboarding.DriveStepScreen
import com.pocketide.ui.screens.onboarding.GitHubStepScreen
import com.pocketide.ui.screens.onboarding.WelcomeScreen
import com.pocketide.ui.screens.schedules.SchedulesScreen
import com.pocketide.ui.screens.secrets.SecretsScreen
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
 * Each screen is drawn on this phone's real app state and saved as a PNG in the app's own
 * files, under tour/, where CI reads it with `run-as`. A long screen is first scrolled to the
 * part the picture is about.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTour {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val nav = TourNav()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("Runs only with -e tour true", InstrumentationRegistry.getArguments().getString("tour") == "true")
    }

    @Test fun welcome() = shoot("01-welcome") { WelcomeScreen(onContinue = {}) }

    @Test fun welcomeDark() = shoot("01-welcome-dark", ThemeMode.DARK) { WelcomeScreen(onContinue = {}) }

    @Test fun gitHubStep() = shoot("02-github-step") { GitHubStepScreen(onDone = {}) }

    @Test fun driveStep() = shoot("03-drive-step") { DriveStepScreen(onDone = {}, onOpenHelp = {}) }

    @Test fun computerStep() = shoot("04-computer-step") { ComputerStepScreen(onDone = {}) }

    @Test fun home() = shoot("05-home") { HomeScreen(nav) }

    @Test fun homeDark() = shoot("05-home-dark", ThemeMode.DARK) { HomeScreen(nav) }

    @Test fun chats() = shoot("06-chats") { ChatsScreen(nav) }

    @Test fun activity() = shoot("07-activity") { ActivityScreen(nav) }

    @Test fun settings() = shoot("08-settings") { SettingsScreen(nav) }

    @Test fun settingsDark() = shoot("08-settings-dark", ThemeMode.DARK) { SettingsScreen(nav) }

    @Test fun yourData() = shoot("09-your-data") { YourDataScreen(nav) }

    @Test fun computer() = shoot("10-computer") { ComputerScreen(nav) }

    @Test fun usage() = shoot("11-usage") { UsageScreen(nav) }

    @Test fun moreAgents() = shoot("12-more-agents") { MoreAgentsScreen(nav) }

    @Test fun secrets() = shoot("13-secrets") { SecretsScreen(projectId = null, nav = nav) }

    @Test fun schedules() = shoot("14-schedules") { SchedulesScreen(projectId = null, nav = nav) }

    @Test fun help() = shoot("15-help") { HelpScreen(sectionId = null, nav = nav) }

    @Test fun helpPrivacy() = shoot("16-help-privacy") { HelpScreen(sectionId = "privacy", nav = nav) }

    @Test fun appLock() = shoot("17-app-lock") { AppLockScreen(onUnlock = {}) }

    @Test fun gitHubDisconnected() = shoot("18-locked-github") { LockScreen(LockReason.GitHubDisconnected) }

    @Test fun driveDisconnected() = shoot("19-locked-drive") { LockScreen(LockReason.DriveDisconnected) }

    @Test fun settingsClaudeChats() = shoot(
        "20-settings-claude-chats",
        scroll = { compose.onNodeWithText(ChatHomes.CLAUDE_SWITCH).performScrollTo() },
    ) { SettingsScreen(nav) }

    // Your data is a lazy list: a card below the fold is not composed until the list scrolls to it.
    // Its section labels are drawn in capitals.
    @Test fun yourDataChatPlaces() = shoot(
        "21-your-data-chat-places",
        scroll = { list().performScrollToNode(hasText(CHAT_PLACES_LABEL, ignoreCase = true)) },
    ) { YourDataScreen(nav) }

    @Test fun helpPrivacyTable() {
        val privacy = checkNotNull(DocsContent.section("privacy"))
        val table = itemOf(privacy) { it is DocBlock.Table && it.header.last() == ChatHomes.TABLE_HEADER }
        shoot("22-help-privacy-table", scroll = { list().performScrollToIndex(table) }) { HelpScreen(sectionId = privacy.id, nav = nav) }
    }

    @Test fun helpClaude() {
        val page = DocsContent.agentPage(OfficialAgents.claude)
        val stores = itemOf(page) { it is DocBlock.Paragraph && it.text == ChatHomes.claude.note }
        shoot("23-help-claude", scroll = { list().performScrollToIndex(stores) }) { HelpScreen(sectionId = page.id, nav = nav) }
    }

    /** The screen's one scrolling list. */
    private fun list(): SemanticsNodeInteraction = compose.onAllNodes(hasScrollToIndexAction()).onFirst()

    /**
     * The list position of [section]'s first block that [block] accepts, as Help's page lists it:
     * its title, its summary, then one item per block. Help draws its text in Android's own text
     * views (for native selection), which the Compose test cannot search, so the page scrolls by position.
     */
    private fun itemOf(section: DocSection, block: (DocBlock) -> Boolean): Int {
        val index = section.blocks.indexOfFirst(block)
        check(index >= 0) { "${section.id} has no such block" }
        return 1 + (if (section.summary.isNotBlank()) 1 else 0) + index
    }

    private fun shoot(name: String, mode: ThemeMode = ThemeMode.LIGHT, scroll: (() -> Unit)? = null, screen: @Composable () -> Unit) {
        // The same frame PocketRoot gives every screen: the theme's background, and the text colour
        // that goes with it.
        compose.setContent {
            PocketTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { screen() }
            }
        }
        compose.waitForIdle()
        // Lists and states load from disk off the main thread; give them a moment to arrive.
        SystemClock.sleep(SETTLE_MS)
        compose.waitForIdle()
        if (scroll != null) {
            scroll()
            compose.waitForIdle()
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "tour").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class TourNav : PocketNav {
        override fun back() = Unit
        override fun home() = Unit
        override fun chats() = Unit
        override fun activity() = Unit
        override fun settings() = Unit
        override fun project(projectId: String) = Unit
        override fun agent(sessionId: String) = Unit
        override fun transcript(sessionId: String) = Unit
        override fun yourData() = Unit
        override fun computer() = Unit
        override fun usage() = Unit
        override fun moreAgents() = Unit
        override fun help(sectionId: String?) = Unit
        override fun recentlyDeleted() = Unit
        override fun waitingUploads() = Unit
        override fun secrets(projectId: String?) = Unit
        override fun schedules(projectId: String?) = Unit
        override fun openExternal(url: String) = Unit
    }

    private companion object {
        const val SETTLE_MS = 2_000L
        const val CHAT_PLACES_LABEL = "Where your chats are saved"
    }
}
