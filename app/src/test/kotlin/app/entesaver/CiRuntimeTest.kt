package app.entesaver

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workflows must keep running on GitHub's runners without anyone
 * watching them.
 *
 * GitHub removes the Node 20 runtime from its runners on 23 September 2026.
 * Every JavaScript action pinned to a major that still says `using: node20`
 * stops loading that day, and a workflow that cannot load its checkout step
 * cannot even begin - CI goes red on a repository nobody changed. The
 * majors below are the first of each action to declare `node24`, read from
 * each action.yml at its tag; anything older is a pin that expires.
 */
class CiRuntimeTest {

    /** Unit tests run from app/; the workflows live beside it, one level up. */
    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            if (File(dir, ".github/workflows").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    private val workflows = File(repoRoot() ?: File("."), ".github/workflows")

    /** Action -> the lowest major whose action.yml runs on Node 24. */
    private val nodeTwentyFour = mapOf(
        "actions/checkout" to 5,
        "actions/setup-java" to 5,
        "actions/upload-artifact" to 6,
        "actions/download-artifact" to 7,
        "gradle/actions/setup-gradle" to 5,
        "reactivecircus/android-emulator-runner" to 2
    )

    @Test
    fun `the Gradle the wrapper downloads is the Gradle that was tested`() {
        // Every CI job starts on a fresh runner and downloads Gradle from the
        // URL below; without a checksum the wrapper runs whatever those bytes
        // turn out to be, and the release APK - whose hash and signing
        // fingerprint the release notes ask people to verify - is built by a
        // toolchain nobody verified. The value is the one services.gradle.org
        // publishes beside the distribution, confirmed against a download.
        val root = repoRoot()
        assertTrue("repository root not found from app/", root != null)
        val props = File(root, "gradle/wrapper/gradle-wrapper.properties").readLines()
        val url = props.firstOrNull { it.startsWith("distributionUrl=") }
        assertTrue("the wrapper must name its distribution", url != null)
        val sum = props.firstOrNull { it.startsWith("distributionSha256Sum=") }
            ?.substringAfter("=")
        assertTrue(
            "the wrapper must pin the SHA-256 of $url",
            sum != null && Regex("[0-9a-f]{64}").matches(sum)
        )
    }

    @Test
    fun `no action is pinned to a major that loses its runtime`() {
        assertTrue("the workflows directory must be found from app/", workflows.isDirectory)
        val uses = Regex("""uses:\s*([A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-/]+)@v(\d+)""")
        val expiring = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (file in workflows.listFiles { f -> f.extension == "yml" || f.extension == "yaml" }.orEmpty()) {
            file.readLines().forEachIndexed { idx, line ->
                val m = uses.find(line) ?: return@forEachIndexed
                val (action, major) = m.destructured
                val floor = nodeTwentyFour[action]
                if (floor == null) {
                    unknown += "${file.name}:${idx + 1} $action"
                } else if (major.toInt() < floor) {
                    expiring += "${file.name}:${idx + 1} $action@v$major (needs v$floor)"
                }
            }
        }
        assertTrue(
            "these actions are pinned below the first Node 24 major and stop " +
                "loading on 2026-09-23: $expiring",
            expiring.isEmpty()
        )
        assertTrue(
            "new actions must be added to the Node 24 table with the major " +
                "whose action.yml says `using: node24`: $unknown",
            unknown.isEmpty()
        )
    }

    @Test
    fun `the emulator harness hides system error dialogs before the suite`() {
        // A loaded runner can hang the emulator's launcher, and the "isn't
        // responding" dialog Android then shows takes window focus: taps and
        // back presses stop reaching the app under test and a leg goes red
        // with Ente Saver untouched (run 373 lost nine API 35 tests to the
        // Pixel Launcher's dialog). The harness turns those dialogs off
        // before the suite starts; the crash buffer and the process checks
        // it keeps still report every failure that is the app's.
        val root = repoRoot()
        assertTrue("repository root not found from app/", root != null)
        val script = File(root, ".github/scripts/emulator-e2e.sh").readText()
        val hide = script.indexOf("settings put global hide_error_dialogs 1")
        val suite = script.indexOf("connectedDebugAndroidTest")
        assertTrue("the emulator harness must hide the system's error dialogs", hide >= 0)
        assertTrue("and must do so before the instrumented suite starts", suite > hide)
    }

    @Test
    fun `a release is tagged at the commit its APK was built from`() {
        // Without --target GitHub creates a missing tag at the branch head at
        // the moment of the call. The release job waits for every emulator
        // leg, so a push that lands meanwhile got the tag and the source
        // archive while the APK and its hash came from the commit before.
        val root = repoRoot()
        assertTrue("repository root not found from app/", root != null)
        val workflow = File(root, ".github/workflows/build.yml").readText()
        val create = workflow.substringAfter("gh release create", "").substringBefore("--repo")
        assertTrue("the workflow must publish its release with gh release create", create.isNotEmpty())
        assertTrue(
            "the release tag must name the commit this run built",
            create.contains("--target \"\$GITHUB_SHA\"")
        )
    }

    @Test
    fun `the release APK walk finishes setup and reaches every tab`() {
        // A fresh install opens on setup, which has no tab bar. The walk used
        // to tap where the tabs would be, landed on setup every time and
        // passed, so no screen past launch was ever drawn from R8 code.
        val root = repoRoot()
        assertTrue("repository root not found from app/", root != null)
        val script = File(root, ".github/scripts/emulator-e2e.sh").readText()
        // The install the walk runs on, in its own group: the update checks
        // before it install the same file and then remove it again.
        val group = script.indexOf("::group::Install the signed release APK and launch it")
        val install = script.indexOf("adb install -r -g EnteSaver-release.apk", group)
        val walk = script.indexOf("python3 .github/scripts/release-tab-walk.py")
        val promises = script.indexOf("android.permission.INTERNET")
        assertTrue("the release install must have its own group", group >= 0)
        assertTrue("the release APK must be installed with its permissions granted", install > group)
        assertTrue("the release walk must run after the install", walk > install)
        assertTrue("and before the installed package's promises are read", promises > walk)
        assertFalse("tabs are found by their labels, not by screen fraction", script.contains("TAB_Y"))

        // Every label the walk looks for has to be one the app still shows:
        // a renamed button would leave it swiping at setup until it fails.
        val walker = File(root, ".github/scripts/release-tab-walk.py").readText()
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        for (list in listOf("FORWARD", "TABS")) {
            val names = Regex("""^$list = \[(.*)]$""", RegexOption.MULTILINE).find(walker)
                ?.groupValues?.get(1)
                ?.let { Regex(""""(\w+)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
            assertTrue("the walk must list its $list labels", !names.isNullOrEmpty())
            for (name in names!!) {
                assertTrue("strings.xml has no \"$name\" for the walk", strings.contains("name=\"$name\""))
            }
        }
        assertTrue(
            "the walk must fail when the tabs never appear",
            walker.contains("never reached its tabs") && walker.contains("sys.exit(1)")
        )
        assertTrue("and when a tab does not open", walker.contains("tab never opened"))
    }
}
