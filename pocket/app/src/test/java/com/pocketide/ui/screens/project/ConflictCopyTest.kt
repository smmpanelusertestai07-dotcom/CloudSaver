package com.pocketide.ui.screens.project

import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictCopyTest {
    private val original = SessionRecord(
        id = "s1",
        agentId = "claude",
        projectId = "octo/app",
        title = "Login bug",
        branch = "pocket/claude/2026-09-01-login-bug",
        startedAt = 1,
        lastActivityAt = 2,
        deviceId = "phone",
    )
    private val copy = original.copy(id = "s2", title = "Login bug (conflict copy)", status = SessionStatus.CONFLICT_COPY, conflictOf = "s1")

    @Test
    fun `Put on main is offered only where it can work`() {
        assertTrue(canPutOnMain(SessionStatus.OPEN))
        // SessionManager always refuses these; the menus do not offer them.
        assertFalse(canPutOnMain(SessionStatus.CONFLICT_COPY))
        assertFalse(canPutOnMain(SessionStatus.ON_MAIN))
        assertFalse(canPutOnMain(SessionStatus.DELETED))
    }

    @Test
    fun `a conflict copy names the chat that goes on main instead`() {
        assertEquals("Conflict copy of \"Login bug\": put the original on main.", conflictCopyNote(copy, listOf(original, copy)))
        assertEquals("Conflict copy: put the original chat on main.", conflictCopyNote(copy, listOf(copy)))
        assertNull(conflictCopyNote(original, listOf(original, copy)))
    }
}
