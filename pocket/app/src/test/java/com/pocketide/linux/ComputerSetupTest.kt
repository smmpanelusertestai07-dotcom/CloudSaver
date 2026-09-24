package com.pocketide.linux

import com.pocketide.model.Decision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths

class ComputerSetupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var world: SetupWorld

    @Before
    fun setUp() {
        world = SetupWorld(temp.root)
    }

    private fun install(setup: ComputerSetup = world.setup()) = runBlocking { setup.install() }

    private fun installing() = world.published.filterIsInstance<ComputerState.Installing>()

    @Test
    fun waitsForWifiWithoutFetchingAnything() {
        world.host.decision = Decision.no("Waiting for Wi-Fi…")
        val end = install()
        assertEquals("Waiting for Wi-Fi…", (end as ComputerState.Broken).why)
        assertEquals(end, world.published.last())
        assertTrue(world.fetcher.fetched.isEmpty())
        assertFalse(world.rootfs.exists())
        // The whole set-up is asked for at once, as a big download for set-up.
        val (bytes, kind) = world.host.asked.single()
        assertEquals("setup", kind)
        assertTrue(bytes >= world.ubuntu.bytes + world.codeServer.bytes)
    }

    @Test
    fun refusesToStartWithoutRoom() {
        world.host.free = 100_000_000
        val end = install() as ComputerState.Broken
        assertEquals("There is not enough free space for the computer.", end.why)
        assertTrue(world.fetcher.fetched.isEmpty())
    }

    @Test
    fun buildsTheComputerStepByStep() {
        assertEquals(ComputerState.Ready, install())
        assertEquals(ComputerState.Ready, world.published.last())

        val steps = installing().map { it.step }.distinct()
        for (expected in listOf("Downloading Ubuntu 24.04.5…", "Unpacking Ubuntu…", "Preparing Ubuntu…", "Downloading code-server 4.138.0…", "Unpacking code-server…", "Checking that code-server starts…")) {
            assertTrue("missing step $expected in $steps", expected in steps)
        }
        val fractions = installing().mapNotNull { it.fraction }
        assertEquals(fractions.sorted(), fractions)
        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(world.ubuntu.bytes + world.codeServer.bytes, installing().last().bytesTotal)
        assertEquals(world.ubuntu.bytes + world.codeServer.bytes, installing().maxOf { it.bytesDone })

        // What the phone provides, written before anything ran inside.
        assertEquals("nameserver 192.168.1.1", world.guestText("/etc/resolv.conf")?.lines()?.firstOrNull { it.startsWith("nameserver") })
        assertTrue(world.guestText("/etc/gai.conf").orEmpty().contains("precedence ::ffff:0:0/96  100"))
        assertTrue(world.guestText("/etc/hosts").orEmpty().contains("127.0.0.1\tlocalhost pocketide"))
        assertEquals("#!/bin/bash\n# bootstrap v1\n", world.guestText("/opt/pocketide/bootstrap.sh"))
        assertNotNull(world.guestText("/opt/pocketide/launch.pl"))

        // code-server behind a relative link, so the rootfs can move without breaking it.
        val link = File(world.rootfs, "opt/code-server").toPath()
        assertEquals(Paths.get("code-server-4.138.0"), Files.readSymbolicLink(link))
        assertEquals("code-server 4.138.0\n", world.guestText("/opt/code-server/bin/code-server"))

        val record = world.savedRecord()
        assertEquals("24.04.5", record.ubuntu)
        assertEquals(world.ubuntu.sha256, record.base)
        assertEquals(Downloader.sha256(world.assets.read("bootstrap.sh")), record.bootstrap)
        assertEquals("4.138.0", record.codeServer)
        assertEquals(world.codeServer.sha256, record.codeServerSha256)
        assertEquals(SetupWorld.NOW, record.readyAt)

        // Archives go once unpacked; nothing half-made stays beside the rootfs.
        assertTrue(world.downloads.listFiles().isNullOrEmpty())
        assertFalse(File(world.rootfs.parentFile, "rootfs.partial").exists())
        assertEquals(listOf(1_500_000L to "setup"), world.host.transfers)
        assertEquals(ComputerState.Ready, world.setup().stateOnDisk())
    }

    @Test
    fun continuesWhereAStoppedSetUpLeftOff() {
        world.guest.bootstrapFails = true
        val first = install() as ComputerState.Broken
        assertEquals("Setting up Ubuntu stopped: Could not reach Ubuntu's servers.", first.why)
        assertNull(world.savedRecord().readyAt)
        assertEquals(ComputerState.NotInstalled, world.setup().stateOnDisk())

        world.guest.bootstrapFails = false
        assertEquals(ComputerState.Ready, install())
        assertEquals(1, world.fetcher.fetched.count { it == world.ubuntu.url })
        assertEquals(1, world.fetcher.fetched.count { it == world.codeServer.url })
    }

    @Test
    fun aKilledUnpackIsCleanedUpAndDoneAgain() {
        val staging = File(world.rootfs.parentFile, "rootfs.partial").apply { mkdirs() }
        File(staging, "half-written").writeText("x")
        val trash = File(world.rootfs.parentFile, "rootfs.trash-123").apply { mkdirs() }
        File(trash, "old").writeText("x")
        assertEquals(ComputerState.Ready, install())
        assertFalse(staging.exists())
        assertFalse(trash.exists())
        assertFalse(File(world.rootfs, "half-written").exists())
    }

    @Test
    fun aDownloadThatDoesNotMatchStopsWithThatReason() {
        world.fetcher.mismatch = world.ubuntu.url
        val end = install() as ComputerState.Broken
        assertTrue(end.why, end.why.contains("did not match its published checksum"))
        assertFalse(world.rootfs.exists())
    }

    @Test
    fun aCodeServerThatDoesNotStartIsNotCalledReady() {
        world.fetcher.archives[world.codeServer.url] = TarBuilder()
            .file("code-server-4.138.0-linux-arm64/bin/code-server", "broken\n")
            .gz()
        val end = install() as ComputerState.Broken
        assertEquals("code-server did not start.", end.why)
        assertNull(world.savedRecord().readyAt)
    }

    @Test
    fun theDiskDecidesTheStateAtStart() {
        assertEquals(ComputerState.NotInstalled, world.setup().stateOnDisk())
        install()
        File(world.rootfs, "opt/code-server-4.138.0/bin/code-server").delete()
        val state = world.setup().stateOnDisk()
        assertEquals("Part of the computer is missing.", (state as ComputerState.Broken).why)
    }

    @Test
    fun installingAReadyComputerOnlyRefreshesWhatThePhoneProvides() {
        install()
        File(world.rootfs, "etc/hosts").writeText("10.0.0.5 myserver\n")
        world.host.dns = listOf(InetAddress.getByName("8.8.4.4"))
        val runsBefore = world.guest.ran.size
        val fetchesBefore = world.fetcher.fetched.size

        assertEquals(ComputerState.Ready, install())
        assertEquals(runsBefore, world.guest.ran.size)
        assertEquals(fetchesBefore, world.fetcher.fetched.size)
        assertEquals("10.0.0.5 myserver\n", world.guestText("/etc/hosts"))
        assertTrue(world.guestText("/etc/resolv.conf").orEmpty().contains("nameserver 8.8.4.4"))
    }

    @Test
    fun removeDeletesTheComputerAndItsDownloadsButKeepsEveryRoom() {
        install()
        val signIn = File(world.rooms, "claude/home/.claude/.credentials.json").apply { parentFile?.mkdirs(); writeText("{}") }
        val history = File(world.rooms, "codex/home/.codex/sessions/1.jsonl").apply { parentFile?.mkdirs(); writeText("{}") }
        File(world.downloads, world.codeServer.url.substringAfterLast('/') + ".part").apply { parentFile?.mkdirs(); writeText("half") }

        runBlocking { world.setup().remove() }

        assertFalse(world.rootfs.exists())
        assertFalse(world.record.exists())
        assertTrue(world.downloads.listFiles().isNullOrEmpty())
        assertEquals(ComputerState.NotInstalled, world.published.last())
        // The reset sheet says sign-ins and chat history stay: they do.
        assertTrue(ResetPlan.STANDARD.keeps.any { it.contains("sign-in") && it.contains("chat history") })
        assertEquals("{}", signIn.readText())
        assertEquals("{}", history.readText())
    }

    @Test
    fun updateBaseWaitsForWifiAndCountsTheFixes() {
        val setup = world.setup()
        runBlocking {
            assertEquals(UpdateOutcome.Waiting("The computer is not set up yet."), setup.updateBase())
            setup.install()
            world.host.decision = Decision.no("Waiting for Wi-Fi…")
            assertEquals(UpdateOutcome.Waiting("Waiting for Wi-Fi…"), setup.updateBase())
            world.host.decision = Decision.YES
            assertEquals(UpdateOutcome.Updated("Installed 2 security fixes."), setup.updateBase())
            world.guest.securityFixes = 0
            assertEquals(UpdateOutcome.UpToDate, setup.updateBase())
        }
        assertEquals(ComputerState.Ready, world.published.last())
        assertTrue(world.published.any { it is ComputerState.Updating })
        assertTrue(2_000_000L to "update" in world.host.transfers)
    }

    @Test
    fun anAppUpdateWithANewBootstrapRunsItAgain() {
        install()
        world.assets.bootstrap = "#!/bin/bash\n# bootstrap v2\n"
        world.guest.securityFixes = 1
        val outcome = runBlocking { world.setup().updateBase() }
        assertEquals(UpdateOutcome.Updated("Refreshed Ubuntu's set-up and installed 1 security fix."), outcome)
        assertEquals(Downloader.sha256(world.assets.read("bootstrap.sh")), world.savedRecord().bootstrap)
        assertEquals("#!/bin/bash\n# bootstrap v2\n", world.guestText("/opt/pocketide/bootstrap.sh"))
    }

    @Test
    fun codeServerUpdateSwitchesOnlyToOneThatStarts() {
        install()
        val setup = world.setup()
        var released = 0
        val same = runBlocking { setup.updateCodeServer(world.codeServer, hold = { true }, release = { released++ }) }
        assertEquals(UpdateOutcome.UpToDate, same)

        val broken = world.codeServerRelease("4.139.0", starts = false)
        val refused = runBlocking { setup.updateCodeServer(broken, hold = { true }, release = { released++ }) }
        assertEquals(UpdateOutcome.Failed("code-server 4.139.0 did not start, so code-server 4.138.0 stays."), refused)
        assertEquals(Paths.get("code-server-4.138.0"), Files.readSymbolicLink(File(world.rootfs, "opt/code-server").toPath()))
        assertFalse(File(world.rootfs, "opt/code-server-4.139.0").exists())

        val good = world.codeServerRelease("4.140.0")
        val updated = runBlocking { setup.updateCodeServer(good, hold = { true }, release = { released++ }) }
        assertEquals(UpdateOutcome.Updated("code-server is now 4.140.0."), updated)
        assertEquals(Paths.get("code-server-4.140.0"), Files.readSymbolicLink(File(world.rootfs, "opt/code-server").toPath()))
        assertFalse(File(world.rootfs, "opt/code-server-4.138.0").exists())
        assertEquals("4.140.0", world.savedRecord().codeServer)
        assertEquals(1, released)
    }

    @Test
    fun codeServerWaitsForTheRoomsAndKeepsWhatItUnpacked() {
        install()
        val setup = world.setup()
        val next = world.codeServerRelease("4.140.0")
        val waiting = runBlocking { setup.updateCodeServer(next, hold = { false }, release = {}) }
        assertTrue(waiting is UpdateOutcome.Waiting)
        assertTrue(File(world.rootfs, "opt/code-server-4.140.0/bin/code-server").exists())
        assertEquals("4.138.0", world.savedRecord().codeServer)

        val fetches = world.fetcher.fetched.size
        val updated = runBlocking { setup.updateCodeServer(next, hold = { true }, release = {}) }
        assertEquals(UpdateOutcome.Updated("code-server is now 4.140.0."), updated)
        assertEquals(fetches, world.fetcher.fetched.size)
    }

    @Test
    fun codeServerSwitchesBackWhenTheNewOneFailsBehindTheLink() {
        install()
        val setup = world.setup()
        val next = world.codeServerRelease("4.140.0")
        world.guest.linkStarts = false
        val outcome = runBlocking { setup.updateCodeServer(next, hold = { true }, release = {}) }
        assertEquals(UpdateOutcome.Failed("code-server 4.140.0 did not start after the switch, so code-server 4.138.0 stays."), outcome)
        assertEquals(Paths.get("code-server-4.138.0"), Files.readSymbolicLink(File(world.rootfs, "opt/code-server").toPath()))
        assertEquals("4.138.0", world.savedRecord().codeServer)
    }

    @Test
    fun repairReportsEachPartAndPutsBackWhatIsMissing() {
        install()
        File(world.rootfs, "etc/hosts").delete()
        File(world.rootfs, "opt/pocketide/update.sh").writeText("changed")
        File(world.rootfs, "etc/gai.conf").writeText("# edited by the owner\n")

        val report = runBlocking { world.setup().repair() }.associateBy { it.what }

        assertEquals(RepairStatus.NEW, report.getValue("Host name and network files").status)
        assertEquals("Written again: /etc/hosts.", report.getValue("Host name and network files").detail)
        assertEquals("# edited by the owner\n", world.guestText("/etc/gai.conf"))
        assertEquals(RepairStatus.OK, report.getValue("DNS").status)
        assertEquals(RepairStatus.NEW, report.getValue("PocketIDE's scripts").status)
        assertEquals(RepairStatus.OK, report.getValue("Tools and settings").status)
        assertEquals(RepairStatus.NEW, report.getValue("Ubuntu's security fixes").status)
        assertEquals(RepairStatus.OK, report.getValue("code-server").status)
        assertEquals(ComputerState.Ready, world.published.last())
    }

    @Test
    fun repairWaitsForWifiForWhatDownloads() {
        install()
        world.host.decision = Decision.no("Waiting for Wi-Fi…")
        val report = runBlocking { world.setup().repair() }.associateBy { it.what }
        assertEquals(RepairStatus.NOTE, report.getValue("Tools and settings").status)
        assertEquals(RepairStatus.NOTE, report.getValue("Ubuntu's security fixes").status)
        assertEquals(RepairStatus.OK, report.getValue("code-server").status)
    }

    @Test
    fun repairUnpacksACodeServerThatNoLongerStarts() {
        install()
        File(world.rootfs, "opt/code-server-4.138.0/bin/code-server").writeText("broken\n")
        val report = runBlocking { world.setup().repair() }.associateBy { it.what }
        assertEquals(RepairStatus.NEW, report.getValue("code-server").status)
        assertEquals("code-server 4.138.0\n", world.guestText("/opt/code-server/bin/code-server"))
    }

    @Test
    fun repairOfAnUnfinishedComputerFinishesTheSetUp() {
        world.guest.bootstrapFails = true
        install()
        world.guest.bootstrapFails = false
        val report = runBlocking { world.setup().repair() }
        assertEquals(listOf(RepairItem("The computer", RepairStatus.NEW, "The parts that were missing are set up again.")), report)
        assertEquals(ComputerState.Ready, world.setup().stateOnDisk())
    }
}
