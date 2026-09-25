package com.pocketide.core

import com.pocketide.bridge.PhoneBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppDirsTest {
    private val dirs = AppDirs(File("/data/user/0/com.pocketide.debug/files"), File("/data/user/0/com.pocketide.debug/cache"))

    @Test
    fun shortAgentIdsKeepTheirOwnBridgeFolder() {
        assertEquals(File(dirs.bridge, "claude"), dirs.roomBridge("claude"))
        assertEquals(File(dirs.bridge, "anthropic.claude-code"), dirs.roomBridge("anthropic.claude-code"))
    }

    @Test
    fun longAgentIdsGetAShortStableFolder() {
        val id = "some-publisher-with-a-long-name.an-agent-extension-with-an-even-longer-name"
        val name = AppDirs.bridgeDirName(id)
        assertEquals(17, name.length)
        assertTrue(name.startsWith("~"))
        assertEquals(name, AppDirs.bridgeDirName(id))
        assertNotEquals(name, AppDirs.bridgeDirName("$id-2"))
    }

    @Test
    fun everyPhoneSocketFitsAUnixSocketAddress() {
        val ids = listOf("a".repeat(32), "b".repeat(33), "x".repeat(200))
        ids.forEach { id ->
            val socket = File(dirs.roomBridge(id), PhoneBridge.SOCKET_NAME).path
            assertTrue("$id: ${socket.length} bytes", socket.toByteArray().size <= 107)
        }
    }
}
