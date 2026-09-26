package com.pocketide.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.ui.screens.home.NewProjectDialog
import com.pocketide.ui.theme.PocketTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the owner typed survives the screen being rebuilt from saved state, as it is when the app
 * lock re-arms while they are away, or Android ends the process.
 */
@RunWith(AndroidJUnit4::class)
class SavedDraftsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aNewProjectsNameAndDescriptionAreKept() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PocketTheme { NewProjectDialog(onDismiss = {}, onCreated = {}) } }

        compose.onNodeWithText("Name").performTextInput("pocket-notes")
        compose.onNodeWithText("Description (optional)").performTextInput("Notes for the week")
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Name").assertTextContains("pocket-notes")
        compose.onNodeWithText("Description (optional)").assertTextContains("Notes for the week")
    }
}
