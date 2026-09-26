package com.pocketide.bridge

import com.pocketide.core.AppDirs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The socket rules that need no Android: an agent id must be one safe path segment, or a room's
 * socket could land outside its own bridge folder. The socket itself is tested on a device
 * (UnixPhoneBridgeOnDeviceTest).
 */
class UnixPhoneBridgeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `start refuses an agent id that is not one path segment`() {
        val dirs = AppDirs(temp.newFolder("files"), temp.newFolder("cache"))
        val bridge = UnixPhoneBridge(dirs, PhoneOps(), null)

        for (id in listOf("../x", "a/b", "", ".hidden", "x".repeat(129), "a\u0000b", "/abs")) {
            assertThrows(id, IllegalArgumentException::class.java) { bridge.start(id) }
        }
        assertFalse("nothing was made for a refused id", dirs.bridge.exists())
        assertFalse(File(dirs.base, "bridge-staging").exists())
    }
}
