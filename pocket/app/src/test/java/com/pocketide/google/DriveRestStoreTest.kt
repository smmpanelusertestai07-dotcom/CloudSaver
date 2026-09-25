package com.pocketide.google

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.RecordedRequest
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class DriveRestStoreTest {
    private val drive = DriveFixture()
    private val store get() = drive.store
    private val server get() = drive.server

    @After
    fun tearDown() = drive.close()

    @Test
    fun `list reads every page of the hidden folder`() = runBlocking<Unit> {
        drive.enqueue(200, """{"nextPageToken":"p2","files":[${fileJson("1", "a", 10)}]}""")
        drive.enqueue(200, """{"files":[${fileJson("2", "b", 20)},${fileJson("3", "c", 30)}]}""")

        val files = store.list()

        assertEquals(listOf("a", "b", "c"), files.map { it.name })
        assertEquals(listOf(10L, 20L, 30L), files.map { it.size })
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/drive/v3/files", first.url.encodedPath)
        assertEquals("appDataFolder", first.url.queryParameter("spaces"))
        assertEquals("1000", first.url.queryParameter("pageSize"))
        assertTrue(first.url.queryParameter("fields")!!.startsWith("nextPageToken,files(id,name,size,modifiedTime,md5Checksum"))
        assertNull(first.url.queryParameter("pageToken"))
        assertEquals("Bearer t1", first.headers["Authorization"])
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun `find escapes quotes and backslashes in the name`() = runBlocking<Unit> {
        drive.enqueue(200, """{"files":[${fileJson("9", "it's a\\\\b", 5, md5 = "abc")}]}""")

        val found = store.find("it's a\\b")

        assertEquals("9", found?.id)
        assertEquals("abc", found?.md5)
        val request = server.takeRequest()
        assertEquals("name = 'it\\'s a\\\\b' and 'appDataFolder' in parents", request.url.queryParameter("q"))
        assertEquals("appDataFolder", request.url.queryParameter("spaces"))
    }

    @Test
    fun `find returns null when nothing has the name`() = runBlocking<Unit> {
        drive.enqueue(200, """{"files":[]}""")
        assertNull(store.find("missing"))
    }

    @Test
    fun `small uploads are one multipart request into the hidden folder`() = runBlocking<Unit> {
        val bytes = "sealed piece".toByteArray()
        drive.enqueue(200, fileJson("new1", "piece-1", bytes.size.toLong(), md5 = md5(bytes)))

        val file = store.uploadBytes("piece-1", bytes)

        assertEquals("new1", file.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files", request.url.encodedPath)
        assertEquals("multipart", request.url.queryParameter("uploadType"))
        val contentType = request.headers["Content-Type"]!!
        assertTrue(contentType, contentType.startsWith("multipart/related; boundary="))
        val body = request.body!!.utf8()
        assertTrue(body, body.contains("Content-Type: application/json; charset=UTF-8"))
        assertTrue(body, body.contains("""{"name":"piece-1","parents":["appDataFolder"]}"""))
        assertTrue(body, body.contains("Content-Type: application/octet-stream"))
        assertTrue(body, body.contains("sealed piece"))
        assertTrue("metadata comes first", body.indexOf("piece-1") < body.indexOf("sealed piece"))
    }

    @Test
    fun `replacing a file sends only its new content`() = runBlocking<Unit> {
        val bytes = "v2".toByteArray()
        drive.enqueue(200, fileJson("idx", "index", 2, md5 = md5(bytes)))

        store.uploadBytes("index", bytes, existingId = "idx")

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/upload/drive/v3/files/idx", request.url.encodedPath)
        assertEquals("media", request.url.queryParameter("uploadType"))
        assertEquals("v2", request.body!!.utf8())
    }

    @Test
    fun `a replaced file that was deleted meanwhile is created again`() = runBlocking<Unit> {
        val bytes = "v2".toByteArray()
        drive.fail(404, "notFound")
        drive.enqueue(200, fileJson("fresh", "index", 2, md5 = md5(bytes)))

        val file = store.uploadBytes("index", bytes, existingId = "gone")

        assertEquals("fresh", file.id)
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `a damaged new upload is removed and reported`() = runBlocking<Unit> {
        drive.enqueue(200, fileJson("bad", "piece", 3, md5 = "00000000000000000000000000000000"))
        drive.enqueue(204)

        try {
            store.uploadBytes("piece", "abc".toByteArray())
            fail("expected a damaged upload")
        } catch (e: DriveException.Other) {
            assertTrue(e.message!!.contains("damaged"))
        }
        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/drive/v3/files/bad", delete.url.encodedPath)
    }

    @Test
    fun `large files upload in resumable chunks and resume after a server error`() = runBlocking<Unit> {
        val bytes = randomBytes(LARGE)
        val file = tempFile(bytes)
        val session = server.url("/upload/drive/v3/files?uploadType=resumable&upload_id=s1").toString()
        drive.enqueue(200, "", "Location" to session)
        drive.fail(503, "backendError")
        drive.enqueue(308, "", "Range" to "bytes=0-${HALF_CHUNK - 1}")
        drive.enqueue(200, fileJson("big", "blob", LARGE.toLong(), md5 = md5(bytes)))

        val uploaded = store.upload("blob", file)

        assertEquals("big", uploaded.id)
        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("resumable", start.url.queryParameter("uploadType"))
        assertEquals(LARGE.toString(), start.headers["X-Upload-Content-Length"])
        assertEquals("""{"name":"blob","parents":["appDataFolder"]}""", start.body!!.utf8())
        val first = server.takeRequest()
        assertEquals("PUT", first.method)
        assertEquals("s1", first.url.queryParameter("upload_id"))
        assertEquals("bytes 0-${CHUNK - 1}/$LARGE", first.headers["Content-Range"])
        assertEquals(CHUNK.toLong(), first.bodySize)
        val status = server.takeRequest()
        assertEquals("bytes */$LARGE", status.headers["Content-Range"])
        assertEquals(0L, status.bodySize)
        val rest = server.takeRequest()
        assertEquals("bytes $HALF_CHUNK-${LARGE - 1}/$LARGE", rest.headers["Content-Range"])
        assertArrayEquals(bytes.copyOfRange(HALF_CHUNK, LARGE), rest.body!!.toByteArray())
        assertEquals(1, drive.pauses.size)
    }

    @Test
    fun `a cut-off resumable upload continues on the next call instead of starting over`() = runBlocking<Unit> {
        val limited = DriveFixture(maxRetries = 0)
        try {
            val bytes = randomBytes(LARGE)
            val file = tempFile(bytes)
            val session = limited.server.url("/upload/drive/v3/files?uploadType=resumable&upload_id=s2").toString()
            limited.enqueue(200, "", "Location" to session)
            limited.fail(503, null)
            try {
                limited.store.upload("blob", file)
                fail("expected the upload to stop")
            } catch (_: DriveException.RateLimited) {
            }

            limited.enqueue(308, "", "Range" to "bytes=0-${CHUNK - 1}")
            limited.enqueue(200, fileJson("big", "blob", LARGE.toLong(), md5 = md5(bytes)))
            assertEquals("big", limited.store.upload("blob", file).id)

            repeat(2) { limited.server.takeRequest() }
            val status = limited.server.takeRequest()
            assertEquals("s2", status.url.queryParameter("upload_id"))
            assertEquals("bytes */$LARGE", status.headers["Content-Range"])
            val rest = limited.server.takeRequest()
            assertEquals("bytes $CHUNK-${LARGE - 1}/$LARGE", rest.headers["Content-Range"])
        } finally {
            limited.close()
        }
    }

    @Test
    fun `a session Drive no longer knows is replaced by a new one`() = runBlocking<Unit> {
        val bytes = randomBytes(LARGE)
        val file = tempFile(bytes)
        drive.enqueue(200, "", "Location" to server.url("/upload/drive/v3/files?upload_id=old").toString())
        drive.fail(404, "notFound")
        drive.enqueue(200, "", "Location" to server.url("/upload/drive/v3/files?upload_id=new").toString())
        drive.enqueue(308, "", "Range" to "bytes=0-${CHUNK - 1}")
        drive.enqueue(200, fileJson("big", "blob", LARGE.toLong(), md5 = md5(bytes)))

        assertEquals("big", store.upload("blob", file).id)

        val methods = List(5) { server.takeRequest() }.map { it.method + " " + (it.url.queryParameter("upload_id") ?: "-") }
        assertEquals(listOf("POST -", "PUT old", "POST -", "PUT new", "PUT new"), methods)
    }

    @Test
    fun `an upload session on another host is refused`() = runBlocking<Unit> {
        drive.enqueue(200, "", "Location" to "https://elsewhere.example/upload?upload_id=x")

        try {
            store.upload("blob", tempFile(randomBytes(LARGE)))
            fail("expected a refused session")
        } catch (e: DriveException.Other) {
            assertTrue(e.message!!.contains("did not start"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `replacing a large file opens a resumable PATCH session`() = runBlocking<Unit> {
        val bytes = randomBytes(LARGE)
        drive.enqueue(200, "", "Location" to server.url("/upload/drive/v3/files/idx?upload_id=p").toString())
        drive.enqueue(308, "", "Range" to "bytes=0-${CHUNK - 1}")
        drive.enqueue(200, fileJson("idx", "blob", LARGE.toLong(), md5 = md5(bytes)))

        store.upload("blob", tempFile(bytes), existingId = "idx")

        val start = server.takeRequest()
        assertEquals("PATCH", start.method)
        assertEquals("/upload/drive/v3/files/idx", start.url.encodedPath)
        assertEquals("{}", start.body!!.utf8())
    }

    @Test
    fun `download streams the content into the sink`() = runBlocking<Unit> {
        val bytes = randomBytes(300_000)
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())
        val sink = ByteArrayOutputStream()

        store.download("f1", sink)

        assertArrayEquals(bytes, sink.toByteArray())
        val request = server.takeRequest()
        assertEquals("/drive/v3/files/f1", request.url.encodedPath)
        assertEquals("media", request.url.queryParameter("alt"))
    }

    @Test
    fun `a download that breaks off continues from where it stopped`() = runBlocking<Unit> {
        val bytes = randomBytes(200_000)
        val ranges = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                ranges += range
                if (range == null) return MockResponse.Builder().code(200).body(CutOffBody(bytes, bytes.size / 2)).build()
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse.Builder().code(206).body(Buffer().write(bytes, from, bytes.size - from)).build()
            }
        }
        val sink = ByteArrayOutputStream()

        store.download("f1", sink)

        assertArrayEquals(bytes, sink.toByteArray())
        assertEquals(2, ranges.size)
        assertNull(ranges[0])
        assertTrue(ranges[1]!!.startsWith("bytes="))
    }

    @Test
    fun `a server that ignores Range still gives a whole download`() = runBlocking<Unit> {
        val bytes = randomBytes(120_000)
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                val body = if (calls == 1) CutOffBody(bytes, 50_000) else CutOffBody(bytes, bytes.size)
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        val sink = ByteArrayOutputStream()

        store.download("f1", sink)

        assertArrayEquals(bytes, sink.toByteArray())
    }

    @Test
    fun `open gives a stream of the content`() = runBlocking<Unit> {
        drive.enqueue(200, "hello")
        val text = store.open("f1").use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("hello", text)
    }

    @Test
    fun `delete is permanent and a file already gone is fine`() = runBlocking<Unit> {
        drive.enqueue(204)
        drive.fail(404, "notFound")

        store.delete("a")
        store.delete("b")

        val first = server.takeRequest()
        assertEquals("DELETE", first.method)
        assertEquals("/drive/v3/files/a", first.url.encodedPath)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `quota reads Google's strings and sums the hidden folder briefly cached`() = runBlocking<Unit> {
        drive.enqueue(200, """{"storageQuota":{"limit":"16106127360","usage":"5000","usageInDrive":"4000","usageInDriveTrash":"0"},"user":{"emailAddress":"owner@example.com"}}""")
        drive.enqueue(200, """{"files":[${fileJson("1", "a", 10, quotaBytesUsed = 12)},${fileJson("2", "b", 20)}]}""")
        drive.enqueue(200, """{"storageQuota":{"usage":"5000","usageInDrive":"4000"},"user":{"emailAddress":"owner@example.com"}}""")

        val first = store.quota()
        val second = store.quota()

        assertEquals(16_106_127_360L, first.limitBytes)
        assertEquals(5000L, first.usageBytes)
        assertEquals(4000L, first.usageInDriveBytes)
        assertEquals(32L, first.appDataBytes)
        assertEquals("owner@example.com", first.email)
        assertNull("no limit means unlimited", second.limitBytes)
        assertEquals(32L, second.appDataBytes)
        assertEquals(3, server.requestCount)
        assertEquals("storageQuota,user", server.takeRequest().url.queryParameter("fields"))
    }

    @Test
    fun `storage full is reported at once, without retrying`() = runBlocking<Unit> {
        drive.fail(403, "storageQuotaExceeded")
        expect<DriveException.StorageFull> { store.uploadBytes("p", "x".toByteArray()) }
        assertEquals(1, server.requestCount)
        assertTrue(drive.pauses.isEmpty())
    }

    @Test
    fun `rate limits back off exponentially, then give up`() = runBlocking<Unit> {
        repeat(4) { drive.fail(403, "userRateLimitExceeded") }
        val error = expect<DriveException.RateLimited> { store.list() }
        assertEquals(4, server.requestCount)
        assertEquals(3, drive.pauses.size)
        drive.pauses.forEachIndexed { i, pause ->
            val ceiling = 1000L shl i
            assertTrue("pause $i was $pause", pause in ceiling / 2..ceiling)
        }
        assertTrue(error.retryAfterMs > 0)
    }

    @Test
    fun `project rate limits, 429 and server errors are retried`() = runBlocking<Unit> {
        drive.fail(403, "rateLimitExceeded")
        drive.fail(429, "rateLimitExceeded")
        drive.fail(500, "backendError")
        drive.enqueue(200, """{"files":[]}""")
        assertTrue(store.list().isEmpty())
        assertEquals(3, drive.pauses.size)
    }

    @Test
    fun `Retry-After is honoured`() = runBlocking<Unit> {
        drive.fail(429, null, "Retry-After" to "7")
        drive.enqueue(200, """{"files":[]}""")
        store.list()
        assertTrue(drive.pauses.single() >= 7_000)
    }

    @Test
    fun `a daily limit is not retried within the call`() = runBlocking<Unit> {
        drive.fail(403, "dailyLimitExceeded")
        expect<DriveException.RateLimited> { store.list() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a rejected token is refreshed once`() = runBlocking<Unit> {
        drive.fail(401, "authError")
        drive.enqueue(200, """{"files":[]}""")

        store.list()

        assertEquals(listOf("t1"), drive.tokens.rejected)
        assertEquals("Bearer t1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer t2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a second 401 means access was removed`() = runBlocking<Unit> {
        drive.fail(401, "authError")
        drive.fail(401, "authError")
        expect<DriveException.Revoked> { store.list() }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a token without the Drive scope counts as removed access`() = runBlocking<Unit> {
        drive.fail(403, "insufficientPermissions")
        expect<DriveException.Revoked> { store.list() }
    }

    @Test
    fun `an admin block is explained`() = runBlocking<Unit> {
        drive.fail(403, "domainPolicy")
        val error = expect<DriveException.Other> { store.list() }
        assertTrue(error.message!!.contains("administrator"))
    }

    @Test
    fun `a missing file is NotFound, which older callers see as Other`() = runBlocking<Unit> {
        drive.fail(404, "notFound")
        val error = expect<DriveException.Other> { store.download("gone", ByteArrayOutputStream()) }
        assertTrue(error is DriveException.NotFound)
    }

    @Test
    fun `an unreadable answer is a plain error`() = runBlocking<Unit> {
        drive.enqueue(200, "<html>")
        val error = expect<DriveException.Other> { store.list() }
        assertEquals(DriveCalls.UNREADABLE, error.message)
    }

    @Test
    fun `no connection is Offline`() = runBlocking<Unit> {
        server.close()
        expect<DriveException.Offline> { store.list() }
        Unit
    }

    @Test
    fun `errors never carry the token`() = runBlocking<Unit> {
        drive.fail(400, "badRequest")
        val error = expect<DriveException.Other> { store.list() }
        assertTrue(!error.message!!.contains("t1"))
    }

    private fun tempFile(bytes: ByteArray) = kotlin.io.path.createTempFile("drive", ".bin").toFile().apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    private inline fun <reified E : Throwable> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${E::class.simpleName}, nothing was thrown")
    }

    /** Declares the whole length, sends [upTo] bytes and breaks off (a dropped connection). */
    private class CutOffBody(private val bytes: ByteArray, private val upTo: Int) : MockResponseBody {
        override val contentLength: Long = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes, 0, upTo)
            sink.flush()
            if (upTo < bytes.size) throw IOException("connection dropped")
        }
    }

    private companion object {
        const val CHUNK = 8 * 1024 * 1024
        const val HALF_CHUNK = CHUNK / 2
        const val LARGE = CHUNK + 1024 * 1024 + 100
    }
}
