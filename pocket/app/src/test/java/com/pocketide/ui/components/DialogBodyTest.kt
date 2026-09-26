package com.pocketide.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A dialog whose text runs past a few lines at a large font size scrolls: its text slot is a
 * [DialogBody], so nothing below the cut, such as how to stop something or the one button that
 * fixes it, is out of reach.
 */
class DialogBodyTest {
    @Test
    fun longDialogsScrollInsteadOfCuttingTheirTextOff() {
        val dialogs = mapOf(
            "ui/manage/ChatPlacesUi.kt" to "title = { Text(\"Where this chat is saved\") }",
            "ui/shell/KeyOnPhone.kt" to "title = { Text(\"Reconnect GitHub\") }",
            "ui/screens/secrets/SecretsScreen.kt" to "title = { Text(shown.name) }",
            "ui/screens/lock/LockScreens.kt" to "title = { Text(\"PocketIDE's Drive limit\") }",
            "ui/screens/computer/ResetComputer.kt" to "title = { Text(\"Saving your work first\") }",
        )
        for ((file, title) in dialogs) {
            val source = source(file)
            val start = source.indexOf(title)
            assertTrue("$file has no dialog titled like $title", start >= 0)
            val text = source.indexOf("text = {", start)
            assertTrue("$file: $title has a text slot", text >= 0)
            val slot = source.substring(text + "text = {".length).trimStart()
            assertTrue("$file: $title scrolls its text", slot.startsWith("DialogBody"))
        }
    }

    private fun source(file: String): String {
        val path = "src/main/java/com/pocketide/$file"
        val found = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
        return checkNotNull(found) { "$path not found" }.readText()
    }
}
