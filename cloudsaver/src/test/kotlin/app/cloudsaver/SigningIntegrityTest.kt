package app.cloudsaver

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The release pipeline may not publish the key it signs with, and may not
 * publish a build nobody can install.
 *
 * Both of these were true at once, for eleven releases. The workflow minted a
 * fresh RSA key on every run because no signing secret was set, signed with
 * it, and then uploaded `release.jks` AND `keystore-password.txt` as a
 * workflow artifact - on a public repository, where reading an artifact needs
 * only read access. So the private key and its password for every published
 * release were downloadable by anyone, and an APK signed with them installs as
 * an update over a real user's app, inheriting the media and usage-access
 * permissions they granted. `TamperCheck` cannot see it: the expected
 * certificate is whatever the builder passed in at build time.
 *
 * The same throwaway key meant three consecutive releases carried three
 * different certificates, so no user could ever update in place - every
 * version was an uninstall, and with allowBackup=false that is a wipe.
 *
 * These are properties of one file, so they are checked here in a millisecond
 * rather than noticed a year later.
 */
class SigningIntegrityTest {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            if (File(dir, ".github/workflows/build.yml").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun workflow(): String =
        File(repoRoot(), ".github/workflows/build.yml").readText()

    /** Lines of an `upload-artifact` step's `path:` block. */
    private fun uploadedPaths(text: String): List<String> {
        val out = mutableListOf<String>()
        val lines = text.lines()
        for ((i, line) in lines.withIndex()) {
            if (!line.contains("upload-artifact")) continue
            // Walk forward to this step's path: block and collect its entries.
            var j = i
            while (j < lines.size && !lines[j].trimStart().startsWith("path:")) {
                // Stop at the next step rather than running into the one after.
                if (j > i && lines[j].trimStart().startsWith("- name:")) break
                j++
            }
            if (j >= lines.size || !lines[j].trimStart().startsWith("path:")) continue
            val inline = lines[j].substringAfter("path:").trim()
            if (inline.isNotEmpty() && inline != "|") {
                out += inline
                continue
            }
            var k = j + 1
            while (k < lines.size) {
                val t = lines[k].trim()
                if (t.isEmpty() || t.startsWith("- name:") || t.contains(":") && !t.startsWith("-")) break
                out += t.removePrefix("- ").trim()
                k++
            }
        }
        return out
    }

    @Test
    fun `no workflow step ever uploads a private key or a password`() {
        assumeTrue("workflow not found", repoRoot() != null)
        val secretish = Regex(
            """(\.jks|\.keystore|\.p12|\.pfx|\.pem|\.key)\b|password|secret|\.b64""",
            RegexOption.IGNORE_CASE
        )
        val offenders = uploadedPaths(workflow()).filter { secretish.containsMatchIn(it) }
        assertTrue(
            "a private key or password must never enter an artifact - a public repo " +
                "hands it to everyone, and an APK signed with it installs as an update " +
                "over the real app: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the signing password is never written to a file`() {
        assumeTrue("workflow not found", repoRoot() != null)
        // `echo "$KS_PASS" > keystore-password.txt` is how it leaked. A password
        // belongs in the environment and the mask, never on the disk of a runner
        // whose files can be collected.
        val offenders = workflow().lines()
            .filter { Regex(""">\s*\S*(password|passwd|\.b64)\S*""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .map { it.trim() }
        assertTrue("the signing password must not be written to a file: $offenders", offenders.isEmpty())
    }

    @Test
    fun `a release is refused when the signing key would not outlive the run`() {
        assumeTrue("workflow not found", repoRoot() != null)
        val text = workflow()
        // The build job has to report whether it invented the key ...
        assertTrue(
            "the build job must expose whether it minted a throwaway key",
            Regex("""outputs:(?s).{0,400}?throwaway_key:""").containsMatchIn(text)
        )
        // ... and the release job has to refuse on it, not merely mention it.
        val release = text.substringAfter("\n  release:")
        assertTrue(
            "the release job must branch on build.outputs.throwaway_key",
            release.contains("needs.build.outputs.throwaway_key == 'true'")
        )
        assertTrue(
            "refusing means failing the job, not printing a warning",
            Regex("""throwaway_key == 'true'(?s).{0,2000}?exit 1""").containsMatchIn(release)
        )
    }
}
