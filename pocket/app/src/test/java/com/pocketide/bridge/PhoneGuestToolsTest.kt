package com.pocketide.bridge

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.io.IOException
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The room's xdg-open, run with python3 against a real Unix socket served by [PhoneSession]. */
class PhoneGuestToolsTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val dir: File = Files.createTempDirectory("pide").toFile()
    private val socketPath = File(dir, "phone.sock")
    private val script = File(dir, "xdg-open")
    private val opened = CopyOnWriteArrayList<String>()
    private val ops = PhoneOps().apply {
        val openUrl = OpenUrlOp(browser = { opened += it }, minIntervalMs = 0)
        register(PhoneBridge.OPEN_URL, openUrl::invoke)
    }
    private var server: ServerSocketChannel? = null

    private class Run(val exit: Int, val stdout: String, val stderr: String)

    @Before fun setUp() {
        assumeTrue("python3 is needed to run the room's tools.", hasPython())
        script.writeText(PhoneGuestTools.xdgOpen)
        script.setExecutable(true)
    }

    @After fun tearDown() {
        server?.close()
        dir.deleteRecursively()
    }

    private fun hasPython(): Boolean = try {
        ProcessBuilder("python3", "--version").start().waitFor(10, TimeUnit.SECONDS)
    } catch (e: IOException) {
        false
    }

    /** Serves the phone bridge protocol on [socketPath] for as many connections as arrive. */
    private fun serve() {
        // Unix domain channels are in the JDK the tests run on, but not in the Android stubs
        // they compile against.
        val open = ServerSocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
        val channel = open.invoke(null, StandardProtocolFamily.valueOf("UNIX")) as ServerSocketChannel
        val address = Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String::class.java)
        channel.bind(address.invoke(null, socketPath.path) as SocketAddress)
        server = channel
        thread(isDaemon = true) {
            while (true) {
                val client = try {
                    channel.accept()
                } catch (e: IOException) {
                    return@thread
                }
                thread(isDaemon = true) {
                    client.use {
                        runBlocking { PhoneSession("claude", Channels.newInputStream(it), Channels.newOutputStream(it), ops).serve() }
                    }
                }
            }
        }
    }

    private fun xdgOpen(vararg args: String): Run {
        val process = ProcessBuilder(listOf("python3", script.path) + args)
            .apply { environment()["POCKETIDE_PHONE_SOCKET"] = socketPath.path }
            .start()
        process.outputStream.close()
        // Both outputs are a few lines, so reading one after the other cannot fill a pipe.
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue("xdg-open did not finish.", process.waitFor(30, TimeUnit.SECONDS))
        return Run(process.exitValue(), stdout, stderr)
    }

    @Test fun `a sign-in link is handed to the phone and opens`() {
        serve()
        val link = "https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=http%3A%2F%2Flocalhost%3A40075%2Fauth%2Fcallback&state=${"s".repeat(700)}"

        val run = xdgOpen(link)

        assertEquals(run.stderr, 0, run.exit)
        assertEquals(listOf(link), opened)
    }

    @Test fun `what the phone refuses is reported with the link to copy`() {
        serve()

        val run = xdgOpen("https://bank.example@evil.example/")

        assertEquals(4, run.exit)
        assertTrue(run.stderr, run.stderr.contains("user name or password"))
        assertTrue(run.stderr, run.stderr.contains("The address was: https://bank.example@evil.example/"))
        assertTrue(opened.isEmpty())
    }

    @Test fun `files and other schemes are never sent to the phone`() {
        serve()
        for (target in listOf("/root/.codex/auth.json", "file:///etc/passwd", "intent://x#Intent;end", "javascript:alert(1)")) {
            assertEquals(target, 3, xdgOpen(target).exit)
        }
        assertTrue(opened.isEmpty())
    }

    @Test fun `with no phone listening the link is printed instead`() {
        val run = xdgOpen("https://example.com/sign-in")

        assertEquals(4, run.exit)
        assertTrue(run.stderr, run.stderr.contains("PocketIDE did not answer."))
        assertTrue(run.stderr, run.stderr.contains("The address was: https://example.com/sign-in"))
    }

    @Test fun `usage, help and version follow xdg-open`() {
        assertEquals(1, xdgOpen().exit)
        assertEquals(1, xdgOpen("https://a.example/", "https://b.example/").exit)
        assertEquals(0, xdgOpen("--help").exit)
        assertEquals(0, xdgOpen("--version").exit)
    }
}
