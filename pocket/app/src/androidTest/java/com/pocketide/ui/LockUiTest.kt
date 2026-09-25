package com.pocketide.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.model.LockReason
import com.pocketide.ui.screens.lock.AppLockScreen
import com.pocketide.ui.screens.lock.LockScreen
import com.pocketide.ui.theme.PocketTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app lock and the access locks: each says what happened and offers its fix. */
@RunWith(AndroidJUnit4::class)
class LockUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appLockAsksOnceByItselfAndAgainOnTheButton() {
        var asked = 0
        compose.setContent { PocketTheme { AppLockScreen(onUnlock = { asked++ }) } }

        compose.onNodeWithText("PocketIDE is locked").assertIsDisplayed()
        compose.runOnIdle { assertEquals("the prompt opens by itself when the screen resumes", 1, asked) }
        compose.onNodeWithText("Unlock").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, asked) }
    }

    @Test
    fun withoutAScreenLockTheOwnerIsSentToSetOne() {
        var asked = 0
        var settingsOpened = false
        compose.setContent {
            PocketTheme {
                AppLockScreen(onUnlock = { asked++ }, deviceSecure = false, onSetScreenLock = { settingsOpened = true })
            }
        }

        compose.onNodeWithText("Unlock").assertIsNotEnabled()
        compose.onNodeWithText("Open screen lock settings").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(settingsOpened)
            assertEquals("no prompt without a screen lock to check", 0, asked)
        }
    }

    @Test
    fun anUnsupportedPhoneIsToldWhatPocketIdeNeeds() {
        compose.setContent { PocketTheme { LockScreen(LockReason.Unsupported("This phone runs 32-bit Android.")) } }

        compose.onNodeWithText("PocketIDE can't run on this phone").assertIsDisplayed()
        compose.onNodeWithText("This phone runs 32-bit Android.").assertIsDisplayed()
        compose.onNodeWithText("• Android 10 or newer, 64-bit (arm64)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun usingItOnThisPhoneIsConfirmedFirst() {
        compose.setContent { PocketTheme { LockScreen(LockReason.OtherPhone("Pixel 8")) } }

        compose.onNodeWithText("In use on Pixel 8").assertIsDisplayed()
        compose.onNodeWithText("Use here").performScrollTo().performClick()
        compose.onNodeWithText("Use PocketIDE on this phone?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Use PocketIDE on this phone?").assertDoesNotExist()
    }
}
