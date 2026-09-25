package com.pocketide.github

import com.pocketide.core.SecureStore
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeviceFlowAuthTest {
    private val server = MockWebServer()
    private val clock = TestClock()
    private lateinit var store: SecureStore

    @Before
    fun setUp() {
        server.start()
        store = secureStore()
    }

    @After
    fun tearDown() = server.close()

    private fun auth(
        clientId: String = "Iv23liTESTCLIENT",
        slug: String = "pocketide-test",
        oauth: HttpUrl = server.url("/"),
    ) = authFor({ GitHubApp(clientId, slug) }, oauth)

    private fun authFor(app: () -> GitHubApp, oauth: HttpUrl = server.url("/"), api: HttpUrl = server.url("/")) =
        DeviceFlowAuth(app, TokenStore(store), testHttp, oauth, api, clock, Dispatchers.IO, pause = {})

    private fun signedIn(expiresInMs: Long? = 8 * 3_600_000L, refresh: String? = "ghr_old") {
        TokenStore(store).save(
            StoredTokens(
                access = "ghu_old",
                accessExpiresAt = expiresInMs?.let { clock.now + it },
                refresh = refresh,
                refreshExpiresAt = clock.now + 180L * 86_400_000L,
                login = "octo",
                id = 7,
                name = "Octo Cat",
                avatar = "https://avatars.githubusercontent.com/u/7",
            ),
        )
    }

    private val user = """{"login":"octo","id":7,"name":"Octo Cat","avatar_url":"https://avatars.githubusercontent.com/u/7","plan":{"name":"free"}}"""

    private fun tokenReply(access: String, refresh: String) =
        json("""{"access_token":"$access","expires_in":28800,"refresh_token":"$refresh","refresh_token_expires_in":15897600,"scope":"","token_type":"bearer"}""")

    @Test
    fun `without a client ID nothing is attempted and the owner is told why`() = runBlocking {
        val auth = auth(clientId = "")
        assertFalse(auth.configured)
        for (call in listOf<suspend () -> Unit>({ auth.startDeviceFlow() }, { auth.token() }, { auth.poll(code()) })) {
            try {
                call()
                fail("expected NotConnectedException")
            } catch (e: NotConnectedException) {
                assertTrue(e.message!!.contains("client ID"))
            }
        }
        assertEquals(LinkHealth.NOT_CONNECTED, auth.health())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an App entered while the app runs is used at once`() = runBlocking {
        var app = GitHubApp("", "")
        val auth = authFor({ app })
        assertFalse(auth.configured)
        assertEquals("https://github.com/settings/installations", auth.installUrl())

        app = GitHubApp("Iv23liENTERED00000", "owners-pocketide")
        assertTrue(auth.configured)
        assertEquals("https://github.com/apps/owners-pocketide/installations/new", auth.installUrl())
        server.enqueue(
            json("""{"device_code":"d","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""),
        )
        auth.startDeviceFlow()
        assertEquals(mapOf("client_id" to "Iv23liENTERED00000"), server.next().form())
    }

    @Test
    fun `device code request sends the client ID as a form and reads the code`() = runBlocking {
        server.enqueue(
            json("""{"device_code":"3584d83530557fdd1f46af8289938c8ef79f9dc5","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""),
        )
        val code = auth().startDeviceFlow()
        val request = server.next()
        assertEquals("/login/device/code", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("application/json", request.headers["Accept"])
        assertEquals(mapOf("client_id" to "Iv23liTESTCLIENT"), request.form())
        assertEquals("WDJB-MJHT", code.userCode)
        assertEquals("https://github.com/login/device", code.verificationUri)
        assertEquals(clock.now + 900_000, code.expiresAtMs)
        assertEquals(5, code.intervalSeconds)
    }

    @Test
    fun `polling walks through pending and slow down to a stored connection`() = runBlocking {
        val auth = auth()
        val code = code()
        server.enqueue(json("""{"error":"authorization_pending","error_description":"pending"}"""))
        server.enqueue(json("""{"error":"slow_down","interval":10}"""))
        server.enqueue(json("""{"error":"slow_down"}"""))
        server.enqueue(tokenReply("ghu_new", "ghr_new"))
        server.enqueue(json(user))

        assertEquals(DevicePoll.Pending, auth.poll(code))
        assertEquals(DevicePoll.SlowDown(10), auth.poll(code))
        assertEquals("a second slow_down without an interval adds 5 s", DevicePoll.SlowDown(15), auth.poll(code))
        val connected = auth.poll(code) as DevicePoll.Connected
        assertEquals("octo", connected.account.login)
        assertEquals(connected.account, auth.account.value)

        val poll = server.next()
        assertEquals("/login/oauth/access_token", poll.url.encodedPath)
        assertEquals(
            mapOf("client_id" to "Iv23liTESTCLIENT", "device_code" to code.deviceCode, "grant_type" to DeviceFlowAuth.DEVICE_GRANT),
            poll.form(),
        )
        repeat(3) { server.next() }
        assertEquals("Bearer ghu_new", server.next().headers["Authorization"])

        val restored = auth()
        assertEquals("the account comes back from storage", "octo", restored.account.value?.login)
        assertEquals("ghu_new", restored.token())
    }

    @Test
    fun `GitHub's answer to an unknown client ID says to copy it again`() = runBlocking {
        val auth = auth(clientId = "Iv24abcDEF0123456789xy")
        server.enqueue(json("""{"error":"Not Found"}""", code = 404))
        server.enqueue(json("""{"error":"incorrect_client_credentials"}"""))
        server.enqueue(json("""{"error":"Not Found"}"""))
        repeat(3) {
            try {
                auth.startDeviceFlow()
                fail("expected GitHubException")
            } catch (e: GitHubException) {
                assertEquals(GitHubText.BAD_CLIENT_ID, e.message)
            }
        }
        assertEquals(mapOf("client_id" to "Iv24abcDEF0123456789xy"), server.next().form())
    }

    @Test
    fun `denied and expired codes end the flow`() = runBlocking {
        val auth = auth()
        server.enqueue(json("""{"error":"access_denied"}"""))
        server.enqueue(json("""{"error":"expired_token"}"""))
        server.enqueue(json("""{"error":"token_expired"}"""))
        server.enqueue(json("""{"error":"device_flow_disabled"}"""))
        assertEquals(DevicePoll.Denied, auth.poll(code()))
        assertEquals(DevicePoll.Expired, auth.poll(code()))
        assertEquals(DevicePoll.Expired, auth.poll(code()))
        assertEquals(DevicePoll.Failed(GitHubText.DEVICE_FLOW_OFF), auth.poll(code()))
        assertNull(auth.account.value)
    }

    @Test
    fun `a token with plenty of time left is used as it is`() = runBlocking {
        signedIn()
        assertEquals("ghu_old", auth().token())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a token close to expiry is refreshed without a client secret and both tokens rotate`() = runBlocking {
        signedIn(expiresInMs = 2 * 60_000L)
        server.enqueue(tokenReply("ghu_new", "ghr_new"))
        val auth = auth()
        assertEquals("ghu_new", auth.token())
        val form = server.next().form()
        assertEquals(mapOf("client_id" to "Iv23liTESTCLIENT", "grant_type" to "refresh_token", "refresh_token" to "ghr_old"), form)
        assertFalse(form.containsKey("client_secret"))
        val saved = TokenStore(store).load()!!
        assertEquals("ghr_new", saved.refresh)
        assertEquals(clock.now + 28_800_000, saved.accessExpiresAt)
        assertEquals("the account is kept across a refresh", "octo", saved.login)
    }

    @Test
    fun `a bad refresh token signs out and reports revoked`() = runBlocking {
        signedIn(expiresInMs = 60_000L)
        server.enqueue(json("""{"error":"bad_refresh_token","error_description":"The refresh token passed is incorrect or expired."}"""))
        val auth = auth()
        try {
            auth.token()
            fail("expected NotConnectedException")
        } catch (e: NotConnectedException) {
            assertEquals(GitHubText.ACCESS_REMOVED, e.message)
        }
        assertNull(auth.account.value)
        assertNull(TokenStore(store).load())
        assertEquals(LinkHealth.REVOKED, auth.health())
        assertEquals("the lock survives a restart", LinkHealth.REVOKED, auth().health())
    }

    @Test
    fun `concurrent callers share one refresh`() = runBlocking {
        signedIn(expiresInMs = 60_000L)
        server.enqueue(
            MockResponse.Builder()
                .body("""{"access_token":"ghu_new","expires_in":28800,"refresh_token":"ghr_new","refresh_token_expires_in":15897600}""")
                .headersDelay(300, TimeUnit.MILLISECONDS)
                .build(),
        )
        val auth = auth()
        val tokens = (1..20).map { async(Dispatchers.IO) { auth.token() } }.awaitAll()
        assertEquals(List(20) { "ghu_new" }, tokens)
        assertEquals("rotation means a second refresh would sign the owner out", 1, server.requestCount)
    }

    @Test
    fun `an unreachable refresh keeps using a token that has not expired yet`() = runBlocking {
        signedIn(expiresInMs = 60_000L)
        val closed = MockWebServer().apply { start() }
        val offline = closed.url("/")
        closed.close()
        assertEquals("ghu_old", auth(oauth = offline).token())
    }

    @Test
    fun `health is OK when GitHub accepts the token and offline without a connection`() = runBlocking {
        signedIn()
        server.enqueue(json(user.replace("Octo Cat", "Octo Renamed")))
        val auth = auth()
        assertEquals(LinkHealth.OK, auth.health())
        assertEquals("Octo Renamed", auth.account.value?.name)

        val closed = MockWebServer().apply { start() }
        val offlineAuth = authFor({ GitHubApp("Iv23li", "") }, oauth = closed.url("/"), api = closed.url("/"))
        closed.close()
        assertEquals(LinkHealth.OFFLINE, offlineAuth.health())
    }

    @Test
    fun `a 401 that survives one refresh means access was removed`() = runBlocking {
        signedIn()
        server.enqueue(status(401))
        server.enqueue(tokenReply("ghu_new", "ghr_new"))
        server.enqueue(status(401))
        val auth = auth()
        assertEquals(LinkHealth.REVOKED, auth.health())
        assertEquals("Bearer ghu_old", server.next().headers["Authorization"])
        assertEquals("refresh_token", server.next().form()["grant_type"])
        assertEquals("Bearer ghu_new", server.next().headers["Authorization"])
        assertNull(auth.account.value)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `signing out forgets the tokens and is not a revocation`() = runBlocking {
        signedIn()
        val auth = auth()
        auth.signOut()
        assertNull(auth.account.value)
        assertNull(TokenStore(store).load())
        assertEquals(LinkHealth.NOT_CONNECTED, auth.health())
        try {
            auth.token()
            fail("expected NotConnectedException")
        } catch (expected: NotConnectedException) {
            assertEquals(GitHubText.NOT_CONNECTED, expected.message)
        }
    }

    @Test
    fun `install link goes to the app's own install page when its slug is known`() {
        assertEquals("https://github.com/apps/pocketide-test/installations/new", auth().installUrl())
        assertEquals("https://github.com/settings/installations", auth(slug = "").installUrl())
        assertEquals("https://github.com/settings/installations", auth(slug = "../evil").installUrl())
    }

    @Test
    fun `stored tokens never print`() {
        val tokens = StoredTokens(access = "ghu_secret", refresh = "ghr_secret", login = "octo", id = 1)
        assertFalse(tokens.toString().contains("secret"))
    }

    private fun code() = DeviceCode("3584d83530557fdd1f46af8289938c8ef79f9dc5", "WDJB-MJHT", "https://github.com/login/device", clock.now + 900_000, 5)
}
