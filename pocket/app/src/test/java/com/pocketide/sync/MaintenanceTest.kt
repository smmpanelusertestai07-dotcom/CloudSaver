package com.pocketide.sync

import com.pocketide.core.AppDirs
import com.pocketide.core.Settings
import com.pocketide.model.Project
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MaintenanceTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val day = Durations.DAY
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private fun phone(settings: Settings = Settings(onboardingDone = true)) = TestPhone(accounts, clock, settings = settings)

    @Test
    fun phoneCopiesOfOldChatsGoOnlyOnceDriveHasThemAndComeBackWhenOpened() = runBlocking {
        val phone = phone()
        phone.sessions += session("s1", at = clock.now, ref = "c0ffee00-1111")
        phone.sessions += session("s2", at = clock.now, ref = "feedbeef-2222")
        val synced = phone.homeFile("claude", path).apply { writeText("all in Drive\n") }
        val shot = phone.mediaFile("claude", "owner/app", "s1", "shot.png").apply {
            writeBytes(ByteArray(10) { 3 })
            setLastModified(clock.now)
        }
        val unsynced = phone.homeFile("claude", claudeTranscript("owner/app", "s2", "feedbeef-2222")).apply { writeText("start\n") }
        phone.engine.syncNow()
        unsynced.appendText("not yet in Drive\n")

        clock.advance(31 * day)
        phone.drive.offline = true
        phone.engine.syncNow()
        phone.drive.offline = false
        phone.engine.runMaintenance()

        assertFalse(synced.exists())
        assertFalse(shot.exists())
        assertTrue("never deleted before Drive confirmed it", unsynced.exists())
        assertFalse(phone.state().tracks.values.single { it.path == path }.onPhone)
        assertTrue("the phone's clean-up is not a delete", phone.remoteIndex()!!.objects.any { it.path == path })

        phone.engine.fetchSession("s1")
        assertEquals("all in Drive\n", synced.readText())
        assertTrue(shot.exists())
    }

    @Test
    fun cachesOfIdleProjectsGoWhenGitIgnoresThem() {
        val phone = phone()
        val cleaner = LocalCleaner(phone.dirs)
        val idle = phone.dirs.worktree("claude", "owner/idle", "s-idle").apply { mkdirs() }
        File(idle, ".gitignore").writeText("node_modules/\nbuild/\n")
        File(idle, "node_modules/pkg/index.js").apply { parentFile?.mkdirs(); writeText("x".repeat(100)) }
        File(idle, "app/build/out.bin").apply { parentFile?.mkdirs(); writeText("y".repeat(50)) }
        File(idle, "target/keep.txt").apply { parentFile?.mkdirs(); writeText("not ignored") }
        val active = phone.dirs.worktree("claude", "owner/idle", "s-active").apply { mkdirs() }
        File(active, ".gitignore").writeText("node_modules/\n")
        File(active, "node_modules/a.js").apply { parentFile?.mkdirs(); writeText("z") }
        val busy = phone.dirs.worktree("claude", "owner/busy", "s-busy").apply { mkdirs() }
        File(busy, ".gitignore").writeText("node_modules/\n")
        File(busy, "node_modules/b.js").apply { parentFile?.mkdirs(); writeText("z") }

        val activity = mapOf(AppDirs.projectDirName("owner/idle") to clock.now - 31 * day, AppDirs.projectDirName("owner/busy") to clock.now - day)
        val freed = cleaner.caches(clock.now, 30, { activity[it] }, active = setOf("s-active"), force = false)

        assertEquals(150L, freed)
        assertFalse(File(idle, "node_modules").exists())
        assertFalse(File(idle, "app/build").exists())
        assertTrue(File(idle, "target/keep.txt").exists())
        assertTrue("a session open in a room is never cleaned", File(active, "node_modules/a.js").exists())
        assertTrue(File(busy, "node_modules/b.js").exists())
    }

    @Test
    fun onlyTheLastThreeBuildsAndSevenDaysOfTempAndLogsAreKept() {
        val phone = phone()
        val cleaner = LocalCleaner(phone.dirs)
        val builds = File(phone.dirs.builds, "owner__app")
        for (i in 1..5) File(builds, "build-$i.apk").apply { parentFile?.mkdirs(); writeText("apk$i"); setLastModified(clock.now - (10 - i) * day) }
        val oldLog = File(phone.dirs.logs, "old.log").apply { parentFile?.mkdirs(); writeText("old"); setLastModified(clock.now - 8 * day) }
        val newLog = File(phone.dirs.logs, "new.log").apply { writeText("new"); setLastModified(clock.now - day) }

        cleaner.builds()
        cleaner.oldFiles(clock.now, Durations.days(Maintenance.TEMP_DAYS), roomsRunning = false)

        assertEquals(setOf("build-3.apk", "build-4.apk", "build-5.apk"), builds.list()!!.toSet())
        assertFalse(oldLog.exists())
        assertTrue(newLog.exists())
    }

    @Test
    fun anUnusedComputerGetsANoticeADayBeforeAndIsRemovedOnlyWhenAllIsSynced() = runBlocking {
        val phone = phone()
        phone.sessions += session("s1", at = clock.now - 91 * day, ref = "c0ffee00-1111")
        phone.projects += Project(id = "owner/app", owner = "owner", repo = "app", addedAt = 0, lastActivityAt = clock.now - 91 * day)
        File(phone.dirs.rootfs, "usr/bin/bash").apply { parentFile?.mkdirs(); writeText("bash") }
        phone.homeFile("claude", path).writeText("done\n")

        phone.engine.runMaintenance()
        assertTrue(phone.notifier.posted.any { it.key == "computer" && it.title == "The computer will be removed" })
        val due = phone.engine.computerRemovalAt.value
        assertEquals(clock.now + 7 * day, due)
        assertTrue(phone.dirs.rootfs.exists())

        clock.advance(6 * day + Durations.HOUR)
        phone.engine.runMaintenance()
        assertTrue(phone.notifier.posted.any { it.title == "The computer is removed tomorrow" })
        assertTrue(phone.dirs.rootfs.exists())

        // Something is still waiting for Drive: the computer stays.
        clock.advance(day)
        phone.drive.offline = true
        phone.homeFile("claude", path).appendText("late line\n")
        phone.engine.syncNow()
        phone.drive.offline = false
        phone.network.online = false
        phone.engine.runMaintenance()
        assertTrue(phone.dirs.rootfs.exists())

        phone.network.online = true
        phone.engine.syncNow()
        assertEquals(0, phone.computerRemovals)
        phone.engine.runMaintenance()
        assertEquals("the computer module removes it, so it stops its programs and says it is not set up", 1, phone.computerRemovals)
        assertFalse(phone.dirs.rootfs.exists())
        assertNull(phone.engine.computerRemovalAt.value)
        assertTrue(phone.notifier.posted.any { it.title == "The computer was removed" })
    }

    @Test
    fun agentWorkCancelsTheComputerNotice() = runBlocking {
        val phone = phone()
        phone.sessions += session("s1", at = clock.now - 95 * day)
        File(phone.dirs.rootfs, "etc/os-release").apply { parentFile?.mkdirs(); writeText("ubuntu") }
        phone.engine.runMaintenance()
        assertTrue(phone.engine.computerRemovalAt.value != null)
        phone.sessions[0] = phone.sessions[0].copy(lastActivityAt = clock.now)
        clock.advance(8 * day)
        phone.engine.runMaintenance()
        assertTrue(phone.dirs.rootfs.exists())
        assertNull(phone.engine.computerRemovalAt.value)
    }

    @Test
    fun afterARekeyOlderObjectsAreEncryptedAgainOnWifi() = runBlocking {
        val phone = phone()
        phone.sessions += session("s1", at = clock.now, ref = "c0ffee00-1111")
        phone.homeFile("claude", path).writeText("chat\n")
        phone.homeFile("claude", ".claude/CLAUDE.md").writeText("rules")
        phone.engine.syncNow()
        assertTrue(phone.drive.files.values.filter { it.name.startsWith("o-") }.all { FakeCipher.generationOf(it.bytes) == 1 })

        phone.cipher.generation = 2
        phone.network.metered = true
        phone.engine.runMaintenance()
        assertTrue("never on mobile data", phone.remoteIndex()!!.objects.all { it.keyGeneration == 1 })

        phone.network.metered = false
        phone.engine.runMaintenance()
        val index = phone.remoteIndex()!!
        assertTrue(index.objects.all { it.keyGeneration == 2 })
        assertTrue(phone.drive.files.values.all { FakeCipher.generationOf(it.bytes) == 2 })
        val other = TestPhone(accounts, clock, deviceId = "b", deviceName = "B")
        other.engine.fetchSession("s1")
        assertEquals("chat\n", other.homeFile("claude", path).readText())
    }

    @Test
    fun orphanedUploadsAreSweptButTheVaultsFilesAreNeverTouched() = runBlocking {
        val phone = phone()
        phone.homeFile("claude", ".claude/CLAUDE.md").writeText("rules")
        phone.engine.syncNow()
        phone.drive.uploadBytes("o-orphanorphanorphanorphan00", byteArrayOf(1))
        phone.drive.uploadBytes("keyhalf-d", byteArrayOf(2))
        phone.drive.uploadBytes("keycheck", byteArrayOf(3))
        phone.drive.uploadBytes("keyhistory", byteArrayOf(4))

        phone.engine.runMaintenance()
        assertTrue("a day's grace first", "o-orphanorphanorphanorphan00" in phone.drive.objectNames())
        clock.advance(day + Durations.MINUTE)
        phone.engine.runMaintenance()
        assertFalse("o-orphanorphanorphanorphan00" in phone.drive.objectNames())
        assertEquals(1, phone.drive.named("keyhalf-d").size)
        assertEquals(1, phone.drive.named("keycheck").size)
        assertEquals(1, phone.drive.named("keyhistory").size)
        assertEquals(1, phone.remoteIndex()!!.objects.size)
    }
}
