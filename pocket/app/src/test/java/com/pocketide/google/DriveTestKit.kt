package com.pocketide.google

import com.pocketide.core.Clock
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import kotlin.random.Random

/** Hands out t1, t2, … and records which tokens Drive rejected. */
internal class FakeTokens(private val account: String? = "owner@example.com") : TokenSource {
    private var next = 1
    var current = "t1"
        private set
    val rejected = mutableListOf<String>()

    override fun account(): String? = account

    override suspend fun token(): String = current

    override suspend fun rejected(token: String) {
        rejected += token
        next++
        current = "t$next"
    }
}

internal class TestClock(var now: Long = 1_000_000L) : Clock {
    override fun now(): Long = now
}

internal class DriveFixture(maxRetries: Int = 3) {
    val server = MockWebServer().apply { start() }
    val tokens = FakeTokens()
    val clock = TestClock()
    val pauses = mutableListOf<Long>()
    val http = DriveHttp(
        client = OkHttpClient(),
        endpoints = DriveEndpoints(api = server.url("/drive/v3/"), upload = server.url("/upload/drive/v3/")),
        backoff = Backoff(maxRetries = maxRetries, random = Random(7), pause = { pauses += it }),
        sessions = UploadSessions(clock),
    )
    val store = DriveRestStore(http, tokens, clock) { error("Not used in these tests") }

    fun enqueue(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(
            MockResponse.Builder().code(code).body(body)
                .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                .build(),
        )
    }

    fun fail(code: Int, reason: String?, vararg headers: Pair<String, String>) {
        val errors = if (reason == null) "[]" else """[{"domain":"usageLimits","reason":"$reason","message":"m"}]"""
        enqueue(code, """{"error":{"errors":$errors,"code":$code,"message":"m"}}""", *headers)
    }

    fun close() = server.close()
}

internal fun fileJson(id: String, name: String, size: Long, md5: String? = null, quotaBytesUsed: Long? = null): String {
    val parts = mutableListOf(
        "\"id\":\"$id\"",
        "\"name\":\"$name\"",
        "\"size\":\"$size\"",
        "\"modifiedTime\":\"2026-09-24T10:00:00.000Z\"",
    )
    if (md5 != null) parts += "\"md5Checksum\":\"$md5\""
    if (quotaBytesUsed != null) parts += "\"quotaBytesUsed\":\"$quotaBytesUsed\""
    return parts.joinToString(",", "{", "}")
}

internal fun md5(bytes: ByteArray): String = bytes.toByteString().md5().hex()

internal fun randomBytes(size: Int, seed: Int = 3): ByteArray = Random(seed).nextBytes(size)
