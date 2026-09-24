package com.pocketide.media

import android.net.Uri
import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class MediaSnifferTest {
    private fun kind(vararg bytes: Int, name: String = "file") =
        MediaSniffer.kindOf(name, ByteArray(bytes.size) { bytes[it].toByte() })

    private fun kind(text: String, name: String = "file") = MediaSniffer.kindOf(name, text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun imagesByTheirMagicBytes() {
        assertEquals(MediaKind.IMAGE, kind(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))
        assertEquals(MediaKind.IMAGE, kind(0xFF, 0xD8, 0xFF, 0xE0))
        assertEquals(MediaKind.IMAGE, kind("GIF89a\u0001\u0000"))
        assertEquals(MediaKind.IMAGE, kind("RIFF\u0010\u0000\u0000\u0000WEBPVP8 "))
        // A RIFF file that is not WebP (a WAV) is not an image.
        assertEquals(MediaKind.OTHER, kind("RIFF\u0010\u0000\u0000\u0000WAVEfmt \u0000"))
    }

    @Test
    fun videosByTheirMagicBytes() {
        assertEquals(MediaKind.VIDEO, kind("\u0000\u0000\u0000\u0018ftypisom\u0000\u0000\u0002\u0000"))
        assertEquals(MediaKind.VIDEO, kind("\u0000\u0000\u0000\u0018ftypmp42\u0000\u0000"))
        assertEquals(MediaKind.VIDEO, kind(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x84, 0x77, 0x65, 0x62, 0x6D))
        // HEIC and QuickTime share the box layout but are not safe formats.
        assertEquals(MediaKind.OTHER, kind("\u0000\u0000\u0000\u0018ftypheic\u0000\u0000"))
        assertEquals(MediaKind.OTHER, kind("\u0000\u0000\u0000\u0014ftypqt  \u0000\u0000"))
    }

    @Test
    fun namesDoNotDecideTheKind() {
        assertEquals(MediaKind.TEXT, kind("just some notes", name = "photo.png"))
        assertEquals(MediaKind.OTHER, kind(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00, name = "video.mp4"))
        assertEquals(MediaKind.IMAGE, kind(0xFF, 0xD8, 0xFF, 0xE1, name = "notes.txt"))
    }

    @Test
    fun documentsApksAndText() {
        assertEquals(MediaKind.PDF, kind("%PDF-1.7\n"))
        assertEquals(MediaKind.APK, kind("PK\u0003\u0004\u0014\u0000", name = "app-release.APK"))
        assertEquals(MediaKind.OTHER, kind("PK\u0003\u0004\u0014\u0000", name = "report.zip"))
        assertEquals(MediaKind.HTML, kind("\n  <!DOCTYPE html><html><body>hi</body></html>"))
        assertEquals(MediaKind.HTML, kind("<!-- report -->\n<html lang=\"en\">"))
        assertEquals(MediaKind.TEXT, MediaSniffer.kindOf("log.txt", "Grüße ✓ done".toByteArray(Charsets.UTF_8)))
        assertEquals(MediaKind.TEXT, kind(""))
        assertEquals(MediaKind.OTHER, kind(0x00, 0x01, 0x02, 0x03))
        assertEquals(MediaKind.OTHER, kind(0xC3, 0x28, 0x41, 0x42, 0x43))
    }

    @Test
    fun aCharacterCutByTheEndOfTheHeadIsStillText() {
        val bytes = "abc✓".toByteArray(Charsets.UTF_8)
        assertEquals(MediaKind.TEXT, MediaSniffer.kindOf("a.txt", bytes.copyOf(bytes.size - 1)))
    }

    @Test
    fun storedExtensionsFollowTheBytes() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("png", MediaSniffer.extensionFor(MediaKind.IMAGE, png, "shot.jpg"))
        assertEquals("txt", MediaSniffer.extensionFor(MediaKind.TEXT, "x".toByteArray(), "fake.mp4"))
        assertEquals("log", MediaSniffer.extensionFor(MediaKind.TEXT, "x".toByteArray(), "build.log"))
    }
}

class SessionMediaLibraryTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var dirs: AppDirs
    private var sessions = listOf<SessionRecord>()
    private var backup = BackupFacts(emptySet(), null)
    private var now = 10_000L
    private val shrunk = mutableListOf<File>()

    private val pngHead = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val webpHead = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)

    @Before
    fun setUp() {
        dirs = AppDirs(temp.newFolder("files"), temp.newFolder("cache"))
        sessions = listOf(session("s1"), session("s2"), session("s3"), session("s4"))
    }

    private fun session(id: String, backUp: Boolean = true) = SessionRecord(
        id = id, agentId = "claude", projectId = "alice/demo", title = id, branch = "pocket/claude/$id",
        startedAt = 0, lastActivityAt = 0, deviceId = "phone", backUp = backUp,
    )

    private fun library(shrinker: ImageShrinker = ImageShrinker { _, _ -> false }) = SessionMediaLibrary(
        dirs = dirs,
        sessions = { sessions },
        backup = { backup },
        shrinker = shrinker,
        clock = { now },
        io = Dispatchers.Unconfined,
        metaDir = File(dirs.base, "media-meta"),
        uriFor = { Uri.EMPTY },
        pollMs = 10,
    )

    private fun source(name: String, bytes: ByteArray): File = File(temp.newFolder(), name).apply { writeBytes(bytes) }

    private fun folder(sessionId: String) = dirs.sessionMedia("claude", "alice/demo", sessionId)

    @Test
    fun sameContentIsStoredOnce() = runTest {
        val media = library()
        val bytes = pngHead + ByteArray(100) { it.toByte() }
        val first = media.add("s1", source("shot.png", bytes), "shot.png", "you")
        val second = media.add("s1", source("again.png", bytes), "again.png", "you")

        assertEquals(first.file, second.file)
        assertEquals(1, folder("s1").listFiles()!!.size)
        val sha = SessionMediaLibrary.sha256(first.file)
        assertEquals("${sha.take(8)}-shot.png", first.name)
        assertEquals(MediaKind.IMAGE, first.kind)
        assertEquals("you", first.source)
    }

    @Test
    fun unsafeNamesAreCleaned() = runTest {
        val item = library().add("s1", source("x", "hello".toByteArray()), "../../etc/.pass wd?.sh", "you")
        assertTrue(item.name.matches(Regex("[0-9a-f]{8}-pass_wd\\.sh")))
        assertEquals(folder("s1").canonicalFile, item.file.parentFile!!.canonicalFile)
    }

    @Test
    fun agentScreenshotsBecomeWebpWhenSmaller() = runTest {
        val media = library { source, target ->
            shrunk += source
            target.writeBytes(webpHead)
            true
        }
        val item = media.add("s1", source("shot.png", pngHead + ByteArray(4000)), "shot.png", "agent")
        assertTrue(item.name.endsWith(".webp"))
        assertEquals(MediaKind.IMAGE, item.kind)
        assertEquals(1, shrunk.size)
        assertEquals(listOf(item.name), folder("s1").list()!!.toList())

        // An image the owner attached is kept as it is.
        val mine = media.add("s1", source("mine.png", pngHead + ByteArray(10)), "mine.png", "you")
        assertTrue(mine.name.endsWith(".png"))
        assertEquals(1, shrunk.size)
    }

    @Test
    fun originalsStayWhenWebpIsNotSmaller() = runTest {
        val item = library { _, target ->
            target.writeBytes(webpHead)
            false
        }.add("s1", source("shot.png", pngHead + ByteArray(10)), "shot.png", "agent")
        assertTrue(item.name.endsWith(".png"))
        assertEquals(1, folder("s1").list()!!.size)
    }

    @Test
    fun sizeLimitsFollowTheRealKind() = runTest {
        val big = File(temp.newFolder(), "big.png")
        RandomAccessFile(big, "rw").use {
            it.write(pngHead)
            it.setLength(MediaSniffer.IMAGE_LIMIT + 1)
        }
        try {
            library().add("s1", big, "big.png", "you")
            fail("an image over 50 MB must be refused")
        } catch (expected: MediaException) {
            assertTrue(expected.message!!.contains("50 MB"))
        }
        assertFalse(folder("s1").exists() && folder("s1").list()!!.isNotEmpty())
    }

    @Test
    fun linksAreNeverFollowed() = runTest {
        val secret = source("auth.json", "token".toByteArray())
        val link = File(temp.newFolder(), "innocent.txt").toPath()
        Files.createSymbolicLink(link, secret.toPath())
        try {
            library().add("s1", link.toFile(), "innocent.txt", "agent")
            fail("a link must be refused")
        } catch (expected: MediaException) {
            assertTrue(expected.message!!.contains("plain files"))
        }
    }

    @Test
    fun aMediaFolderReplacedByALinkIsNotUsed() = runTest {
        val elsewhere = temp.newFolder("elsewhere")
        File(elsewhere, "credentials.json").writeText("{}")
        folder("s2").parentFile!!.mkdirs()
        Files.createSymbolicLink(folder("s2").toPath(), elsewhere.toPath())
        val media = library()

        assertTrue(media.forSession("s2").first().isEmpty())
        try {
            media.add("s2", source("a.txt", "x".toByteArray()), "a.txt", "you")
            fail("a linked media folder must be refused")
        } catch (expected: MediaException) {
            assertEquals(listOf("credentials.json"), elsewhere.list()!!.toList())
        }
    }

    @Test
    fun forSessionListsNewestFirstAndSkipsPartialFiles() = runTest {
        val media = library()
        media.add("s1", source("a.txt", "first".toByteArray()), "a.txt", "you")
        now += 1000
        media.add("s1", source("b.txt", "second".toByteArray()), "b.txt", "actions")
        File(folder("s1"), "c.part").writeText("half")
        File(folder("s1"), ".hidden").writeText("h")

        val items = media.forSession("s1").first()
        assertEquals(listOf("actions", "you"), items.map { it.source })
        assertTrue(items.all { it.kind == MediaKind.TEXT && it.onPhone })
    }

    @Test
    fun backedUpFollowsTheSyncEngine() = runTest {
        val media = library()
        val item = media.add("s1", source("a.txt", "x".toByteArray()), "a.txt", "you")
        assertFalse(media.forSession("s1").first().single().backedUp)

        backup = BackupFacts(emptySet(), item.file.lastModified() + 1)
        assertTrue(media.forSession("s1").first().single().backedUp)

        backup = BackupFacts(setOf("s1"), item.file.lastModified() + 1)
        assertFalse(media.forSession("s1").first().single().backedUp)
    }

    @Test
    fun onlyTheNewestThreeApksStay() = runTest {
        val media = library()
        val zip = "PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1)
        for ((i, id) in listOf("s1", "s2", "s3", "s4").withIndex()) {
            now += 1000
            media.add(id, source("app.apk", zip + byteArrayOf(i.toByte())), "app-release.apk", "actions")
        }
        val left = listOf("s1", "s2", "s3", "s4").filter { folder(it).list().orEmpty().isNotEmpty() }
        assertEquals(listOf("s2", "s3", "s4"), left)
    }

    @Test
    fun deleteRemovesTheFile() = runTest {
        val media = library()
        val item = media.add("s1", source("a.txt", "x".toByteArray()), "a.txt", "you")
        media.delete(item)
        assertFalse(item.file.exists())
        assertTrue(media.forSession("s1").first().isEmpty())
    }

    @Test
    fun aFileTheAgentWroteIntoTheFolderIsRenamedNotCopied() = runTest {
        folder("s1").mkdirs()
        val written = File(folder("s1"), "report.html").apply { writeText("<!doctype html><p>ok") }
        val item = library().add("s1", written, "report.html", "agent")
        assertFalse(written.exists())
        assertEquals(MediaKind.HTML, item.kind)
        assertEquals(listOf(item.name), folder("s1").list()!!.toList())
    }
}
