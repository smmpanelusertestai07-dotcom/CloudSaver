package com.pocketide.update

import android.app.Activity
import com.pocketide.agents.SemVer
import com.pocketide.github.PublicReleases
import com.pocketide.model.Decision
import com.pocketide.sync.MeteredDataBudget
import com.pocketide.sync.NeedsMobileData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
    /** GitHub's release list, one JSON array per page. */
    private var pages = listOf("[]")
    private val files = mutableMapOf<String, ByteArray>()
    private var listStatus = 200
    private val self = ApkFacts("com.pocketide", 300, "3.0.0", setOf("aa"))
    private var allowed = Decision.YES
    private var apkDownloads = 0

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = files[path]
                if (path.endsWith(".apk")) apkDownloads++
                return when {
                    path == "/repos/$repo/releases" -> page(request.url.queryParameter("page")?.toInt() ?: 1)
                    body == null -> MockResponse.Builder().code(404).build()
                    else -> MockResponse.Builder().body(Buffer().write(body)).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun page(number: Int): MockResponse {
        if (listStatus != 200) return MockResponse.Builder().code(listStatus).build()
        val builder = MockResponse.Builder().body(pages.getOrElse(number - 1) { "[]" })
        if (number < pages.size) builder.addHeader("Link", "<${server.url("/repos/$repo/releases?per_page=100&page=${number + 1}")}>; rel=\"next\"")
        return builder.build()
    }

    private fun github(): GitHubReleases {
        val releases = PublicReleases(client, server.url("/"))
        return GitHubReleases(client, { releases.list(repo) }, "pocketide-v")
    }

    /** An APK stand-in: Android's reading of it is "package|versionCode|signer". */
    private fun apk(pkg: String = "com.pocketide", code: Long = 310, signer: String = "aa") = "$pkg|$code|$signer".toByteArray()

    /** A release as the release job publishes it (the APK, and SHA256SUMS naming it), as GitHub lists it. */
    private fun release(tag: String, apk: ByteArray? = null, sums: String? = null, prerelease: Boolean = false, draft: Boolean = false): String {
        val version = tag.substringAfterLast("-v")
        val name = "PocketIDE-$version-arm64-v8a-release.apk"
        val assets = if (apk == null) {
            ""
        } else {
            val sumsBytes = (sums ?: "${sha256(apk)}  $name\n").toByteArray()
            files["/download/$tag/$name"] = apk
            files["/download/$tag/SHA256SUMS"] = sumsBytes
            listOf(name to apk.size, "SHA256SUMS" to sumsBytes.size).joinToString(",") { (file, size) ->
                """{"name":"$file","size":$size,"browser_download_url":"${server.url("/download/$tag/$file")}"}"""
            }
        }
        return """{"tag_name":"$tag","body":"**Notes** for $tag & more","published_at":"2026-10-01T00:00:00Z",""" +
            """"draft":$draft,"prerelease":$prerelease,"assets":[$assets]}"""
    }

    private fun publish(vararg releases: String) {
        pages = listOf(releases.joinToString(",", "[", "]"))
    }

    private fun updater(env: FakeUpdaterEnv = FakeUpdaterEnv()) = SelfUpdater(env, github(), ApkFetcher(client, { allowed }, {}))

    private inner class FakeUpdaterEnv : UpdaterEnv {
        override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override val apkFolder = File(temp.root, "apk/update")
        override val currentVersion = "3.0.0"
        override val pinnedSigner = ""
        override fun self() = this@UpdaterTest.self
        override var passedOver: String? = null
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
    fun theNewestReleaseAboveThisVersionIsOfferedWithItsChecksumAndSize() = runBlocking<Unit> {
        val bytes = apk()
        publish(
            release("pocketide-v3.2.0"),
            release("pocketide-v3.1.0-beta.1", apk()),
            release("pocketide-v3.1.0", bytes),
            release("pocketide-v2.9.0", apk()),
            release("cloudsaver-v9.0.0", apk()),
        )

        val found = github().newest(SemVer(3, 0, 0))

        // 3.2.0 publishes no checksums, so it is passed over.
        assertEquals("3.1.0", found?.version)
        assertEquals("pocketide-v3.1.0", found?.tag)
        assertEquals(sha256(bytes), found?.sha256)
        assertEquals(bytes.size.toLong(), found?.apkBytes)
        assertEquals("Notes for pocketide-v3.1.0 & more", found?.notes)
        assertTrue(found!!.apkUrl.endsWith("/download/pocketide-v3.1.0/PocketIDE-3.1.0-arm64-v8a-release.apk"))
        assertNull(github().newest(SemVer(3, 1, 0)))
    }

    @Test
    fun aReleaseBehindManyOtherAppsReleasesIsStillFound() = runBlocking<Unit> {
        // A shared repository: a full page of CloudSaver releases comes before PocketIDE's.
        val others = (1..100).map { release("cloudsaver-v4.0.$it", apk()) }
        pages = listOf(others.joinToString(",", "[", "]"), listOf(release("pocketide-v3.1.0", apk())).joinToString(",", "[", "]"))

        assertEquals("3.1.0", github().newest(SemVer(3, 0, 0))?.version)
    }

    @Test
    fun draftsAndPreReleasesAreNeverOffered() = runBlocking<Unit> {
        publish(release("pocketide-v3.2.0", apk(), draft = true), release("pocketide-v3.1.0", apk(), prerelease = true))

        assertNull(github().newest(SemVer(3, 0, 0)))
    }

    @Test
    fun checksumsThatDoNotNameOneApkAreNotEnough() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk(), sums = "${"ab".repeat(32)}  ../evil.apk\n"))

        assertNull(github().newest(SemVer(3, 0, 0)))
    }

    /** The day GitHub retires the REST version this build knows, the updater must still find the build that knows the next. */
    @Test
    fun theReleaseListIsAskedForWithoutAnApiVersion() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk()))

        github().newest(SemVer(3, 0, 0))

        val request = server.takeRequest()
        assertEquals("/repos/$repo/releases", request.url.encodedPath)
        assertNull(request.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun aReleasePageThatMovedSaysWhatToDoAndIsNotRetried() = runBlocking<Unit> {
        listStatus = 404
        val updater = updater()

        updater.check()

        val failed = updater.state.value as UpdateState.Failed
        assertEquals("PocketIDE's release page moved. Install the newest PocketIDE APK once by hand.", failed.why)
        assertFalse(failed.retry)
    }

    @Test
    fun githubRefusingSaysSoPlainly() {
        listStatus = 429

        val failure = assertThrows(IOException::class.java) { runBlocking { github().newest(SemVer(3, 0, 0)) } }

        assertEquals("GitHub is limiting requests. Try again in an hour.", failure.message)
    }

    @Test
    fun anUpdateSignedWithThisAppsKeyBecomesReady() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk()))
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
    fun aReleaseThatIsNotANewerBuildIsPassedOverInsteadOfDownloadedAgain() = runBlocking<Unit> {
        // A release whose version was raised but whose versionCode was not.
        publish(release("pocketide-v3.1.0", apk(code = 300)))
        val env = FakeUpdaterEnv()
        val updater = updater(env)

        updater.check()
        updater.download()

        assertEquals(UpdateState.UpToDate, updater.state.value)
        assertEquals("pocketide-v3.1.0", env.passedOver)
        assertFalse(File(temp.root, "apk/update/pocketide-3.1.0.apk").exists())
        assertEquals(1, apkDownloads)

        // The next daily check, in a new process, neither offers nor downloads it again.
        val later = updater(env)
        later.check()
        later.download()
        assertEquals(UpdateState.UpToDate, later.state.value)
        assertEquals("the APK is not downloaded again", 1, apkDownloads)

        // A later release that is a newer build is offered as usual.
        publish(release("pocketide-v3.1.1", apk(code = 30101)), release("pocketide-v3.1.0", apk(code = 300)))
        later.check()
        assertEquals("3.1.1", (later.state.value as UpdateState.Available).release.version)
    }

    @Test
    fun aFileThatDoesNotMatchItsPublishedChecksumIsNotKept() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk(), sums = "${"00".repeat(32)}  PocketIDE-3.1.0-arm64-v8a-release.apk\n"))
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
    fun onMobileDataTheOwnerIsAskedWithTheUpdatesSize() = runBlocking<Unit> {
        publish(release("pocketide-v3.1.0", apk()))
        allowed = Decision.no(MeteredDataBudget.WAITS_FOR_WIFI)
        val updater = updater()
        updater.check()

        val ask = assertThrows(NeedsMobileData::class.java) { runBlocking { updater.download() } }

        assertEquals("app update", ask.kind)
        assertEquals(apk().size.toLong(), ask.bytes)
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

    @Test
    fun onlyThisAppAtTheSameOrAnOlderBuildIsNotNewer() {
        assertTrue(UpdateRules.notNewer(ApkFacts("com.pocketide", 300, null, setOf("aa")), self))
        assertTrue(UpdateRules.notNewer(ApkFacts("com.pocketide", 260, null, setOf("bb")), self))
        assertFalse(UpdateRules.notNewer(ApkFacts("com.pocketide", 301, null, setOf("aa")), self))
        assertFalse(UpdateRules.notNewer(ApkFacts("com.other", 1, null, setOf("aa")), self))
        assertFalse(UpdateRules.notNewer(null, self))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
