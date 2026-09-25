package com.pocketide.agents

import com.pocketide.linux.TarBuilder
import com.pocketide.model.AgentCandidate
import com.pocketide.model.Decision
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CatalogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val vsx = OpenVsxFixture()
    private val agyFiles = mutableMapOf<String, ByteArray>()
    private var agyManifest = ""
    private lateinit var env: FakeAgentsEnv
    private lateinit var catalog: OpenVsxCatalog

    private val claude = FakeExtension("Anthropic", "claude-code")
    private val codex = FakeExtension("openai", "chatgpt")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/agy/manifest.json" -> MockResponse.Builder().body(agyManifest).build()
                    path.startsWith("/agy/archives/") -> agyFiles[path]?.let { MockResponse.Builder().body(Buffer().write(it)).build() }
                        ?: MockResponse.Builder().code(404).build()
                    else -> vsx.dispatcher.dispatch(request)
                }
            }
        }
        server.start()
        vsx.base = server.url("/")
        env = FakeAgentsEnv(temp.root)
        catalog = newCatalog()
        vsx.add(claude)
        vsx.add(codex)
    }

    @After
    fun tearDown() {
        env.scope.cancel()
        server.close()
    }

    private fun newCatalog(): OpenVsxCatalog {
        val registry = OpenVsx(env.http, vsx.base)
        val download = VerifiedDownload(env.downloads, env::allowDownload, env::recordDownload)
        return OpenVsxCatalog(
            env = env,
            vsx = registry,
            extensions = ExtensionInstaller(env, registry, download),
            agy = AgyInstaller(env, download, manifestUrl = server.url("/agy/manifest.json"), archiveBase = server.url("/agy/archives/")),
            doctor = AgentDoctor(env),
            discovery = Discovery(registry, env.clock),
            store = AgentStore(File(temp.root, "agents/agents.json")),
            packages = File(temp.root, "agents/packages"),
        )
    }

    private fun claudeVersion(version: String, engine: String = "^1.94.0", preRelease: Boolean = false, command: Boolean = true, wrongChecksum: Boolean = false, wrongSignature: Boolean = false, verified: Boolean = true) =
        FakeVersion(
            version = version,
            engine = engine,
            preRelease = preRelease,
            verified = verified,
            wrongChecksum = wrongChecksum,
            wrongSignature = wrongSignature,
            packageJson = OpenVsxFixture.packageJson(
                "Anthropic",
                "claude-code",
                version,
                engine,
                commands = if (command) listOf("claude-vscode.primaryEditor.open") else listOf("claude-vscode.editor.open"),
            ),
        )

    private fun installedClaude(): String? = runBlocking { AgentDoctor(env).installed("claude", "anthropic.claude-code")?.version }

    private fun packages(agentId: String) = File(temp.root, "agents/packages/$agentId").list().orEmpty().sorted()

    @Test
    fun installsTheNewestReleaseWhoseEngineRangeFitsTheComputer() = runBlocking<Unit> {
        claude.versions += claudeVersion("2.1.200")
        claude.versions += claudeVersion("2.1.281")
        claude.versions += claudeVersion("2.2.0", preRelease = true)
        claude.versions += claudeVersion("2.3.0", engine = "^1.200.0")

        catalog.ensureInstalled("claude")

        assertEquals("2.1.281", installedClaude())
        assertEquals("2.1.281", catalog.find("claude")?.version)
        assertEquals("2.1.281", catalog.installed.value.first { it.id == "claude" }.version)
        assertEquals(listOf("2.1.281.vsix"), packages("claude"))
        assertEquals(listOf("claude"), env.configured)
        val install = env.commands.first { "--install-extension" in it }
        assertEquals(
            listOf(RoomPaths.CODE_SERVER, "--user-data-dir", "/root/.local/share/code-server", "--extensions-dir", "/root/.local/share/code-server/extensions"),
            install.take(5),
        )
        assertTrue(env.dirs.roomTmp("claude").list().orEmpty().isEmpty())
    }

    @Test
    fun codexNeverGetsAPreRelease() = runBlocking<Unit> {
        codex.versions += FakeVersion("26.908.40401", engine = "^1.96.2", packageJson = OpenVsxFixture.packageJson("openai", "chatgpt", "26.908.40401", "^1.96.2", listOf("chatgpt.openSidebar")))
        codex.versions += FakeVersion("26.5908.31748", engine = "^1.96.2", preRelease = true, packageJson = OpenVsxFixture.packageJson("openai", "chatgpt", "26.5908.31748", "^1.96.2", listOf("chatgpt.openSidebar")))

        catalog.ensureInstalled("codex")

        assertEquals("26.908.40401", catalog.find("codex")?.version)
    }

    @Test
    fun anUpdateThatLosesThePinnedCommandIsRolledBack() = runBlocking<Unit> {
        claude.versions += claudeVersion("2.1.200")
        catalog.ensureInstalled("claude")
        claude.versions += claudeVersion("2.1.281", command = false)

        val failure = assertThrows(PackageRejected::class.java) { runBlocking { catalog.ensureInstalled("claude") } }

        assertTrue(failure.message!!, failure.message!!.contains("2.1.200 is back in place"))
        assertEquals("2.1.200", installedClaude())
        assertEquals("2.1.200", catalog.find("claude")?.version)
        assertEquals(listOf("2.1.200.vsix"), packages("claude"))
    }

    @Test
    fun anUpdateKeepsThePackageBeforeIt() = runBlocking<Unit> {
        claude.versions += claudeVersion("2.1.100")
        catalog.ensureInstalled("claude")
        claude.versions += claudeVersion("2.1.200")
        catalog.ensureInstalled("claude")
        claude.versions += claudeVersion("2.1.281")
        catalog.ensureInstalled("claude")

        assertEquals("2.1.281", installedClaude())
        assertEquals(listOf("2.1.200.vsix", "2.1.281.vsix"), packages("claude"))
    }

    @Test
    fun aPackageThatDoesNotMatchItsChecksumIsNeverInstalled() {
        claude.versions += claudeVersion("2.1.281", wrongChecksum = true)

        assertThrows(PackageRejected::class.java) { runBlocking { catalog.ensureInstalled("claude") } }

        assertNull(installedClaude())
        assertTrue(packages("claude").isEmpty())
        assertTrue(env.commands.none { "--install-extension" in it })
    }

    @Test
    fun aPackageSignedByAnotherKeyIsNeverInstalled() {
        claude.versions += claudeVersion("2.1.281", wrongSignature = true)

        val failure = assertThrows(PackageRejected::class.java) { runBlocking { catalog.ensureInstalled("claude") } }

        assertTrue(failure.message!!.contains("signature"))
        assertNull(installedClaude())
    }

    @Test
    fun aVersionFromAnUnverifiedPublisherIsNeverChosen() {
        claude.versions += claudeVersion("2.1.281", verified = false)

        assertThrows(PackageRejected::class.java) { runBlocking { catalog.ensureInstalled("claude") } }

        assertNull(installedClaude())
    }

    @Test
    fun theDataRulesCanMakeTheDownloadWait() {
        claude.versions += claudeVersion("2.1.281")
        env.allowed = Decision.no("Waiting for Wi-Fi.")

        val failure = assertThrows(DownloadWaits::class.java) { runBlocking { catalog.ensureInstalled("claude") } }

        assertEquals("Waiting for Wi-Fi.", failure.message)
        assertNull(installedClaude())
    }

    @Test
    fun aRoomInUseIsUpdatedLater() = runBlocking<Unit> {
        claude.versions += claudeVersion("2.1.200")
        catalog.ensureInstalled("claude")
        claude.versions += claudeVersion("2.1.281")
        env.inUse += "claude"

        catalog.ensureInstalled("claude")
        assertEquals("2.1.200", installedClaude())

        env.inUse.clear()
        catalog.ensureInstalled("claude")
        assertEquals("2.1.281", installedClaude())
    }

    @Test
    fun anInstalledAgentStaysUsableWhenItsUpdateCannotBeFetched() = runBlocking<Unit> {
        claude.versions += claudeVersion("2.1.200")
        catalog.ensureInstalled("claude")
        claude.versions += claudeVersion("2.1.281")
        env.allowed = Decision.no("Waiting for Wi-Fi.")

        catalog.ensureInstalled("claude")

        assertEquals("2.1.200", installedClaude())
        assertThrows(DownloadWaits::class.java) { runBlocking { catalog.updateAll() } }
    }

    @Test
    fun nothingIsInstalledBeforeTheComputerIsReady() {
        claude.versions += claudeVersion("2.1.281")
        env.computer = "Set up the computer first."

        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { catalog.ensureInstalled("claude") } }

        assertEquals("Set up the computer first.", failure.message)
    }

    @Test
    fun agyComesFromItsManifestAndABadUpdateIsRolledBack() = runBlocking<Unit> {
        publishAgy("1.2.9", "good-1")
        catalog.ensureInstalled("antigravity")

        val binary = File(env.dirs.roomHome("antigravity"), ".gemini/bin/agy")
        assertEquals("good-1", binary.readText())
        assertTrue(binary.canExecute())
        assertEquals("1.2.9", catalog.find("antigravity")?.version)

        publishAgy("1.2.10", "broken")
        val failure = assertThrows(PackageRejected::class.java) { runBlocking { catalog.ensureInstalled("antigravity") } }

        assertTrue(failure.message!!.contains("Version 1.2.9 is back in place"))
        assertEquals("good-1", binary.readText())
        assertFalse(File(binary.parentFile, "agy.previous").exists())
        assertEquals("1.2.9", catalog.find("antigravity")?.version)
        assertEquals(listOf("agy-1.2.9.tar.gz"), packages("antigravity"))
    }

    @Test
    fun agyIsUpdatedWhenTheManifestMovesOn() = runBlocking<Unit> {
        publishAgy("1.2.9", "good-1")
        catalog.ensureInstalled("antigravity")
        publishAgy("1.2.10", "good-2")

        catalog.ensureInstalled("antigravity")

        assertEquals("good-2", File(env.dirs.roomHome("antigravity"), ".gemini/bin/agy").readText())
        assertEquals(listOf("agy-1.2.10.tar.gz", "agy-1.2.9.tar.gz"), packages("antigravity"))
    }

    @Test
    fun agyFromAnotherServerOrWithTheWrongDigestIsRefused() {
        publishAgy("1.2.10", "good-1", url = "https://example.com/agy.tar.gz")
        assertThrows(PackageRejected::class.java) { runBlocking { catalog.ensureInstalled("antigravity") } }

        publishAgy("1.2.10", "good-1", wrongDigest = true)
        assertThrows(PackageRejected::class.java) { runBlocking { catalog.ensureInstalled("antigravity") } }

        assertFalse(File(env.dirs.roomHome("antigravity"), ".gemini/bin/agy").exists())
    }

    @Test
    fun theAntigravityExtensionIsNeverInstalled() {
        val installer = ExtensionInstaller(env, OpenVsx(env.http, vsx.base), VerifiedDownload(env.downloads, env::allowDownload, env::recordDownload))

        assertThrows(PackageRejected::class.java) {
            runBlocking { installer.newest("google.google-antigravity", "Google", SemVer(1, 138, 0)) }
        }
    }

    @Test
    fun addingAnAgentInstallsItInItsOwnRoomAndRemovingDeletesTheRoom() = runBlocking<Unit> {
        val cline = community()
        catalog.discover()
        val candidate = catalog.candidates.value.single()

        val report = catalog.add(candidate)

        assertTrue(report.note, report.ok)
        val agent = catalog.find(cline.id)
        assertNotNull(agent)
        assertFalse(agent!!.official)
        assertTrue(agent.verifiedPublisher)
        assertEquals("1.0.0", agent.version)
        assertEquals("cline.chat.focus", agent.openCommand)
        assertTrue(catalog.candidates.value.isEmpty())
        assertTrue(File(env.extensionsFolder(cline.id), "${cline.id}-1.0.0-linux-arm64/package.json").isFile)

        catalog.remove(cline.id)

        assertEquals(listOf(cline.id), env.savedFirst)
        assertEquals(listOf(cline.id), env.deleted)
        assertNull(catalog.find(cline.id))
        assertTrue(packages(cline.id).isEmpty())
    }

    @Test
    fun anAgentWithWorkOnlyOnThisPhoneIsNotRemoved() = runBlocking<Unit> {
        val cline = community()
        catalog.discover()
        catalog.add(catalog.candidates.value.single())
        val kept = packages(cline.id)
        env.unsaved = listOf("\"Login fix\" has work that is not on GitHub yet")

        val refused = assertThrows(IllegalStateException::class.java) { runBlocking { catalog.remove(cline.id) } }

        assertTrue(refused.message, refused.message!!.contains("was not removed: \"Login fix\" has work that is not on GitHub yet"))
        assertTrue("the room was deleted", env.deleted.isEmpty())
        assertNotNull(catalog.find(cline.id))
        assertEquals(kept, packages(cline.id))

        env.unsaved = emptyList()
        catalog.remove(cline.id)
        assertEquals(listOf(cline.id), env.deleted)
    }

    @Test
    fun anAgentThatFailsTheDoctorIsNotAdded() = runBlocking<Unit> {
        val cline = community()
        catalog.discover()
        env.memory = "This phone has less than the 4 GB of memory an agent needs."

        val report = catalog.add(catalog.candidates.value.single())

        assertFalse(report.ok)
        assertTrue(report.note!!.contains("was not added"))
        assertNull(catalog.find(cline.id))
        assertEquals(listOf(cline.id), env.deleted)
    }

    @Test
    fun onlyOfficialAgentsHidesTheOthersAndRefusesToAddOrInstallThem() = runBlocking<Unit> {
        val cline = community()
        catalog.discover()
        catalog.add(catalog.candidates.value.single())
        assertEquals(4, catalog.installed.value.size)

        env.onlyOfficial.value = true

        assertEquals(listOf("claude", "codex", "antigravity"), catalog.installed.value.map { it.id })
        assertTrue(catalog.candidates.value.isEmpty())
        assertThrows(IllegalStateException::class.java) { runBlocking { catalog.ensureInstalled(cline.id) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { catalog.add(candidateFor(cline)) } }
    }

    @Test
    fun discoveryAnnouncesEachNewAgentOnce() = runBlocking<Unit> {
        community()

        catalog.discover()
        catalog.discover()

        assertEquals(1, env.announced.size)
        assertEquals("cline.cline", env.announced.single().single().extensionId)
        assertNotNull(catalog.facts("cline.cline"))
    }

    @Test
    fun theOfficialAgentsCannotBeRemoved() {
        assertThrows(IllegalStateException::class.java) { runBlocking { catalog.remove("claude") } }
    }

    @Test
    fun whatWasAddedSurvivesARestart() = runBlocking<Unit> {
        val cline = community()
        catalog.discover()
        catalog.add(catalog.candidates.value.single())

        val again = newCatalog()

        assertEquals("1.0.0", again.find(cline.id)?.version)
        assertNotNull(again.facts(cline.id))
    }

    @Test
    fun updateAllTriesEveryAgentAndReportsTheFirstProblem() = runBlocking<Unit> {
        claude.versions += claudeVersion("2.1.281")
        publishAgy("1.2.10", "good-1")

        val failure = assertThrows(IOException::class.java) { runBlocking { catalog.updateAll() } }

        assertTrue(failure.message!!, failure.message!!.contains("openai.chatgpt"))
        assertEquals("2.1.281", installedClaude())
        assertEquals("1.2.10", catalog.find("antigravity")?.version)
    }

    private fun community(): FakeExtension {
        val cline = FakeExtension("cline", "cline", categories = listOf("AI", "Chat"), license = "Apache-2.0")
        cline.versions += FakeVersion("1.0.0", timestamp = "2026-01-01T00:00:00Z", packageJson = OpenVsxFixture.packageJson("cline", "cline", "1.0.0"))
        return vsx.add(cline)
    }

    private fun candidateFor(extension: FakeExtension) = AgentCandidate(
        extensionId = extension.id,
        displayName = extension.name,
        publisher = extension.namespace,
        version = "1.0.0",
        downloads = extension.downloads,
        firstPublishedAt = 0,
        categories = extension.categories,
        description = "",
        foundAt = 0,
    )

    private fun publishAgy(version: String, program: String, url: String? = null, wrongDigest: Boolean = false) {
        val archive = TarBuilder().file("antigravity", program).gz()
        val path = "/agy/archives/antigravity-cli/$version/cli_linux_arm64.tar.gz"
        agyFiles[path] = archive
        agyManifest = buildJsonObject {
            put("version", version)
            put("url", url ?: server.url(path).toString())
            put("sha512", if (wrongDigest) OpenVsxFixture.sha512(byteArrayOf(1)) else OpenVsxFixture.sha512(archive))
        }.toString()
    }
}
