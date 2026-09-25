package com.pocketide.ui.screens.project

import com.pocketide.sync.BackupState
import com.pocketide.sync.SessionBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The session's chip counts the videos the sync engine holds back for Wi-Fi. */
class WaitingVideosTest {
    @Test
    fun aVideoHeldForWifiShowsOnTheSession() {
        val backup = SessionBackup(BackupState.WAITING_FOR_WIFI, pendingBytes = 40_000_000, videosWaitingForWifi = 1)
        assertEquals("1 video waiting for Wi-Fi", waitingVideosChip(backup))
        assertEquals("3 videos waiting for Wi-Fi", waitingVideosChip(backup.copy(videosWaitingForWifi = 3)))
    }

    @Test
    fun noChipWithoutAHeldVideo() {
        assertNull(waitingVideosChip(null))
        assertNull(waitingVideosChip(SessionBackup(BackupState.WAITING, pendingBytes = 10)))
        assertNull(waitingVideosChip(SessionBackup(BackupState.BACKED_UP)))
    }
}
