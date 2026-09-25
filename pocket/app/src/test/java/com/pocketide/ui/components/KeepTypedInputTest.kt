package com.pocketide.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A dialog that holds typed text closes only through Back or its own buttons. */
class KeepTypedInputTest {
    @Test
    fun aTapBesideTheDialogDoesNotCloseIt() {
        assertFalse(KeepTypedInput.dismissOnClickOutside)
        assertTrue(KeepTypedInput.dismissOnBackPress)
    }

    @Test
    fun everyDialogWithTypedTextKeepsIt() {
        val dialogs = mapOf(
            "schedules/SchedulesScreen.kt" to "title = { Text(if (existing == null) \"New scheduled task\"",
            "secrets/SecretsScreen.kt" to "title = { Text(if (existing == null) \"Add a value\"",
            "home/HomeDialogs.kt" to "title = { Text(\"New project\") }",
            "project/ProjectScreens.kt" to "title = { Text(\"New session\") }",
        )
        for ((file, title) in dialogs) {
            val source = screen(file)
            val start = source.indexOf(title)
            assertTrue("$file has no dialog titled like $title", start >= 0)
            val opening = source.lastIndexOf("AlertDialog(", start)
            assertTrue("$file: $title", source.substring(opening, start).contains("properties = KeepTypedInput"))
        }
    }

    private fun screen(file: String): String {
        val path = "src/main/java/com/pocketide/ui/screens/$file"
        val found = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
        return checkNotNull(found) { "$path not found" }.readText()
    }
}
