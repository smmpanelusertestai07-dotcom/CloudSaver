package com.pocketide.limiter

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProcessStatsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun process(proc: File, pid: Int, uid: Int, rssKb: Long, pssKb: Long? = null) {
        val dir = File(proc, "$pid").apply { mkdirs() }
        File(dir, "status").writeText("Name:\tnode\nUid:\t$uid\t$uid\t$uid\t$uid\nVmRSS:\t  $rssKb kB\n")
        if (pssKb != null) File(dir, "smaps_rollup").writeText("00400000-7fff [rollup]\nRss:  ${rssKb} kB\nPss:  $pssKb kB\nPss_Anon:  1 kB\n")
    }

    @Test
    fun `counts only this app's own processes, apart from the app itself`() {
        val proc = temp.newFolder("proc")
        process(proc, 100, uid = 10123, rssKb = 200_000, pssKb = 150_000) // the app
        process(proc, 200, uid = 10123, rssKb = 50_000, pssKb = 40_000) // proot
        process(proc, 201, uid = 10123, rssKb = 300_000) // node, no smaps_rollup
        process(proc, 300, uid = 10999, rssKb = 999_999, pssKb = 999_999) // someone else
        File(proc, "self").mkdirs()
        File(proc, "meminfo").writeText("MemTotal: 1 kB\n")

        val own = ProcessReader(proc, uid = 10123, selfPid = 100).read()

        assertEquals(2, own.children)
        assertEquals((150_000L + 40_000 + 300_000) * 1024, own.memoryBytes)
    }

    @Test
    fun `a process that vanished mid-read is skipped`() {
        val proc = temp.newFolder("proc")
        File(proc, "400").mkdirs() // listed, but its files are already gone
        process(proc, 401, uid = 10123, rssKb = 1_000)
        assertEquals(1, ProcessReader(proc, uid = 10123, selfPid = 1).read().children)
    }

    @Test
    fun `sizes count each file once and never follow links`() {
        val root = temp.newFolder("files")
        File(root, "a.bin").writeBytes(ByteArray(1000))
        File(root, "deep/er").mkdirs()
        File(root, "deep/er/b.bin").writeBytes(ByteArray(234))
        // A loop back to the top, as the Linux computer has, and a link to a big file elsewhere.
        Files.createSymbolicLink(File(root, "deep/loop").toPath(), root.toPath())
        val outside = temp.newFile("outside.bin").apply { writeBytes(ByteArray(10_000)) }
        Files.createSymbolicLink(File(root, "link.bin").toPath(), outside.toPath())

        assertEquals(1234L, DirectorySize.of(listOf(root, File(root, "missing"))))
    }
}
