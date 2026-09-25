package com.pocketide.update

import android.app.Activity
import com.pocketide.agents.SemVer
import com.pocketide.model.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdaterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val repo = "owner/CloudSaver"
    private var releases: JsonArray = JsonArray(emptyList())
    private val files = mutableMapOf<String, ByteArray>()
    private var listStatus = 200
    private val self = ApkFacts("com.pocketide", 300, "3.0.0", setOf("aa"))
    private var allowed = Decision.YES

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/repos/$repo/releases" -> MockResponse.Builder().code(listStatus).body(releases.toString()).build()
                    files.containsKey(path) -> MockResponse.Builder().body(Buffer().write(files.getValue(path))).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun github() = GitHubReleases(client, repo, "pocketide-v", api = server.url("/api/"), downloads = server.url("/"))

    /** An APK stand-in: Android's reading of it is "package|versionCode|signer". */
    private fun apk(pkg: String = "com.pocketide", code: Long = 310, signer: String = "aa") = "$pkg|$code|$signer".toByteArray()

    private fun release(tag: String, apk: ByteArray? = null, draft: Boolean = false, prerelease: Boolean = false, url: String? = null, digest: String? = null): JsonObject {
        val path = "/$repo/releases/download/$tag/PocketIDE-$tag.apk"
        if (apk != null) files[path] = apk
        return buildJsonObject {
            put("tag_name", tag)
            put("draft", draft)
            put("prerelease", prerelease)
            put("body", "Notes for $tag")
            put("published_at", "2026-10-01T00:00:00Z")
            put(
                "assets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "PocketIDE-$tag.apk")
                            put("size", apk?.size ?: 10)
                            put("browser_download_url", url ?: server.url(path).toString())
                            digest?.let { put("digest", it) }
                        },
                    )
                    add(buildJsonObject { put("name", "notes.txt"); put("size", 3); put("browser_download_url", server.url("/$repo/releases/download/$tag/notes.txt").toString()) })
                },
            )
        }
    }

    private fun publish(vararg items: JsonObject) {
        releases = JsonArray(items.toList())
    }

    private fun updater(env: FakeUpdaterEnv = FakeUpdaterEnv()) = SelfUpdater(env, github(), ApkFetcher(client, { allowed }, {}))

    private inner class FakeUpdaterEnv : UpdaterEnv {
        override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override val apkFolder = File(temp.root, "apk/update")
        override val currentVersion = "3.0.0"
        override val pinnedSigner = ""
        override fun self() = this@UpdaterTest.self
        override fun inspect(file: File): ApkFacts? {
            val parts = file.readText().split('|')
            if (parts.size != 3) return null
            return ApkFacts(parts[0], parts[1].toLong(), null, setOf(parts[2]))
        }
        override fun mayInstall(activity: Activity) = true
        override suspend fun install(activity: Activity, apk: File, done: (InstallResult) -> Unit) = done(InstallResult.Installed)
        override fun schedule() = Unit
    }

    @Test
    fun theNewestFullReleaseAboveThisVersionIsOffered() = runBlocking<Unit> {
        publish(
            release("pocketide-v3.2.0", draft = true),
            release("pocketide-v3.1.5", prerelease = true),
            release("pocketide-v3.1.0-beta.1"),
            release("pocketide-v3.1.0", digest = "sha256:" + "ab".repeat(32)),
            release("pocketide-v2.9.0"),
            release("cloudsaver-v9.0.0"),
        )

        val found = github().newest(SemVer(3, 0, 0))

        assertEquals("3.1.0", found?.version)
        assertEquals("pocketide-v3.1.0", found?.tag)
        assertEquals("ab".repeat(32), found?.sha256)
        assertTrue(found!!.apkUrl.endsWith("/PocketIDE-pocketide-v3.1.0.apk"))
        assertNull(github().newest(SemVer(3, 1, 0)))
    }

    @Test
    fun anApkFromAnywhereElseIsNotOffered() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", url = "https://example.com/owner/CloudSaver/releases/download/pocketide-v3.1.0/x.apk"))

        assertNull(github().newest(SemVer(3, 0, 0)))
    }

    @Test
    fun githubRefusingSaysSoPlainly() {
        listStatus = 403

        val failure = assertThrows(IOException::class.java) { runBlocking { github().newest(SemVer(3, 0, 0)) } }

        assertEquals("GitHub is limiting requests. Try again in an hour.", failure.message)
    }

    @Test
    fun anUpdateSignedWithThisAppsKeyBecomesReady() = runBlocking<Unit> {
        val bytes = apk()
        publish(release("pocketide-v3.1.0", bytes, digest = "sha256:" + sha256(bytes)))
        val updater = updater()

        updater.check()
        assertTrue(updater.state.value is UpdateState.Available)
        updater.download()

        val ready = updater.state.value as UpdateState.Ready
        assertEquals("3.1.0", ready.release.version)
        assertTrue(File(temp.root, "apk/update/pocketide-3.1.0.apk").isFile)

        // A later check finds the file already there and checked.
        val again = updater()
        again.check()
        assertTrue(again.state.value is UpdateState.Ready)
    }

    @Test
    fun anUpdateSignedWithAnotherKeyIsDeleted() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk(signer = "bb")))
        val updater = updater()

        updater.check()
        updater.download()

        val failed = updater.state.value as UpdateState.Failed
        assertTrue(failed.why, failed.why.contains("not signed with PocketIDE's key"))
        assertFalse(File(temp.root, "apk/update/pocketide-3.1.0.apk").exists())
    }

    @Test
    fun aFileThatDoesNotMatchGitHubsChecksumIsNotKept() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk(), digest = "sha256:" + "00".repeat(32)))
        val updater = updater()

        updater.check()
        updater.download()

        assertTrue(updater.state.value is UpdateState.Failed)
        assertTrue(File(temp.root, "apk/update").list().orEmpty().isEmpty())
    }

    @Test
    fun theDownloadWaitsForWifiWhenTheRulesSaySo() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk()))
        allowed = Decision.no("The update waits for Wi-Fi.")
        val updater = updater()
        updater.check()

        val failure = assertThrows(UpdateWaits::class.java) { runBlocking { updater.download() } }

        assertEquals("The update waits for Wi-Fi.", failure.message)
        assertTrue(updater.state.value is UpdateState.Available)
    }

    @Test
    fun anOlderApkLeftFromBeforeIsRemovedOnTheNextCheck() = runBlocking<Unit> {
        val folder = File(temp.root, "apk/update").apply { mkdirs() }
        File(folder, "pocketide-2.9.0.apk").writeText("old")
        File(folder, "pocketide-3.1.0.apk.part").writeText("cut")
        publish()
        val updater = updater()

        updater.check()

        assertEquals(UpdateState.UpToDate, updater.state.value)
        assertTrue(folder.list().orEmpty().isEmpty())
    }

    @Test
    fun theRulesNameEachReasonToRefuse() {
        assertNull(UpdateRules.problem(ApkFacts("com.pocketide", 310, null, setOf("aa")), self, "AA"))
        assertTrue(UpdateRules.problem(null, self, null)!!.contains("not an app"))
        assertTrue(UpdateRules.problem(ApkFacts("com.other", 310, null, setOf("aa")), self, null)!!.contains("different app"))
        assertTrue(UpdateRules.problem(ApkFacts("com.pocketide", 300, null, setOf("aa")), self, null)!!.contains("not newer"))
        assertTrue(UpdateRules.problem(ApkFacts("com.pocketide", 310, null, emptySet()), self, null)!!.contains("not signed"))
        assertTrue(UpdateRules.problem(ApkFacts("com.pocketide", 310, null, setOf("aa", "bb")), self, null)!!.contains("PocketIDE's key"))
        assertTrue(UpdateRules.problem(ApkFacts("com.pocketide", 310, null, setOf("aa")), self, "cc")!!.contains("release key"))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
