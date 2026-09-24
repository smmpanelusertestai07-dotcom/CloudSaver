package com.pocketide.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

/** The small parsers: script lines, /proc facts, versions, and the files written into Linux. */
class GuestFactsTest {
    @Test
    fun scriptLines() {
        assertEquals(GuestLine.Progress(30), GuestLines.parse("pocketide-progress 30"))
        assertEquals(GuestLine.Progress(100), GuestLines.parse("pocketide-progress 250"))
        assertEquals(GuestLine.Fixed(3), GuestLines.parse("pocketide-fixed 3"))
        assertEquals(GuestLine.Installed(13), GuestLines.parse("pocketide-installed 13"))
        assertEquals(GuestLine.Apt(false, 45.5f, "Retrieving file 3 of 9"), GuestLines.parse("dlstatus:3:45.5:Retrieving file 3 of 9"))
        assertEquals(GuestLine.Apt(true, 80f, "Installing git (arm64)"), GuestLines.parse("pmstatus:git:80:Installing git (arm64)"))
        assertEquals(GuestLine.Fetched(27_900_000), GuestLines.parse("Fetched 27.9 MB in 30s (930 kB/s)"))
        assertEquals(GuestLine.Fetched(812_000), GuestLines.parse("Fetched 812 kB in 1s (812 kB/s)"))
        assertEquals(GuestLine.Text("pocketide-fixed 12345678901"), GuestLines.parse("pocketide-fixed 12345678901"))
        assertEquals(GuestLine.Text("Done"), GuestLines.parse("\u001B[1;32mDone\u001B[0m\r"))
    }

    @Test
    fun longLinesAreShortenedForTheScreen() {
        val shown = (GuestLines.parse("x".repeat(500)) as GuestLine.Text).text
        assertEquals(160, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun outputIsSplitIntoLinesWithoutUnboundedMemory() {
        val lines = mutableListOf<String>()
        ByteArrayInputStream("one\r\ntwo\n${"z".repeat(40)}".toByteArray()).forEachLine(maxChars = 16) { lines += it }
        assertEquals(listOf("one", "two", "z".repeat(16), "z".repeat(16), "z".repeat(8)), lines)
    }

    @Test
    fun cpuFromHardwareLineAndCoreParts() {
        val cpuinfo = buildString {
            repeat(6) { append("processor\t: $it\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n\n") }
            repeat(2) { append("processor\t: ${it + 6}\nCPU implementer\t: 0x41\nCPU part\t: 0xd0a\n\n") }
            append("Hardware\t: MT6769Z\n")
        }
        assertEquals("MT6769Z · 6× Cortex-A55 + 2× Cortex-A75", CpuFacts.describe(cpuinfo, null))
        assertEquals("MediaTek MT6769Z · 6× Cortex-A55 + 2× Cortex-A75", CpuFacts.describe(cpuinfo, "MediaTek MT6769Z"))
        assertEquals("Snapdragon · 4× Kryo 4xx Gold", CpuFacts.describe("model name : Snapdragon\n" + "CPU implementer : 0x51\nCPU part : 0x804\n".repeat(4), null))
        assertEquals("arm64 processor", CpuFacts.describe(null, null))
    }

    @Test
    fun coresFromThePossibleCpus() {
        assertEquals(8, CpuFacts.cores("0-7\n"))
        assertEquals(6, CpuFacts.cores("0-3,6-7"))
        assertEquals(1, CpuFacts.cores("0"))
        assertNull(CpuFacts.cores("7-3"))
        assertNull(CpuFacts.cores("x"))
        assertNull(CpuFacts.cores(null))
    }

    @Test
    fun addressSpaceBitsFromTheMemoryMap() {
        val phone = "5570000000-5570100000 r-xp 00000000 fd:00 1 /system/bin/app_process64\n" +
            "7fe0000000-7fe0021000 rw-p 00000000 00:00 0 [stack]\n"
        val server = "aaaad0000000-aaaad0100000 r-xp 0 0 0 /usr/bin/java\nffffb0000000-ffffb0021000 rw-p 0 0 0 [stack]\n"
        assertEquals(39, KernelFacts.vaBits(phone))
        assertEquals(48, KernelFacts.vaBits(server))
        assertEquals(39, KernelFacts.vaBits(phone + "ffffffffff600000-ffffffffff601000 --xp 0 0 0 [vsyscall]\n"))
        assertNull(KernelFacts.vaBits(""))
    }

    @Test
    fun versionsReadFromLinux() {
        assertEquals("Ubuntu 24.04.5 LTS", GuestFacts.prettyName("NAME=\"Ubuntu\"\nPRETTY_NAME=\"Ubuntu 24.04.5 LTS\"\n"))
        assertNull(GuestFacts.prettyName("NAME=x\n"))
        assertEquals("4.138.0", GuestFacts.packageVersion("{\"name\":\"code-server\",\"version\":\"4.138.0\"}"))
        assertNull(GuestFacts.packageVersion("not json"))
        assertEquals("1.2.10", GuestFacts.version("agy 1.2.10 (4751581200121856)"))
        assertNull(GuestFacts.version("no digits"))
    }

    @Test
    fun resolverUsesOnlyThePhonesUsableServers() {
        val text = GuestConfig.resolvConf(
            listOf(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("192.168.1.1"),
                InetAddress.getByName("2001:4860:4860::8888"),
                InetAddress.getByName("192.168.1.1"),
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1"),
            ),
        )
        val servers = text.orEmpty().lines().filter { it.startsWith("nameserver ") }
        assertEquals(listOf("nameserver 192.168.1.1", "nameserver 2001:4860:4860:0:0:0:0:8888", "nameserver 8.8.8.8"), servers)
        assertNull(GuestConfig.resolvConf(emptyList()))
        assertNull(GuestConfig.resolvConf(listOf(InetAddress.getByName("0.0.0.0"))))
    }

    @Test
    fun procStandInsOnlyForWhatAndroidHides() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        val hidden = setOf("/proc/stat", "/proc/loadavg")
        val binds = ProcStandIns(directory, cores = 4) { it !in hidden }.binds()
        assertEquals(listOf("/proc/loadavg", "/proc/stat"), binds.keys.toList())
        val stat = java.io.File(binds.getValue("/proc/stat")).readText()
        assertEquals(4, stat.lines().count { it.matches(Regex("cpu\\d+ .*")) })
        assertTrue(ProcStandIns(directory, cores = 4) { true }.binds().isEmpty())
        directory.deleteRecursively()
    }
}
