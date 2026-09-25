package com.pocketide.ui.work

import com.pocketide.limiter.RoomWork
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.rooms.RoomStop
import com.pocketide.rooms.StopReason
import com.pocketide.sync.BackupState
import com.pocketide.sync.RecentlyDeleted
import com.pocketide.sync.SessionBackup
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.Told
import com.pocketide.ui.manage.WorkText
import com.pocketide.ui.screens.chats.backupState
import com.pocketide.ui.screens.chats.daysLeft
import com.pocketide.ui.screens.home.syncDot
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWordsTest {
    private fun session(id: String, agent: String = "claude", status: SessionStatus = SessionStatus.OPEN, last: Long = 0) =
        SessionRecord(id, agent, "me/app", id, "pocket/$agent/2026-09-25-$id", 0, last, status = status, deviceId = "phone")

    @Test
    fun `a chat's chip follows the sync engine`() {
        val s = session("a")
        val at = { ms: Long -> "at $ms" }
        assertEquals("Backed up · at 5" to Tone.OK, backupState(s, SessionBackup(BackupState.BACKED_UP, lastBackedUpAt = 5), at))
        assertEquals("Backed up" to Tone.OK, backupState(s, SessionBackup(BackupState.BACKED_UP), at))
        assertEquals("Waiting to upload" to Tone.WARN, backupState(s, SessionBackup(BackupState.WAITING), at))
        assertEquals("1 video waiting for Wi-Fi" to Tone.WARN, backupState(s, SessionBackup(BackupState.WAITING_FOR_WIFI, videosWaitingForWifi = 1), at))
        assertEquals("Not backed up" to Tone.WARN, backupState(s, SessionBackup(BackupState.NOT_BACKED_UP), at))
    }

    @Test
    fun `the Home dot shows sessions still waiting between syncs`() {
        val idle = SyncStatus.UpToDate(1)
        assertEquals(Tone.OK, syncDot(idle, listOf(SessionBackup(BackupState.BACKED_UP))).first)
        assertEquals(Tone.WARN, syncDot(idle, listOf(SessionBackup(BackupState.BACKED_UP), SessionBackup(BackupState.WAITING))).first)
        assertEquals(Tone.NEUTRAL, syncDot(idle, listOf(SessionBackup(BackupState.WAITING_FOR_WIFI))).first)
        assertEquals(Tone.ERROR, syncDot(SyncStatus.Error("no access"), listOf(SessionBackup(BackupState.BACKED_UP))).first)
    }

    @Test
    fun `days left count from the sync engine's erase date`() {
        val deletedAt = 1_000_000L
        assertEquals(1, daysLeft(deletedAt, RecentlyDeleted.erasesAt(deletedAt) - 1))
        assertEquals(0, daysLeft(deletedAt, RecentlyDeleted.erasesAt(deletedAt)))
    }

    @Test
    fun `resume goes back to the session each room showed, else its latest open one`() {
        val sessions = listOf(
            session("old", last = 1),
            session("new", last = 5),
            session("merged", status = SessionStatus.ON_MAIN, last = 9),
            session("c1", agent = "codex", last = 3),
        )
        val active = mapOf("claude" to "old", "gemini" to "gone")
        assertEquals(
            listOf("claude" to "old", "codex" to "c1"),
            WorkText.resumeTargets(listOf("claude", "codex", "gemini", "claude"), sessions) { active[it] },
        )
        assertEquals(listOf("claude" to "new"), WorkText.resumeTargets(listOf("claude"), sessions) { null })
    }

    @Test
    fun `work chips say working and warn about sleep in the last ten minutes`() {
        val now = 1_000_000L
        assertEquals(emptyList<Told>(), WorkText.chips("Claude", null, now))
        assertEquals(emptyList<Told>(), WorkText.chips("Claude", RoomWork(emptySet(), now, now + 30 * 60_000), now))
        assertEquals(
            listOf(Told("working", Tone.OK)),
            WorkText.chips("Claude", RoomWork(setOf("turn"), now, null), now),
        )
        assertEquals(
            listOf(Told("Claude room sleeps in 4 min", Tone.WARN)),
            WorkText.chips("Claude", RoomWork(emptySet(), now, now + 3 * 60_000 + 1), now),
        )
    }

    @Test
    fun `the owner's own stop is no banner`() {
        val stops = mapOf(
            "claude" to RoomStop(StopReason.OWNER, 1, "Stopped."),
            "codex" to RoomStop(StopReason.IDLE, 2, "Stopped after 30 minutes. Nothing was lost."),
        )
        assertEquals(setOf("codex"), WorkText.newsworthy(stops).keys)
    }
}
