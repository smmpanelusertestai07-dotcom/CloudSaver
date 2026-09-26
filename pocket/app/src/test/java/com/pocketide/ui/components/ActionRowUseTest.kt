package com.pocketide.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A chip and its button side by side wrap, so a large font size never squeezes the button to a sliver. */
class ActionRowUseTest {
    @Test
    fun homesSignInButtonWrapsUnderItsChip() {
        val source = source("ui/screens/home/HomeScreen.kt")
        val start = source.indexOf("signInLabel(signedIn)?.let")
        assertTrue("HomeScreen shows the sign-in chip", start >= 0)
        val block = source.substring(start, source.indexOf("Text(\"Sign in\")", start))
        assertTrue(block, block.contains("ActionRow {") && !block.contains("Row(verticalAlignment"))
    }

    @Test
    fun homesRemoteControlChipLeavesTheNameRowToTheName() {
        val source = source("ui/screens/home/HomeScreen.kt")
        val card = source.substring(source.indexOf("private fun AgentCard("))
        val nameRow = card.substring(card.indexOf("agent.displayName,"), card.indexOf("\"\${agent.publisher} · \$state\""))
        assertFalse("beside the name and Official, it leaves the name no room: $nameRow", nameRow.contains("Remote Control"))
        assertTrue("Home still shows it", card.contains("StatusChip(\"Remote Control on\""))
    }

    private fun source(file: String): String {
        val path = "src/main/java/com/pocketide/$file"
        val found = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
        return checkNotNull(found) { "$path not found" }.readText()
    }
}
