package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class RoomProcessesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fakeProc(): File {
        val proc = temp.newFolder("proc")
        fun process(pid: Int, name: String, parent: Int, utime: Long, stime: Long, rssKb: Long) {
            File(proc, "$pid").mkdirs()
            // Fields after ")": state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime ...
            File(proc, "$pid/stat").writeText("$pid ($name) S $parent 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0 20 0 1 0")
            File(proc, "$pid/status").writeText("Name:\t$name\nVmRSS:\t   $rssKb kB\n")
        }
        process(100, "libproot.so", 1, 10, 5, 1000)
        process(101, "node (code) server", 100, 200, 50, 20000)
        process(102, "claude", 101, 30, 3, 50000)
        process(200, "other room", 1, 999, 999, 99999)
        File(proc, "self").mkdirs()
        return proc
    }

    @Test fun `a room is its proot and everything under it`() {
        val facts = ProcFacts(fakeProc())
        assertEquals(setOf(100, 101, 102), facts.tree(100).toSet())
        assertEquals(10L + 5 + 200 + 50 + 30 + 3, facts.cpuTicks(facts.tree(100)))
        assertEquals((1000L + 20000 + 50000) * 1024, facts.residentBytes(facts.tree(100)))
        assertEquals(listOf(999), facts.tree(999))
        assertEquals(0L, facts.cpuTicks(listOf(999)))
    }

    @Test fun `the pid is read from the process description`() {
        val process = ProcessBuilder("sleep", "5").start()
        try {
            val pid = ProcFacts.pidOf(process)
            assertTrue(pid != null && File("/proc/$pid/cmdline").readText().startsWith("sleep"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun `activity is work, not time`() {
        val clock = ActivityClock(startedAt = 0, idleLimitMs = 15 * 60_000L)
        assertFalse(clock.sample(60_000, 1_000))
        assertFalse("under a second of CPU in a minute is quiet", clock.sample(120_000, 1_050))
        assertTrue(clock.sample(180_000, 1_500))
        assertEquals(180_000, clock.lastActivityAt)
        assertEquals(180_000 + 15 * 60_000L, clock.sleepsAt())
        assertFalse(clock.isIdle(180_000 + 14 * 60_000L))
        clock.touch(500_000)
        assertFalse(clock.isIdle(180_000 + 15 * 60_000L))
        assertTrue(clock.isIdle(500_000 + 15 * 60_000L))
        assertTrue(clock.busyWithin(510_000, 60_000))
        assertFalse(clock.busyWithin(600_000, 60_000))
        clock.touch(1)
        assertEquals("time never runs backwards", 500_000, clock.lastActivityAt)
    }

    @Test fun `with idle sleep off a room never sleeps, and a new idle time applies at once`() {
        var minutes = 0L
        val clock = ActivityClock(startedAt = 0, idleLimitMs = { minutes * 60_000L })
        assertNull(clock.sleepsAt())
        assertFalse(clock.isIdle(Long.MAX_VALUE / 2))
        minutes = 30
        assertEquals(30 * 60_000L, clock.sleepsAt())
        assertTrue(clock.isIdle(30 * 60_000L))
    }

    @Test fun `work is reported when it starts and ends, not on every sample`() {
        val reports = mutableListOf<String>()
        val holds = WorkHolds { agent, what, busy -> reports += "$agent $what $busy" }
        holds.hold("claude", WorkHolds.BUILD)
        holds.hold("claude", WorkHolds.BUILD)
        holds.release("claude", WorkHolds.BUILD)
        assertEquals(listOf("claude build true"), reports)
        holds.release("claude", WorkHolds.BUILD)
        holds.release("claude", WorkHolds.BUILD)
        assertEquals(listOf("claude build true", "claude build false"), reports)

        reports.clear()
        holds.set("codex", WorkHolds.TURN, true)
        holds.set("codex", WorkHolds.TURN, true)
        holds.set("codex", WorkHolds.TURN, false)
        holds.set("codex", WorkHolds.TURN, false)
        assertEquals(listOf("codex turn true", "codex turn false"), reports)

        reports.clear()
        holds.set("codex", WorkHolds.COMMAND, true)
        holds.hold("codex", WorkHolds.WRITE)
        holds.hold("claude", WorkHolds.WRITE)
        assertEquals(setOf("codex", "claude"), holds.holding(WorkHolds.WRITE))
        holds.clear("codex")
        assertEquals(setOf("claude"), holds.holding(WorkHolds.WRITE))
        assertTrue(reports.containsAll(listOf("codex command false", "codex write false")))
        assertFalse(reports.contains("claude write false"))
    }

    @Test fun `the output ring keeps the last lines without secrets`() {
        val ring = OutputRing(capacity = 3)
        ring.add("one")
        ring.add("   ")
        ring.add("token ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        ring.add("three")
        ring.add("four")
        assertEquals(listOf("token [hidden]", "three", "four"), ring.last(3))
        assertTrue(ring.last(10).none { it.contains("ghp_") })
        assertEquals(listOf("four"), ring.last(1))
    }

    @Test fun `loopback GET reads the status and body`() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            val seen = StringBuilder()
            val serving = thread {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        seen.append(line).append('\n')
                    }
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":\"alive\"}".toByteArray())
                }
            }
            val answer = Loopback.get(server.localPort, "/healthz")
            serving.join()
            assertEquals(200, answer?.status)
            assertTrue(RoomEngines.ready(Engine.CODE_SERVER, answer))
            assertTrue(seen.startsWith("GET /healthz HTTP/1.1\nHost: localhost:${server.localPort}\n"))
        }
        assertNull(Loopback.get(Loopback.freePort(), "/"))
        assertFalse(RoomEngines.ready(Engine.CODE_SERVER, HttpAnswer(302, "")))
        assertTrue(RoomEngines.ready(Engine.AGY_HUB, HttpAnswer(401, "")))
        assertFalse(RoomEngines.ready(Engine.AGY_HUB, HttpAnswer(502, "")))
        assertNull(Loopback.parse("SSH-2.0-OpenSSH\r\n"))
    }
}
