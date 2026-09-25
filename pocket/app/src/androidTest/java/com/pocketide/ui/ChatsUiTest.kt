package com.pocketide.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.chats.ChatsScreen
import com.pocketide.ui.theme.PocketTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Chats on a phone with no sessions yet (the test app starts with none). */
@RunWith(AndroidJUnit4::class)
class ChatsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val nav = RecordingNav()

    @Test
    fun anEmptyListSaysWhereChatsWillAppear() {
        compose.setContent { PocketTheme { ChatsScreen(nav) } }

        compose.onNodeWithText("No chats yet").assertIsDisplayed()
        compose.onNodeWithText("Search chats").performTextInput("login bug")
        compose.onNodeWithText("No chats yet").assertIsDisplayed()
    }

    @Test
    fun recentlyDeletedWaitingUploadsAndBackAreOneTapAway() {
        compose.setContent { PocketTheme { ChatsScreen(nav) } }

        compose.onNodeWithText("Recently deleted").performClick()
        compose.onNodeWithText("Waiting to upload").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("recentlyDeleted", "waitingUploads", "back"), nav.calls) }
    }

    private class RecordingNav : PocketNav {
        val calls = mutableListOf<String>()

        override fun back() { calls += "back" }
        override fun home() { calls += "home" }
        override fun chats() { calls += "chats" }
        override fun activity() { calls += "activity" }
        override fun settings() { calls += "settings" }
        override fun project(projectId: String) { calls += "project" }
        override fun agent(sessionId: String) { calls += "agent" }
        override fun transcript(sessionId: String) { calls += "transcript" }
        override fun yourData() { calls += "yourData" }
        override fun computer() { calls += "computer" }
        override fun usage() { calls += "usage" }
        override fun moreAgents() { calls += "moreAgents" }
        override fun help(sectionId: String?) { calls += "help" }
        override fun recentlyDeleted() { calls += "recentlyDeleted" }
        override fun waitingUploads() { calls += "waitingUploads" }
        override fun secrets(projectId: String?) { calls += "secrets" }
        override fun schedules(projectId: String?) { calls += "schedules" }
        override fun openExternal(url: String) { calls += "openExternal" }
    }
}
