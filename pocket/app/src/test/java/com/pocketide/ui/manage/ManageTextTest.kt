package com.pocketide.ui.manage

import com.pocketide.core.Settings
import com.pocketide.github.NotConnectedException
import com.pocketide.google.DriveException
import com.pocketide.model.Guard
import com.pocketide.rooms.RoomState
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ManageTextTest {
    @Test
    fun `every guard has a plain sentence and a matching colour`() {
        val tones = Guard.entries.associateWith { ManageText.guard(it).tone }
        assertEquals(Tone.OK, tones[Guard.OK])
        assertEquals(Tone.WARN, tones[Guard.NO_NEW_HEAVY])
        assertEquals(Tone.ERROR, tones[Guard.SAFE_STOP])
        Guard.entries.forEach { assertTrue(ManageText.guard(it).text.endsWith(".")) }
    }

    @Test
    fun `sync states read plainly`() {
        val time = { ms: Long -> "T$ms" }
        val bytes = { b: Long -> "${b}B" }
        assertEquals("Up to date, as of T5.", ManageText.sync(SyncStatus.UpToDate(5), time, bytes).text)
        val waiting = ManageText.sync(SyncStatus.Waiting("Google storage is full.", 7, 42), time, bytes)
        assertEquals("Google storage is full. 42B waits safely on this phone since T7.", waiting.text)
        assertEquals(Tone.WARN, waiting.tone)
        assertEquals(Tone.ERROR, ManageText.sync(SyncStatus.Error("No access"), time, bytes).tone)
    }

    @Test
    fun `rooms show memory only when known`() {
        val bytes = { b: Long -> "${b}B" }
        assertEquals("Running · 9B", ManageText.room(RoomState.Running("u", null, 9), bytes).text)
        assertEquals("Running", ManageText.room(RoomState.Running("u", null, 0), bytes).text)
        assertEquals(Tone.NEUTRAL, ManageText.room(RoomState.Stopped, bytes).tone)
    }

    @Test
    fun `retention lines follow the settings`() {
        val defaults = ManageText.retention(Settings()).toMap()
        assertEquals("Until you delete them", defaults["Chats in Drive"])
        assertEquals("30 days after Drive has them", defaults["Chats on this phone"])
        val changed = ManageText.retention(Settings(keepChatsMonths = 12, phoneMediaDays = -1, computerUnusedDays = -1)).toMap()
        assertEquals("12 months after the last message", changed["Chats in Drive"])
        assertEquals("Keep all", changed["Media on this phone"])
        assertEquals("Never removed", changed["Unused computer"])
    }

    @Test
    fun `errors become one plain sentence`() {
        assertEquals("GitHub is not connected. Reconnect it in Settings.", PlainError.of(NotConnectedException("x")))
        assertEquals(
            "The app's owner must add the GitHub App client ID to the build.",
            PlainError.of(NotConnectedException("The app's owner must add the GitHub App client ID to the build.")),
        )
        assertTrue(PlainError.of(IOException("reset by peer")).startsWith("No connection"))
        assertEquals(PlainError.OUT_OF_SPACE, PlainError.of(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(PlainError.OUT_OF_SPACE, PlainError.of(IOException("copy failed", IOException("No space left on device"))))
        assertEquals(PlainError.OUT_OF_SPACE, PlainError.local(IOException("No space left on device"), "fallback"))
        assertEquals("This file is too large to edit on the phone.", PlainError.local(IOException("This file is too large to edit on the phone."), "fallback"))
        assertEquals("fallback", PlainError.local(IOException("EACCES"), "fallback"))
        assertTrue(PlainError.of(DriveException.StorageFull()).contains("full"))
        assertEquals("The phone is too hot.", PlainError.of(IllegalStateException("The phone is too hot")))
        assertEquals(PlainError.GENERIC, PlainError.of(IllegalStateException("stub")))
        assertEquals(PlainError.GENERIC, PlainError.of(RuntimeException("java.lang.NullPointerException at x")))
        assertEquals(PlainError.GENERIC, PlainError.of(RuntimeException()))
    }

    @Test
    fun `module messages never leak tokens`() {
        val text = PlainError.readable("Push failed for ghp_" + "a".repeat(36))
        assertFalse(text!!.contains("ghp_"))
        assertNull(PlainError.readable("lowercase start is not a sentence"))
    }
}
