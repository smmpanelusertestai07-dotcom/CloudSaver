package com.pocketide.ui.work

import com.pocketide.model.SessionStatus
import com.pocketide.ui.screens.chats.ChatFilter
import com.pocketide.ui.screens.chats.StatusFilter
import com.pocketide.sessions.Sessions
import com.pocketide.sync.BackupState
import com.pocketide.sync.SessionBackup
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.chats.backupState
import com.pocketide.ui.screens.chats.canContinue
import com.pocketide.ui.screens.chats.daysLeft
import com.pocketide.ui.screens.chats.daysLeftText
import com.pocketide.ui.screens.chats.filterChats
import com.pocketide.ui.screens.chats.recentlyDeleted
import com.pocketide.ui.screens.chats.sessionBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatsLogicTest {
    private val day = 24L * 60 * 60 * 1000
    private val names = mapOf("claude" to "Claude Code", "codex" to "Codex")
    private val nameOf = { id: String -> names[id] ?: id }

    private val chats = listOf(
        session("1", agentId = "claude", projectId = "me/app", title = "Login fix", lastActivityAt = 30),
        session("2", agentId = "codex", projectId = "me/app", title = "Dark mode", lastActivityAt = 50, status = SessionStatus.ON_MAIN),
        session("3", agentId = "claude", projectId = "me/site", title = "Landing page", lastActivityAt = 40, status = SessionStatus.CONFLICT_COPY),
        session("4", agentId = "claude", projectId = "me/app", title = "Gone", lastActivityAt = 99, status = SessionStatus.DELETED, deletedAt = 5),
    )

    private fun ids(filter: ChatFilter) = filterChats(chats, filter, nameOf).map { it.id }

    @Test
    fun deletedChatsNeverShowAndNewestComeFirst() {
        assertEquals(listOf("2", "3", "1"), ids(ChatFilter()))
    }

    @Test
    fun filtersCombine() {
        assertEquals(listOf("3", "1"), ids(ChatFilter(agentId = "claude")))
        assertEquals(listOf("2", "1"), ids(ChatFilter(projectId = "me/app")))
        assertEquals(listOf("1"), ids(ChatFilter(agentId = "claude", projectId = "me/app")))
        assertEquals(listOf("1"), ids(ChatFilter(status = StatusFilter.OPEN)))
        assertEquals(listOf("2"), ids(ChatFilter(status = StatusFilter.ON_MAIN)))
        assertEquals(listOf("3"), ids(ChatFilter(status = StatusFilter.CONFLICT)))
    }

    @Test
    fun searchLooksAtTitleBranchProjectAndAgent() {
        assertEquals(listOf("1"), ids(ChatFilter(query = "  LOGIN ")))
        assertEquals(listOf("3"), ids(ChatFilter(query = "me/site")))
        assertEquals(listOf("2"), ids(ChatFilter(query = "codex")))
        assertEquals(listOf("2"), ids(ChatFilter(query = "2026-09-24-2")))
        assertEquals(emptyList<String>(), ids(ChatFilter(query = "nothing like this")))
    }

    @Test
    fun daysLeftCountsThirtyDaysFromDeletion() {
        val deletedAt = 1_000_000L
        assertEquals(30, daysLeft(deletedAt, deletedAt))
        assertEquals(30, daysLeft(deletedAt, deletedAt + 1))
        assertEquals(29, daysLeft(deletedAt, deletedAt + day))
        assertEquals(1, daysLeft(deletedAt, deletedAt + 29 * day + 1))
        assertEquals(0, daysLeft(deletedAt, deletedAt + 30 * day))
        assertEquals(0, daysLeft(deletedAt, deletedAt + 400 * day))
        assertEquals("1 day left", daysLeftText(1))
        assertEquals("12 days left", daysLeftText(12))
        assertEquals("Erased at the next clean-up", daysLeftText(0))
    }

    @Test
    fun recentlyDeletedListsNewestDeletionFirst() {
        val list = chats + session("5", status = SessionStatus.DELETED, deletedAt = 9)
        assertEquals(listOf("5", "4"), recentlyDeleted(list).map { it.id })
    }

    @Test
    fun chatsDeletedForeverAreNotListedWhileTheyWaitForTheErase() {
        val list = chats + session("gone-for-good", status = SessionStatus.DELETED, deletedAt = Sessions.ERASE_NOW)
        assertEquals(listOf("4"), recentlyDeleted(list).map { it.id })
        assertEquals(listOf("2", "3", "1"), filterChats(list, ChatFilter(), nameOf).map { it.id })
    }

    @Test
    fun onlyLiveBranchesCanContinue() {
        assertTrue(session("a").canContinue())
        assertTrue(session("a", status = SessionStatus.CONFLICT_COPY).canContinue())
        assertFalse(session("a", status = SessionStatus.ON_MAIN).canContinue())
        assertFalse(session("a", status = SessionStatus.DELETED, deletedAt = 1).canContinue())
    }

    @Test
    fun backupStateInWords() {
        assertEquals("Backed up" to Tone.OK, backupState(session("a")))
        assertEquals("Not backed up" to Tone.WARN, backupState(session("a", backUp = false, pendingBytes = 10)))
        // Videos held for Wi-Fi are the sync engine's count; the record never had one.
        val waiting = SessionBackup(BackupState.WAITING_FOR_WIFI, pendingBytes = 10, videosWaitingForWifi = 1)
        assertEquals("1 video waiting for Wi-Fi" to Tone.WARN, backupState(session("a", pendingBytes = 10), waiting))
        assertEquals("Waiting to upload" to Tone.WARN, backupState(session("a", pendingBytes = 10)))
        assertEquals(30L, sessionBytes(session("a", transcriptBytes = 10, mediaBytes = 20)))
    }
}
