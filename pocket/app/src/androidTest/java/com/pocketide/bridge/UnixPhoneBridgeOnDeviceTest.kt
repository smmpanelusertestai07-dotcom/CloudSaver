package com.pocketide.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketide.core.AppDirs
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The phone bridge's socket rules on a real kernel: the socket is made private and renamed over
 * whatever the room planted, only the socket it made is ever trusted or deleted, and a room gets
 * a busy answer past its connection cap.
 */
@RunWith(AndroidJUnit4::class)
class UnixPhoneBridgeOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val job = Job()
    private val sockets = mutableListOf<LocalSocket>()
    private lateinit var root: File
    private lateinit var dirs: AppDirs
    private lateinit var bridge: UnixPhoneBridge

    private val ops = PhoneOps().apply {
        register("echo") { agent, _ -> buildJsonObject { put("agent", agent) } }
    }

    private val socketFile get() = File(dirs.roomBridge(AGENT), PhoneBridge.SOCKET_NAME)

    @Before
    fun setUp() {
        // Short: a Unix socket's path must fit in 107 bytes.
        root = File(context.cacheDir, "ub").apply { deleteRecursively() }
        dirs = AppDirs(File(root, "f"), File(root, "c"))
        bridge = UnixPhoneBridge(dirs, ops, job, PhoneLimits(maxConnectionsPerRoom = 2))
    }

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        bridge.stop(AGENT)
        job.cancel()
        root.deleteRecursively()
    }

    private fun connect(): LocalSocket = LocalSocket().also {
        it.connect(LocalSocketAddress(socketFile.path, LocalSocketAddress.Namespace.FILESYSTEM))
        sockets += it
    }

    private fun LocalSocket.ask(line: String): JsonObject {
        outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
        outputStream.flush()
        return readLine()
    }

    private fun LocalSocket.readLine(): JsonObject {
        val text = inputStream.bufferedReader(Charsets.UTF_8).readLine() ?: error("The bridge closed the connection.")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun mode(file: File) = Os.lstat(file.path).st_mode

    @Test
    fun aRoomReachesItsOwnSocketWhichOnlyThisAppCanUse() {
        bridge.start(AGENT)

        val reply = connect().ask("""{"id":1,"op":"echo"}""")

        assertTrue(reply.getValue("ok").jsonPrimitive.boolean)
        assertEquals(AGENT, reply.getValue("result").jsonObject.getValue("agent").jsonPrimitive.content)
        assertTrue(OsConstants.S_ISSOCK(mode(socketFile)))
        assertEquals(0x180, mode(socketFile) and 0x1FF) // 0600
    }

    @Test
    fun whatTheRoomPlantedIsReplacedNotFollowed() {
        val target = File(root, "outside.txt").apply { parentFile?.mkdirs(); writeText("keep me") }
        socketFile.parentFile?.mkdirs()
        Os.symlink(target.path, socketFile.path)

        bridge.start(AGENT)

        assertTrue(OsConstants.S_ISSOCK(mode(socketFile)))
        assertEquals("keep me", target.readText())
    }

    @Test
    fun onlyTheSocketTheBridgeMadeIsDeleted() {
        bridge.start(AGENT)
        socketFile.delete()
        socketFile.writeText("the room's own file")

        bridge.stop(AGENT)

        assertEquals("the room's own file", socketFile.readText())
    }

    @Test
    fun aRoomPastItsConnectionCapIsToldTheBridgeIsBusy() {
        bridge.start(AGENT)
        repeat(2) { connect() }

        val busy = connect().readLine()

        assertFalse(busy.getValue("ok").jsonPrimitive.boolean)
        assertTrue(busy.getValue("error").jsonPrimitive.content.contains("busy"))
    }

    private companion object {
        const val AGENT = "claude"
    }
}
