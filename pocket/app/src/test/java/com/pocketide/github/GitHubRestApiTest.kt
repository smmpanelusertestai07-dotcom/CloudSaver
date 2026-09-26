package com.pocketide.github

import com.pocketide.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GitHubRestApiTest {
    private val server = MockWebServer()
    private val clock = TestClock()
    private lateinit var fx: ApiFixture

    @Before
    fun setUp() {
        server.start()
        fx = ApiFixture(server, clock)
    }

    @After
    fun tearDown() = server.close()

    private val api get() = fx.api

    private fun body(text: String): JsonObject = AppJson.parseToJsonElement(text).jsonObject

    private fun repoJson(name: String = "demo", private: Boolean = true) =
        """{"id":${name.length},"name":"$name","owner":{"login":"octo"},"private":$private,"visibility":"private","default_branch":"main","size":42,
           "clone_url":"https://github.com/octo/$name.git","html_url":"https://github.com/octo/$name","pushed_at":"2026-09-20T10:00:00Z"}"""

    private inline fun <reified E : Throwable> failsWith(crossinline block: () -> Unit): E = assertThrows(E::class.java) { block() }

    @Test
    fun `every call carries the documented headers`() = runBlocking {
        server.enqueue(json("""{"login":"octo","id":7,"name":null,"avatar_url":null}"""))
        assertEquals(GitHubAccount("octo", 7, null, null), api.me())
        val request = server.next()
        assertEquals("Bearer ghu_first", request.headers["Authorization"])
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals("2026-03-10", request.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun `once GitHub retires the API version, calls go on without it`() = runBlocking {
        server.enqueue(json("""{"message":"Unsupported 'X-GitHub-Api-Version' header: API version 2026-03-10 is no longer supported."}""", 410))
        server.enqueue(json("""{"login":"octo","id":7,"name":null,"avatar_url":null}"""))
        assertEquals("octo", api.me().login)
        assertEquals("2026-03-10", server.next().headers["X-GitHub-Api-Version"])
        assertNull("the same call again, without the version", server.next().headers["X-GitHub-Api-Version"])

        // A write refused for its version is sent again too, and every later call leaves the version out.
        fx.apiVersion.retired = false
        server.enqueue(json("""{"message":"Invalid API version"}""", 400))
        server.enqueue(json(repoJson("fresh"), 201))
        assertEquals("fresh", api.createPrivateRepo("fresh", "").name)
        server.enqueue(json("""{"login":"octo","id":7,"name":null,"avatar_url":null}"""))
        api.me()
        assertEquals("2026-03-10", server.next().headers["X-GitHub-Api-Version"])
        assertEquals(listOf(null, null), listOf(server.next(), server.next()).map { it.headers["X-GitHub-Api-Version"] })
        assertTrue(fx.apiVersion.retired)
    }

    @Test
    fun `an answer that is not about the API version keeps it`() = runBlocking {
        server.enqueue(json("""{"message":"Problems parsing JSON"}""", 400))
        val refused = failsWith<GitHubException> { runBlocking { api.me() } }
        assertEquals(GitHubText.REJECTED, refused.message)
        assertFalse(fx.apiVersion.retired)
        assertEquals(1, server.requestCount)
        assertEquals(GitHubText.API_RETIRED, GitHubErrors.of(410, """{"message":"API version 2026-03-10 is not supported"}""").message)
    }

    @Test
    fun `lists follow the Link header with 100 per page`() = runBlocking {
        val page2 = server.url("/user/installations?per_page=100&page=2")
        server.enqueue(
            MockResponse.Builder().body("""{"installations":[{"id":1},{"id":2}]}""")
                .addHeader("Link", "<$page2>; rel=\"next\", <$page2>; rel=\"last\"").build(),
        )
        server.enqueue(json("""{"installations":[{"id":3}]}"""))
        val ids = fx.rest.pages(fx.rest.url("user", "installations")) { decode(InstallationsPage.serializer(), it).installations.map { i -> i.id } }
        assertEquals(listOf(1L, 2L, 3L), ids)
        assertEquals("100", server.next().url.queryParameter("per_page"))
        assertEquals("2", server.next().url.queryParameter("page"))
    }

    @Test
    fun `a page link to another host is never followed with the token`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().body("""{"installations":[{"id":1}]}""")
                .addHeader("Link", "<https://evil.example/next>; rel=\"next\"").build(),
        )
        val ids = fx.rest.pages(fx.rest.url("user", "installations")) { decode(InstallationsPage.serializer(), it).installations.map { i -> i.id } }
        assertEquals(listOf(1L), ids)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the Import picker lists repositories of active installations once each`() = runBlocking {
        server.enqueue(json("""{"total_count":2,"installations":[{"id":1},{"id":2,"suspended_at":"2026-01-01T00:00:00Z"}]}"""))
        server.enqueue(json("""{"total_count":2,"repository_selection":"selected","repositories":[${repoJson("a")},${repoJson("b")}]}"""))
        val repos = api.repos()
        assertEquals(listOf("a", "b"), repos.map { it.name })
        assertEquals("/user/installations", server.next().url.encodedPath)
        assertEquals("/user/installations/1/repositories", server.next().url.encodedPath)
        assertEquals("the suspended installation is skipped", 2, server.requestCount)
    }

    @Test
    fun `a missing repository is null and a refused one is a plain sentence`() = runBlocking {
        server.enqueue(json("""{"message":"Not Found"}""", 404))
        assertNull(api.repo("octo", "gone"))
        server.enqueue(json("""{"message":"Resource not accessible by integration"}""", 403))
        val refused = failsWith<GitHubException> { runBlocking { api.repo("octo", "locked") } }
        assertEquals(GitHubText.FORBIDDEN, refused.message)
        assertEquals(403, refused.status)
    }

    @Test
    fun `a fork is marked as one`() = runBlocking {
        server.enqueue(json(repoJson("theirs").replace("\"size\":42", "\"size\":42,\"fork\":true")))
        assertTrue(api.repo("octo", "theirs")!!.fork)
        server.enqueue(json(repoJson("mine")))
        assertFalse(api.repo("octo", "mine")!!.fork)
    }

    @Test
    fun `names that could change the path are refused before any call`() = runBlocking {
        failsWith<IllegalArgumentException> { runBlocking { api.repo("octo", "../admin") } }
        failsWith<IllegalArgumentException> { runBlocking { api.readFile("octo", "demo", "a/../../b") } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `new repositories are always private and start with a commit`() = runBlocking {
        server.enqueue(json(repoJson("fresh"), 201))
        val info = api.createPrivateRepo("fresh", "My app")
        assertTrue(info.isPrivate)
        val request = server.next()
        assertEquals("/user/repos", request.url.encodedPath)
        val sent = body(request.text())
        assertEquals("fresh", sent["name"]!!.jsonPrimitive.content)
        assertEquals("My app", sent["description"]!!.jsonPrimitive.content)
        assertTrue(sent["private"]!!.jsonPrimitive.boolean)
        assertTrue(sent["auto_init"]!!.jsonPrimitive.boolean)

        val nameTaken = """{"resource":"Repository","code":"custom","field":"name","message":"name already exists on this account"}"""
        server.enqueue(json("""{"message":"Repository creation failed.","errors":[$nameTaken]}""", 422))
        val taken = failsWith<GitHubException> { runBlocking { api.createPrivateRepo("fresh", "") } }
        assertEquals(GitHubText.NAME_TAKEN, taken.message)
    }

    @Test
    fun `files are read through the contents API in base64`() = runBlocking {
        val content = Base64.getMimeEncoder().encodeToString("{ }".toByteArray())
        val encoded = content.replace("\r\n", "\\n")
        server.enqueue(json("""{"type":"file","encoding":"base64","size":3,"name":"devcontainer.json","content":"$encoded","sha":"abc123"}"""))
        val file = api.readFile("octo", "demo", ".devcontainer/pocketide/devcontainer.json", ref = "main")!!
        assertEquals("{ }", file.bytes.toString(Charsets.UTF_8))
        assertEquals("abc123", file.sha)
        val request = server.next().url
        assertEquals("/repos/octo/demo/contents/.devcontainer/pocketide/devcontainer.json", request.encodedPath)
        assertEquals("main", request.queryParameter("ref"))

        server.enqueue(json("""{"message":"Not Found"}""", 404))
        assertNull(api.readFile("octo", "demo", "none.json"))
    }

    @Test
    fun `files are committed together on top of the branch, never forced`() = runBlocking {
        server.enqueue(json("""{"ref":"refs/heads/main","object":{"sha":"head1","type":"commit"}}"""))
        server.enqueue(json("""{"sha":"head1","tree":{"sha":"tree1"}}"""))
        server.enqueue(json("""{"sha":"tree2"}""", 201))
        server.enqueue(json("""{"sha":"commit2"}""", 201))
        server.enqueue(json("""{"ref":"refs/heads/main","object":{"sha":"commit2"}}"""))
        val files = listOf(NewFile("a/b.json", "{}"), NewFile("a/run.sh", "echo", executable = true))
        assertEquals("commit2", api.commitFiles("octo", "demo", "main", files, "Set up"))

        assertEquals("/repos/octo/demo/git/ref/heads/main", server.next().url.encodedPath)
        assertEquals("/repos/octo/demo/git/commits/head1", server.next().url.encodedPath)
        val tree = body(server.next().text())
        assertEquals("tree1", tree["base_tree"]!!.jsonPrimitive.content)
        val modes = tree["tree"]!!.jsonArray.map { it.jsonObject["mode"]!!.jsonPrimitive.content }
        assertEquals(listOf("100644", "100755"), modes)
        val commit = body(server.next().text())
        assertEquals("tree2", commit["tree"]!!.jsonPrimitive.content)
        assertEquals("head1", commit["parents"]!!.jsonArray.single().jsonPrimitive.content)
        val move = server.next()
        assertEquals("PATCH", move.method)
        assertEquals("/repos/octo/demo/git/refs/heads/main", move.url.encodedPath)
        assertFalse(body(move.text())["force"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `recent runs are one request`() = runBlocking {
        server.enqueue(
            json(
                """{"total_count":1,"workflow_runs":[{"id":9,"name":"Build","head_branch":"main","status":"completed",
                "conclusion":"success","created_at":"2026-09-26T10:00:00Z","updated_at":"2026-09-26T10:05:00Z",
                "html_url":"https://github.com/octo/demo/actions/runs/9"}]}""",
            ),
        )
        val runs = api.runs("octo", "demo")
        assertEquals("success", runs.single().conclusion)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a large file is fetched raw`() = runBlocking {
        server.enqueue(json("""{"type":"file","encoding":"none","content":"","sha":"big1","size":2000000}"""))
        server.enqueue(MockResponse.Builder().body("raw bytes").build())
        assertEquals("raw bytes", api.readFile("octo", "demo", "big.bin")!!.bytes.toString(Charsets.UTF_8))
        server.next()
        assertEquals(RestClient.ACCEPT_RAW, server.next().headers["Accept"])
    }

    @Test
    fun `an expired token is renewed once and the call repeated`() = runBlocking {
        server.enqueue(status(401))
        server.enqueue(json("""{"login":"octo","id":7}"""))
        assertEquals("octo", api.me().login)
        assertEquals("Bearer ghu_first", server.next().headers["Authorization"])
        assertEquals("Bearer ghu_second", server.next().headers["Authorization"])
        assertEquals(listOf("ghu_first"), fx.tokens.renewed)
    }

    @Test
    fun `a second 401 means access is gone`() = runBlocking {
        server.enqueue(status(401))
        server.enqueue(status(401))
        val e = failsWith<NotConnectedException> { runBlocking { api.me() } }
        assertEquals(GitHubText.ACCESS_REMOVED, e.message)
        assertEquals(listOf("ghu_second"), fx.tokens.revoked)
    }

    @Test
    fun `a short rate limit is waited out using the reset time`() = runBlocking {
        val reset = clock.now / 1000 + 10
        server.enqueue(
            MockResponse.Builder().code(403).body("""{"message":"API rate limit exceeded"}""")
                .addHeader("x-ratelimit-remaining", "0").addHeader("x-ratelimit-reset", reset.toString()).build(),
        )
        server.enqueue(json("""{"login":"octo","id":7}"""))
        assertEquals("octo", api.me().login)
        assertEquals(listOf(11_000L), fx.waits)
    }

    @Test
    fun `a secondary limit honours retry-after`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("retry-after", "3").build())
        server.enqueue(json("""{"login":"octo","id":7}"""))
        api.me()
        assertEquals(listOf(3_000L), fx.waits)
    }

    @Test
    fun `a long rate limit becomes a message with the time to retry`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("retry-after", "600").build())
        val e = failsWith<GitHubRateLimitException> { runBlocking { api.me() } }
        assertEquals(clock.now + 600_000, e.retryAtMs)
        assertEquals("GitHub asked PocketIDE to slow down. Try again in 10 minutes.", e.message)
        assertTrue(fx.waits.isEmpty())
    }

    @Test
    fun `server errors are retried a bounded number of times for reads only`() = runBlocking {
        repeat(3) { server.enqueue(status(502)) }
        val read = failsWith<GitHubException> { runBlocking { api.me() } }
        assertEquals(GitHubText.SERVER, read.message)
        assertEquals(listOf(1_000L, 2_000L), fx.waits)
        assertEquals(3, server.requestCount)

        server.enqueue(status(502))
        failsWith<GitHubException> { runBlocking { api.createPrivateRepo("fresh", "") } }
        assertEquals("a write is not repeated", 4, server.requestCount)
    }

    @Test
    fun `account usage reads the billing report and the plan`() = runBlocking {
        clock.now = java.time.Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()
        server.enqueue(json("""{"login":"octo","id":7,"plan":{"name":"pro","space":976562499,"private_repos":9999,"collaborators":0}}"""))
        server.enqueue(json(BILLING_SAMPLE))
        val usage = api.accountUsage()
        assertEquals("pro", usage.plan)
        assertEquals("2026-09-01", usage.periodStart)
        assertEquals("2026-09-30", usage.periodEnd)
        assertNull(usage.unavailableReason)
        assertEquals(3, usage.lines.size)
        val linux = usage.lines.first()
        assertEquals("Actions", linux.product)
        assertEquals("Actions Linux", linux.sku)
        assertEquals(120.0, linux.quantity, 0.0)
        assertEquals("minutes", linux.unit)
        assertEquals(0.72, linux.grossAmountUsd, 1e-9)
        assertEquals(0.72, linux.discountAmountUsd, 1e-9)
        assertEquals(0.0, linux.netAmountUsd, 1e-9)
        assertEquals("octo/demo", linux.repository)
        assertEquals(0.62, usage.lines.sumOf { it.netAmountUsd }, 1e-9)

        server.next()
        val report = server.next().url
        assertEquals("/users/octo/settings/billing/usage", report.encodedPath)
        assertEquals("2026", report.queryParameter("year"))
        assertEquals("9", report.queryParameter("month"))
    }

    @Test
    fun `accounts GitHub does not report for get a reason, not zeros`() = runBlocking {
        server.enqueue(json("""{"login":"octo","id":7,"plan":{"name":"free"}}"""))
        server.enqueue(json("""{"message":"Resource not accessible by integration"}""", 403))
        val usage = api.accountUsage()
        assertEquals("free", usage.plan)
        assertTrue(usage.lines.isEmpty())
        assertEquals(GitHubText.USAGE_UNAVAILABLE, usage.unavailableReason)
    }

    @Test
    fun `the App counts as installed only on the owner's own account, and not while suspended`() = runBlocking {
        val org = """{"id":1,"account":{"login":"octo-org"}}"""
        val suspended = """{"id":2,"account":{"login":"octo"},"suspended_at":"2026-01-01T00:00:00Z"}"""
        server.enqueue(json("""{"total_count":2,"installations":[$org,$suspended]}"""))
        assertFalse(api.installedOn("octo"))
        assertEquals("/user/installations", server.next().url.encodedPath)

        server.enqueue(json("""{"total_count":2,"installations":[$org,{"id":3,"account":{"login":"Octo"}}]}"""))
        assertTrue(api.installedOn("octo"))

        server.enqueue(json("""{"total_count":0,"installations":[]}"""))
        assertFalse(api.installedOn("octo"))
    }

    private companion object {
        /** Shaped like the example in GitHub's "Get billing usage report for a user" docs. */
        const val BILLING_SAMPLE = """{"usageItems":[
            {"date":"2026-09-02","product":"Actions","sku":"Actions Linux","quantity":120,"unitType":"minutes","pricePerUnit":0.006,
             "grossAmount":0.72,"discountAmount":0.72,"netAmount":0.0,"repositoryName":"octo/demo"},
            {"date":"2026-09-03","product":"Actions","sku":"Actions macOS 3-core","quantity":10,"unitType":"minutes","pricePerUnit":0.062,
             "grossAmount":0.62,"discountAmount":0.0,"netAmount":0.62,"repositoryName":"octo/ios-app"},
            {"date":"2026-09-03","product":"Actions","sku":"Actions Storage","quantity":1.5,"unitType":"GigabyteHours","pricePerUnit":0.00033602,
             "grossAmount":0.0005,"discountAmount":0.0005,"netAmount":0.0}
        ]}"""
    }
}
