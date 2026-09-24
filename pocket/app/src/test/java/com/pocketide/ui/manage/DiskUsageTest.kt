package com.pocketide.ui.manage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DiskUsageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(root: File, relative: String, size: Int) = File(root, relative).apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(size))
    }

    @Test
    fun `sums regular files in every folder`() {
        val root = temp.newFolder("work")
        file(root, "a", 10)
        file(root, "x/y/b", 20)
        assertEquals(30, DiskUsage.sizeOf(root))
        assertEquals(10, DiskUsage.sizeOf(File(root, "a")))
    }

    @Test
    fun `missing folders are empty`() {
        assertEquals(0, DiskUsage.sizeOf(File(temp.root, "none")))
    }

    @Test
    fun `links are not followed`() {
        val root = temp.newFolder("room")
        val big = temp.newFolder("big")
        file(big, "huge", 1000)
        file(root, "own", 5)
        Files.createSymbolicLink(File(root, "link").toPath(), big.toPath())
        Files.createSymbolicLink(File(root, "filelink").toPath(), File(big, "huge").toPath())
        assertEquals(5, DiskUsage.sizeOf(root))
        assertEquals(0, DiskUsage.sizeOf(File(root, "link")))
    }

    @Test
    fun `a named folder can be skipped anywhere below the root`() {
        val root = temp.newFolder("work")
        file(root, "claude/app/s1/main.kt", 7)
        file(root, "claude/app/.media/s1/shot.webp", 100)
        assertEquals(7, DiskUsage.sizeOf(root, skipDirectory = ".media"))
        assertEquals(107, DiskUsage.sizeOf(root))
    }
}
