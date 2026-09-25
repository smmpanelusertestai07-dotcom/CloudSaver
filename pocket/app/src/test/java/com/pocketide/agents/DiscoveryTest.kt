package com.pocketide.agents

import com.pocketide.agents.Rule as Check
import com.pocketide.core.Clock
import com.pocketide.model.Decision
import com.pocketide.sync.MeteredDataBudget
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DiscoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val fixture = OpenVsxFixture()
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val now = 1_800_000_000_000L
    private lateinit var discovery: Discovery

    @Before
    fun setUp() {
        server.dispatcher = fixture.dispatcher
        server.start()
        fixture.base = server.url("/")
        discovery = Discovery(OpenVsx(client, fixture.base), Clock { now })
    }

    @After
    fun tearDown() = server.close()

    private fun agent(namespace: String, name: String, published: String = "2026-01-01T00:00:00Z", packageJson: String? = null, verified: Boolean = true, downloads: Long = 200_000, license: String? = "MIT", categories: List<String> = listOf("AI")) =
        fixture.add(
            FakeExtension(namespace, name, verified = verified, downloads = downloads, license = license, categories = categories).apply {
                versions += FakeVersion("1.0.0", timestamp = published, packageJson = packageJson ?: OpenVsxFixture.packageJson(namespace, name, "1.0.0"))
            },
        )

    private fun verdicts(): Map<String, Verdict> = runBlocking { discovery.run(emptySet()) }.associateBy {
        when (it) {
            is Verdict.Offered -> it.found.candidate.extensionId
            is Verdict.Skipped -> it.id
        }
    }

    private fun rule(verdict: Verdict?) = (verdict as? Verdict.Skipped)?.rule

    @Test
    fun anAgentThatPassesEveryCheckIsOfferedWithItsFacts() {
        agent("cline", "cline", license = "Apache-2.0")

        val offered = verdicts()["cline.cline"] as Verdict.Offered

        val facts = offered.found.facts
        assertEquals("cline.cline", facts.identifier)
        assertEquals("linux-arm64", facts.target)
        assertEquals("Apache-2.0", facts.license)
        assertEquals("https://github.com/cline/cline", facts.repository)
        assertEquals("cline.chat.focus", facts.openCommand)
        assertEquals(1_767_225_600_000L, offered.found.candidate.firstPublishedAt)
    }

    @Test
    fun eachRuleKeepsItsAgentOut() {
        agent("anthropic", "claude-code")
        agent("someone", "unverified", verified = false)
        agent("someone", "unpopular", downloads = 1_000)
        agent("anthrop1c", "helper")
        agent("ms-ai", "helper")
        agent("someone", "licensed", license = "Microsoft Software License")
        agent("someone", "brandnew", published = "2027-01-10T00:00:00Z")
        agent("someone", "noscreen", packageJson = """{"publisher":"someone","name":"noscreen","version":"1.0.0","contributes":{}}""")

        val verdicts = verdicts()

        assertEquals(Check.ALREADY_KNOWN, rule(verdicts["anthropic.claude-code"]))
        assertEquals(Check.NOT_VERIFIED, rule(verdicts["someone.unverified"]))
        assertEquals(Check.TOO_FEW_DOWNLOADS, rule(verdicts["someone.unpopular"]))
        assertEquals(Check.LOOKALIKE, rule(verdicts["anthrop1c.helper"]))
        assertEquals(Check.MICROSOFT, rule(verdicts["ms-ai.helper"]))
        assertEquals(Check.MICROSOFT, rule(verdicts["someone.licensed"]))
        assertEquals(Check.TOO_NEW, rule(verdicts["someone.brandnew"]))
        assertEquals(Check.NO_AGENT_SCREEN, rule(verdicts["someone.noscreen"]))
    }

    @Test
    fun aBusyRegistryEndsTheSearch() {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) = MockResponse.Builder().code(429).build()
        }

        assertThrows(RegistryBusy::class.java) { runBlocking { discovery.run(emptySet()) } }
    }
}

class VerifiedDownloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val payload = Random(11).nextBytes(400_000)
    private val keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private var allowed = Decision.YES
    private var recorded = 0L
    private val download = VerifiedDownload(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), { allowed }, { recorded += it })

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun fetch(expected: Expected): File {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
        return runBlocking { download.fetch(server.url("/pkg.vsix"), File(temp.root, "pkg.vsix"), expected) }
    }

    private val signature get() = keys.public.encoded.copyOfRange(12, 44) to OpenVsxFixture.sign(payload, keys)

    @Test
    fun aMatchingPackageIsKept() {
        val file = fetch(Expected(sha256 = OpenVsxFixture.sha256(payload), sha512 = OpenVsxFixture.sha512(payload), signature = signature))

        assertTrue(file.readBytes().contentEquals(payload))
        assertEquals(payload.size.toLong(), recorded)
    }

    @Test
    fun aMismatchLeavesNothingBehind() {
        assertThrows(PackageRejected::class.java) { fetch(Expected(sha256 = OpenVsxFixture.sha256(byteArrayOf(0)))) }
        assertThrows(PackageRejected::class.java) { fetch(Expected(sha512 = OpenVsxFixture.sha512(byteArrayOf(0)))) }
        val otherKeys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertThrows(PackageRejected::class.java) {
            fetch(Expected(sha256 = OpenVsxFixture.sha256(payload), signature = otherKeys.public.encoded.copyOfRange(12, 44) to OpenVsxFixture.sign(payload, otherKeys).also { it[0] = (it[0] + 1).toByte() }))
        }
        assertThrows(PackageRejected::class.java) { fetch(Expected(sha256 = OpenVsxFixture.sha256(payload), bytes = 10)) }

        assertTrue(temp.root.list().orEmpty().isEmpty())
    }

    @Test
    fun theDataRulesAreAskedFirst() {
        allowed = Decision.no("Waiting for Wi-Fi.")

        assertThrows(DownloadWaits::class.java) { fetch(Expected(sha256 = OpenVsxFixture.sha256(payload))) }
        assertEquals(0L, recorded)
    }

    @Test
    fun aDownloadThatWaitsForWiFiAsksTheOwnerWithItsSize() {
        allowed = Decision.no(MeteredDataBudget.WAITS_FOR_WIFI)

        val waits = assertThrows(DownloadWaits::class.java) { fetch(Expected(sha256 = OpenVsxFixture.sha256(payload))) }

        val question = mobileDataQuestion(waits)
        assertEquals(VerifiedDownload.DATA_KIND, question?.kind)
        assertEquals(payload.size.toLong(), question?.bytes)
        allowed = Decision.no("Today's mobile data limit is used up.")
        assertEquals(null, mobileDataQuestion(assertThrows(DownloadWaits::class.java) { fetch(Expected(sha256 = OpenVsxFixture.sha256(payload))) }))
    }

    private fun get(expected: Expected): File = runBlocking { download.fetch(server.url("/pkg.vsix"), File(temp.root, "pkg.vsix"), expected) }

    private val complete get() = Expected(sha256 = OpenVsxFixture.sha256(payload), sha512 = OpenVsxFixture.sha512(payload), signature = signature)

    @Test
    fun aDownloadThatIsCutOffContinuesWhereItStopped() {
        val half = payload.size / 2
        // The connection drops halfway: what arrived stays for the next try.
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(payload, 0, half))
                .setHeader("Content-Length", payload.size)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build(),
        )
        assertThrows(IOException::class.java) { get(complete) }
        val kept = File(temp.root, "pkg.vsix.part")
        assertEquals(half.toLong(), kept.length())

        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes $half-${payload.size - 1}/${payload.size}")
                .body(Buffer().write(payload, half, payload.size - half))
                .build(),
        )
        val file = get(complete)

        server.takeRequest()
        assertEquals("bytes=$half-", server.takeRequest().headers["Range"])
        assertTrue(file.readBytes().contentEquals(payload))
        assertFalse(kept.exists())
        assertEquals("each byte is counted once", payload.size.toLong(), recorded)
    }

    @Test
    fun keptBytesThatDoNotBelongToThePackageAreNotKept() {
        File(temp.root, "pkg.vsix.part").writeBytes(ByteArray(1000) { 7 })
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes 1000-${payload.size - 1}/${payload.size}")
                .body(Buffer().write(payload, 1000, payload.size - 1000))
                .build(),
        )

        assertThrows(PackageRejected::class.java) { get(complete) }
        assertTrue(temp.root.list().orEmpty().isEmpty())
    }

    @Test
    fun aServerThatSendsTheWholeFileAgainStartsItOver() {
        File(temp.root, "pkg.vsix.part").writeBytes(payload.copyOf(1000))
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())

        assertTrue(get(complete).readBytes().contentEquals(payload))
    }

    @Test
    fun aPackageAlreadyInPlaceIsCheckedAndUsed() {
        File(temp.root, "pkg.vsix").writeBytes(payload)

        assertTrue(get(complete).readBytes().contentEquals(payload))
        assertEquals("nothing downloaded", 0, server.requestCount)
    }
}
