package com.pocketide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.ui.screens.data.DeleteEverythingDialog
import com.pocketide.ui.theme.PocketTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A dialog whose text is taller than its window scrolls, so the field under the text can still be reached. */
@RunWith(AndroidJUnit4::class)
class DialogScrollUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deleteEverythingKeepsItsFieldReachableAtTheLargestFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = LARGEST_FONT)) {
                PocketTheme { DeleteEverythingDialog(onDismiss = {}, onConfirm = {}) }
            }
        }

        compose.onNodeWithText("Type DELETE").performScrollTo().assertIsDisplayed().performTextInput("DELETE")
        compose.onNodeWithText("Delete everything").assertIsEnabled()
    }

    private companion object {
        const val LARGEST_FONT = 2f
    }
}
