package app.novacalc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end tests through the real activity: every key, the display, history, and settings. */
@RunWith(AndroidJUnit4::class)
class CalculatorUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun key(description: String) = rule.onNodeWithContentDescription(description)
    private fun tap(vararg descriptions: String) = descriptions.forEach { key(it).performClick() }
    private fun typeDigits(s: String) = s.forEach { key(it.toString()).performClick() }
    private fun expression() = rule.onNodeWithTag("expression")
    private fun preview() = rule.onNodeWithTag("preview")
    private fun result() = rule.onNodeWithTag("result")

    @Before
    fun clean() {
        rule.waitForIdle()
        tap("All clear")
    }

    @Test
    fun basicArithmeticWithLivePreview() {
        typeDigits("12")
        tap("Plus")
        typeDigits("30")
        expression().assertTextEquals("12 + 30")
        preview().assertTextEquals("42")
        tap("Equals")
        result().assertTextEquals("42")
        tap("Multiply")
        typeDigits("2")
        expression().assertTextEquals("42 × 2")
        preview().assertTextEquals("84")
        tap("Equals")
        result().assertTextEquals("84")
    }

    @Test
    fun everyBasicKeyWorks() {
        typeDigits("7")
        tap("Divide"); typeDigits("2")
        preview().assertTextEquals("3.5")
        tap("Minus"); typeDigits("0"); tap("Decimal point"); typeDigits("5")
        preview().assertTextEquals("3")
        tap("Equals"); result().assertTextEquals("3")
        tap("All clear")
        tap("Parentheses"); typeDigits("1"); tap("Plus"); typeDigits("2"); tap("Parentheses")
        tap("Multiply"); typeDigits("3"); tap("Toggle sign")
        expression().assertTextEquals("(1 + 2) × −3")
        preview().assertTextEquals("−9")
        tap("Equals"); result().assertTextEquals("−9")
        tap("All clear")
        typeDigits("50"); tap("Percent")
        preview().assertTextEquals("0.5")
        tap("Backspace")
        expression().assertTextEquals("50")
    }

    @Test
    fun divideByZeroShowsMessage() {
        typeDigits("1"); tap("Divide"); typeDigits("0"); tap("Equals")
        rule.onNodeWithTag("error").assertTextEquals("Can't divide by zero")
        tap("Backspace")
        expression().assertTextEquals("1 ÷")
    }

    @Test
    fun scientificPanelAndMemory() {
        tap("Show scientific keys")
        tap("Sine"); typeDigits("30"); tap("Equals")
        result().assertTextEquals("0.5")
        tap("All clear")
        tap("Inverse functions")
        tap("Inverse sine"); typeDigits("1"); tap("Equals")
        result().assertTextEquals("90")
        tap("All clear")
        typeDigits("5"); tap("Factorial"); preview().assertTextEquals("120")
        tap("Memory store")
        rule.onNodeWithTag("memory_chip").assertIsDisplayed()
        tap("All clear"); typeDigits("2"); tap("Multiply"); tap("Memory recall")
        preview().assertTextEquals("240")
        tap("Memory clear")
        tap("All clear")
        typeDigits("2"); tap("Power"); typeDigits("10"); tap("Equals")
        result().assertTextEquals("1,024")
        tap("Square root"); typeDigits("81"); tap("Equals")
        result().assertTextEquals("9")
        tap("Pi"); tap("Equals")
        result().assertTextEquals("3.1415926536")
        tap("Angle unit degrees")
        rule.onNodeWithTag("angle_chip").assertTextEquals("RAD")
        tap("Angle unit radians")
        rule.onNodeWithTag("angle_chip").assertTextEquals("DEG")
        tap("Hide scientific keys")
    }

    @Test
    fun historySheetShowsAndReusesResults() {
        typeDigits("6"); tap("Multiply"); typeDigits("7"); tap("Equals")
        rule.onNodeWithTag("open_history").performClick()
        rule.onNodeWithText("6 × 7").assertIsDisplayed()
        rule.onNodeWithText("= 42").performClick()
        expression().assertTextEquals("42")
        rule.onNodeWithTag("open_history").performClick()
        rule.onNodeWithTag("history_clear").performClick()
        rule.onNodeWithTag("history_clear_confirm").performClick()
        rule.onNodeWithTag("history_empty").assertIsDisplayed()
    }

    @Test
    fun settingsScreenChangesFormatting() {
        typeDigits("1"); tap("Divide"); typeDigits("3"); tap("Equals")
        result().assertTextEquals("0.3333333333")
        rule.onNodeWithTag("open_settings").performClick()
        rule.onNodeWithTag("settings_screen").assertIsDisplayed()
        rule.onNodeWithTag("theme_dark").performClick()
        rule.onNodeWithTag("switch_grouping").performClick()
        rule.onNodeWithTag("settings_back").performClick()
        tap("All clear")
        typeDigits("1234"); tap("Multiply"); typeDigits("1000")
        preview().assertTextEquals("1234000")
        rule.onNodeWithTag("open_settings").performClick()
        rule.onNodeWithTag("switch_grouping").performClick()
        rule.onNodeWithTag("theme_system").performClick()
        rule.onNodeWithTag("settings_back").performClick()
        preview().assertTextEquals("1,234,000")
    }

    @Test
    fun longPressBackspaceClearsAll() {
        typeDigits("987")
        rule.onNodeWithTag("backspace").performTouchInput { longClick() }
        expression().assertTextEquals("0")
    }
}
