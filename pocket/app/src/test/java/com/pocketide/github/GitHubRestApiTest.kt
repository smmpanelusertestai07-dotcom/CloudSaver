package com.pocketide.github

import com.pocketide.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
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
        """{"name":"$name","owner":{"login":"octo"},"private":$private,"visibility":"private","default_branch":"main","size":42,
           "clone_url":"https://github.com/octo/$name.git","html_url":"https://github.com/octo/$name","pushed_at":"2026-09-20T10:00:00Z"}"""

    private inline fun <reified E : Throwable> failsWith(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        fail("expected ${E::class.simpleName}")
        throw AssertionError()
    }

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
        server.enqueue(MockResponse.Builder().code(204).build())
        assertNull(api.dispatchWorkflowRun("octo", "demo", "ci.yml", "main"))
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
        val page2 = server.url("/repos/octo/demo/collaborators?affiliation=all&per_page=100&page=2")
        server.enqueue(
            MockResponse.Builder().body("""[{"login":"octo"},{"login":"hubot"}]""")
                .addHeader("Link", "<$page2>; rel=\"next\", <$page2>; rel=\"last\"").build(),
        )
        server.enqueue(json("""[{"login":"mona"}]"""))
        assertEquals(listOf("octo", "hubot", "mona"), api.collaborators("octo", "demo"))
        val first = server.next().url
        assertEquals("100", first.queryParameter("per_page"))
        assertEquals("all", first.queryParameter("affiliation"))
        assertEquals("2", server.next().url.queryParameter("page"))
    }

    @Test
    fun `a page link to another host is never followed with the token`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().body("""[{"login":"octo"}]""")
                .addHeader("Link", "<https://evil.example/next>; rel=\"next\"").build(),
        )
        assertEquals(listOf("octo"), api.collaborators("octo", "demo"))
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

        server.enqueue(json("""{"message":"Repository creation failed.","errors":[{"resource":"Repository","code":"custom","field":"name","message":"name already exists on this account"}]}""", 422))
        val taken = failsWith<GitHubException> { runBlocking { api.createPrivateRepo("fresh", "") } }
        assertEquals(GitHubText.NAME_TAKEN, taken.message)
    }

    @Test
    fun `actions can be switched off`() = runBlocking {
        server.enqueue(status(204))
        api.setActionsEnabled("octo", "pocketide-keyring", enabled = false)
        val request = server.next()
        assertEquals("PUT", request.method)
        assertEquals("/repos/octo/pocketide-keyring/actions/permissions", request.url.encodedPath)
        assertEquals("""{"enabled":false}""", request.text())
    }

    @Test
    fun `files are read and written through the contents API in base64`() = runBlocking {
        val content = Base64.getMimeEncoder().encodeToString("hello keyring".toByteArray())
        server.enqueue(json("""{"type":"file","encoding":"base64","size":13,"name":"half-g.json","path":"keys/half-g.json","content":"${content.replace("\r\n", "\\n")}","sha":"abc123"}"""))
        val file = api.readFile("octo", "pocketide-keyring", "keys/half-g.json")!!
        assertEquals("hello keyring", file.bytes.toString(Charsets.UTF_8))
        assertEquals("abc123", file.sha)
        assertEquals("/repos/octo/pocketide-keyring/contents/keys/half-g.json", server.next().url.encodedPath)

        server.enqueue(json("""{"content":{"sha":"def456"},"commit":{"sha":"c1"}}"""))
        api.writeFile("octo", "pocketide-keyring", "keys/half-g.json", "new".toByteArray(), "Save half", "abc123")
        val put = body(server.next().text())
        assertEquals(Base64.getEncoder().encodeToString("new".toByteArray()), put["content"]!!.jsonPrimitive.content)
        assertEquals("abc123", put["sha"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"message":"is at def456 but expected abc123"}""", 409))
        val stale = failsWith<GitHubException> {
            runBlocking { api.writeFile("octo", "pocketide-keyring", "keys/half-g.json", "x".toByteArray(), "m", "abc123") }
        }
        assertEquals(GitHubText.CONFLICT, stale.message)

        server.enqueue(json("""{"message":"Not Found"}""", 404))
        assertNull(api.readFile("octo", "pocketide-keyring", "keys/none.json"))
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
    fun `the Actions secret is sealed with the repository key and sent with its id`() = runBlocking {
        val key = ByteArray(32) { it.toByte() }
        server.enqueue(json("""{"key_id":"568250167242549743","key":"${Base64.getEncoder().encodeToString(key)}"}"""))
        server.enqueue(status(201))
        api.setActionsSecret("octo", "demo", "DEPLOY_TOKEN", "s3cret".toByteArray())

        assertEquals("/repos/octo/demo/actions/secrets/public-key", server.next().url.encodedPath)
        assertArrayEquals(key, fx.sealed.single().first)
        assertArrayEquals("s3cret".toByteArray(), fx.sealed.single().second)
        val put = server.next()
        assertEquals("PUT", put.method)
        assertEquals("/repos/octo/demo/actions/secrets/DEPLOY_TOKEN", put.url.encodedPath)
        val sent = body(put.text())
        assertEquals("568250167242549743", sent["key_id"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString("sealed:s3cret".toByteArray()), sent["encrypted_value"]!!.jsonPrimitive.content)
        assertFalse("the plain value never travels", put.text().contains("s3cret"))
    }

    @Test
    fun `secret names GitHub refuses are caught before sealing`() = runBlocking {
        for (bad in listOf("GITHUB_TOKEN", "1ABC", "HAS SPACE", "dash-name")) {
            failsWith<IllegalArgumentException> { runBlocking { api.setActionsSecret("octo", "demo", bad, ByteArray(1)) } }
        }
        assertEquals(0, server.requestCount)
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
        failsWith<GitHubException> { runBlocking { api.setActionsEnabled("octo", "demo", false) } }
        assertEquals("a write is not repeated", 4, server.requestCount)
    }

    @Test
    fun `an existing pull request is returned instead of an error`() = runBlocking {
        server.enqueue(json("""{"message":"Validation Failed","errors":[{"message":"A pull request already exists for octo:session-1."}]}""", 422))
        server.enqueue(json("""[{"number":5,"html_url":"https://github.com/octo/demo/pull/5","state":"open","mergeable":true}]"""))
        val pr = api.openPullRequest("octo", "demo", "session-1", "main", "Title", "Body")
        assertEquals(5, pr.number)
        server.next()
        val lookup = server.next().url
        assertEquals("octo:session-1", lookup.queryParameter("head"))
        assertEquals("main", lookup.queryParameter("base"))
    }

    @Test
    fun `a session's pull request is found by its branch, merged or not`() = runBlocking {
        server.enqueue(json("""[{"number":7,"html_url":"https://github.com/octo/demo/pull/7","state":"closed","merged_at":"2026-09-24T08:00:00Z"}]"""))
        val pr = api.pullRequestFor("octo", "demo", "pocket/claude/login")!!
        assertEquals(7, pr.number)
        assertTrue("a list gives merged_at, not merged", pr.merged)
        val lookup = server.next().url
        assertEquals("/repos/octo/demo/pulls", lookup.encodedPath)
        assertEquals("octo:pocket/claude/login", lookup.queryParameter("head"))
        assertEquals("all", lookup.queryParameter("state"))
        assertEquals("desc", lookup.queryParameter("direction"))

        server.enqueue(json("[]"))
        assertEquals(null, api.pullRequestFor("octo", "demo", "pocket/claude/other"))
    }

    @Test
    fun `merge reports false when GitHub cannot merge`() = runBlocking {
        server.enqueue(json("""{"sha":"6dcb09b","merged":true,"message":"Pull Request successfully merged"}"""))
        assertTrue(api.mergePullRequest("octo", "demo", 5, "squash"))
        assertEquals("squash", body(server.next().text())["merge_method"]!!.jsonPrimitive.content)
        server.enqueue(json("""{"message":"Pull Request is not mergeable"}""", 405))
        assertFalse(api.mergePullRequest("octo", "demo", 5))
    }

    @Test
    fun `a dispatch returns the exact run it started`() = runBlocking {
        server.enqueue(json("""{"workflow_run_id":30433642,"run_url":"https://api.github.com/repos/octo/demo/actions/runs/30433642","html_url":"https://github.com/octo/demo/actions/runs/30433642"}"""))
        val run = api.dispatchWorkflowRun("octo", "demo", "android.yml", "session-1", mapOf("variant" to "release"))!!
        assertEquals(30433642L, run.runId)
        val request = server.next()
        assertEquals("/repos/octo/demo/actions/workflows/android.yml/dispatches", request.url.encodedPath)
        val sent = body(request.text())
        assertEquals("session-1", sent["ref"]!!.jsonPrimitive.content)
        assertEquals("release", sent["inputs"]!!.jsonObject["variant"]!!.jsonPrimitive.content)

        server.enqueue(status(204))
        assertNull(api.dispatchWorkflowRun("octo", "demo", "android.yml", "main"))
    }

    @Test
    fun `runs show the runner the newest ones asked for`() = runBlocking {
        server.enqueue(
            json(
                """{"total_count":1,"workflow_runs":[{"id":9,"name":"Android","head_branch":"main","status":"completed","conclusion":"success",
                "created_at":"2026-09-20T10:00:00Z","updated_at":"2026-09-20T10:09:00Z","html_url":"https://github.com/octo/demo/actions/runs/9"}]}""",
            ),
        )
        server.enqueue(
            json(
                """{"total_count":1,"jobs":[{"id":3,"run_id":9,"name":"build","status":"completed","conclusion":"success","labels":["ubuntu-latest"],
                "runner_name":"GitHub Actions 2","steps":[{"name":"Set up job","number":1,"status":"completed","conclusion":"success"}]}]}""",
            ),
        )
        val run = api.runs("octo", "demo", branch = "main").single()
        assertEquals("ubuntu-latest", run.runnerImage)
        assertEquals("success", run.conclusion)
        assertEquals("main", server.next().url.queryParameter("branch"))
        assertEquals("/repos/octo/demo/actions/runs/9/jobs", server.next().url.encodedPath)
    }

    @Test
    fun `a job log gives the runner image and the last lines`() = runBlocking {
        val log = buildString {
            append("2026-09-20T10:00:01.0000000Z ##[group]Runner Image\n")
            append("2026-09-20T10:00:01.0000000Z Image: ubuntu-24.04\n")
            append("2026-09-20T10:00:01.0000000Z Version: 20260914.1\n")
            repeat(5000) { append("2026-09-20T10:01:00.0000000Z line $it of the build output\n") }
            append("2026-09-20T10:09:00.0000000Z BUILD FAILED\n")
        }
        val blob = MockWebServer().apply { start() }
        blob.enqueue(MockResponse.Builder().body(log).build())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", blob.url("/logs/3?sig=abc")).build())
        val result = api.jobLog("octo", "demo", 3)!!
        assertEquals("ubuntu-24.04 20260914.1", result.runnerImage)
        assertTrue(result.tail.endsWith("BUILD FAILED\n"))
        assertTrue(result.tail.startsWith("2026-09-20T10:01:00"))
        assertNull("the pre-signed log URL never receives the token", blob.next().headers["Authorization"])
        blob.close()
    }

    @Test
    fun `artifacts download from the redirect without the token and are checked`() = runBlocking {
        val zip = "PK zip bytes"
        val digest = "sha256:" + zip.encodeUtf8().sha256().hex()
        val blob = MockWebServer().apply { start() }
        blob.enqueue(MockResponse.Builder().body(zip).build())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", blob.url("/blob?sig=1")).build())
        val dest = File(tempDir(), "app.zip")
        val artifact = RunArtifact(11, "app", zip.length.toLong(), false, server.url("/repos/octo/demo/actions/artifacts/11/zip").toString(), digest)
        api.downloadArtifact(artifact, dest)
        assertEquals(zip, dest.readText())
        assertEquals("Bearer ghu_first", server.next().headers["Authorization"])
        assertNull(blob.next().headers["Authorization"])

        blob.enqueue(MockResponse.Builder().body("tampered").build())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", blob.url("/blob?sig=2")).build())
        val other = File(dest.parentFile, "other.zip")
        val e = failsWith<GitHubException> { runBlocking { api.downloadArtifact(artifact, other) } }
        assertEquals(GitHubText.DIGEST_MISMATCH, e.message)
        assertFalse(other.exists())
        assertEquals("no partial file is left behind", listOf("app.zip"), dest.parentFile!!.list()!!.toList())
        blob.close()
    }

    @Test
    fun `an expired artifact and a foreign link are plain errors`() = runBlocking {
        server.enqueue(json("""{"message":"Artifact has expired"}""", 410))
        val dest = File(tempDir(), "gone.zip")
        val url = server.url("/repos/octo/demo/actions/artifacts/12/zip").toString()
        val gone = failsWith<GitHubException> { runBlocking { api.downloadArtifact(RunArtifact(12, "a", 1, true, url), dest) } }
        assertEquals(GitHubText.GONE, gone.message)

        val foreign = failsWith<GitHubException> {
            runBlocking { api.downloadArtifact(RunArtifact(13, "a", 1, false, "https://evil.example/zip"), dest) }
        }
        assertEquals(GitHubText.NOT_FROM_GITHUB, foreign.message)
        assertEquals(1, server.requestCount)
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
    fun `repository usage adds cache and live artifacts`() = runBlocking {
        server.enqueue(json(repoJson()))
        server.enqueue(json("""{"full_name":"octo/demo","active_caches_size_in_bytes":2048,"active_caches_count":2}"""))
        server.enqueue(
            json(
                """{"total_count":2,"artifacts":[
                {"id":1,"name":"apk","size_in_bytes":1000,"archive_download_url":"https://api.github.com/x","expired":false},
                {"id":2,"name":"old","size_in_bytes":5000,"archive_download_url":"https://api.github.com/y","expired":true}]}""",
            ),
        )
        val usage = api.repoUsage("octo", "demo")
        assertEquals(42L, usage.repo.sizeKb)
        assertEquals(2048L, usage.cacheBytes)
        assertEquals(1000L, usage.artifactsBytes)
        assertEquals(1, usage.artifactCount)
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
