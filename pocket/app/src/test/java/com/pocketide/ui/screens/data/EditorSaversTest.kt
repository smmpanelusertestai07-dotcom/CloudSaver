package com.pocketide.ui.screens.data

import androidx.compose.runtime.saveable.SaverScope
import com.pocketide.ui.manage.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EditorSaversTest {
    private val scope = SaverScope { true }

    private fun roundTrip(file: MemoryFile?): MemoryFile? {
        val saved = with(MemoryFileSaver) { scope.save(file) } ?: return null
        return MemoryFileSaver.restore(saved)
    }

    @Test
    fun `the open memory file comes back after the app lock`() {
        val file = MemoryFile("claude", "~/.claude/CLAUDE.md", File("/data/rooms/claude/.claude/CLAUDE.md"), true, 120L, true, true)
        assertEquals(file, roundTrip(file))
        assertNull(roundTrip(null))
    }

    @Test
    fun `a draft is kept unless too large for saved state`() {
        val draft = "Always run the tests before Put on main.\n"
        assertEquals(draft, with(DraftSaver) { scope.save(draft) })
        assertNull("a huge text is read from the file again instead", with(DraftSaver) { scope.save("x".repeat(65 * 1024)) })
    }
}
