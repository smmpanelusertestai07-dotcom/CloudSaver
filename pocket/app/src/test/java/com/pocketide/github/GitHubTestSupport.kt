package com.pocketide.github

import com.pocketide.core.Clock
import com.pocketide.core.SecretBox
import com.pocketide.core.SecureStore
import kotlinx.coroutines.Dispatchers
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Seals nothing: the Keystore is not available in unit tests, and the store's logic is what is tested. */
internal class PlainBox : SecretBox {
    override fun seal(plain: ByteArray) = plain.copyOf()
    override fun open(sealed: ByteArray) = sealed.copyOf()
}

internal class TestClock(var now: Long = 1_800_000_000_000L) : Clock {
    override fun now() = now
}

internal fun tempDir(): File = Files.createTempDirectory("github-test").toFile().apply { deleteOnExit() }

internal fun secureStore(dir: File = tempDir()) = SecureStore(dir, PlainBox())

internal val testHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .build()

internal fun json(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

internal fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()

internal fun RecordedRequest.text(): String = body?.utf8().orEmpty()

internal fun RecordedRequest.form(): Map<String, String> = text().split('&').filter { it.isNotEmpty() }.associate {
    val (k, v) = it.split('=', limit = 2)
    java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
}

internal fun MockWebServer.next(): RecordedRequest = checkNotNull(takeRequest(5, TimeUnit.SECONDS)) { "no request" }

/** Hands out a fixed token and records what the client asked for. */
internal class ScriptedTokens(var current: String = "ghu_first") : UserTokens {
    val renewed = mutableListOf<String>()
    val revoked = mutableListOf<String>()
    var next: String = "ghu_second"

    override suspend fun token() = current

    override suspend fun renew(rejected: String): String {
        renewed += rejected
        current = next
        return current
    }

    override suspend fun revoke(rejected: String) {
        revoked += rejected
    }
}

internal class ApiFixture(val server: MockWebServer, clock: TestClock = TestClock()) {
    val tokens = ScriptedTokens()
    val waits = mutableListOf<Long>()
    val apiVersion = ApiVersionChoice()
    val rest = RestClient(testHttp, server.url("/"), tokens, clock, io = Dispatchers.IO, pause = { waits += it }, apiVersion = apiVersion)
    val api = GitHubRestApi(rest = rest, clock = clock)
}
