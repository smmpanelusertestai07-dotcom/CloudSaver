package com.pocketide.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.ui.screens.onboarding.WelcomeScreen
import com.pocketide.ui.theme.PocketTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first screen a new owner sees: what PocketIDE is, this phone's facts, and the way on. */
@RunWith(AndroidJUnit4::class)
class OnboardingUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun welcomeSaysWhereThingsLiveAndMovesOn() {
        var continued = 0
        compose.setContent { PocketTheme { WelcomeScreen(onContinue = { continued++ }) } }

        compose.onNodeWithText("Claude Code, Codex and Antigravity, full screen on your phone.").assertIsDisplayed()
        compose.onNodeWithText("Runs in this app, on your phone. No server of ours in between.").assertIsDisplayed()
        compose.onNodeWithText("This phone").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Get started").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, continued) }
    }

    @Test
    fun termsAndPrivacyOpenTheirHelpPages() {
        val opened = mutableListOf<String>()
        compose.setContent { PocketTheme { WelcomeScreen(onContinue = {}, onOpenHelp = { opened += it }) } }

        compose.onNodeWithText("Terms").performScrollTo().performClick()
        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("terms", "privacy"), opened) }
    }
}
