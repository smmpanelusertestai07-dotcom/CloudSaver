package app.entesaver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app is called Ente Saver everywhere: on the phone, and in every file of
 * the repository. The old name survives only where it is a permanent
 * identifier that phones already hold - renaming one of those would stop an
 * update from installing, leave an icon behind, or lose what the app knows.
 */
class BrandingTest {

    private val strings = File("src/main/res/values/strings.xml").readText()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, ".github/workflows/build.yml").isFile }

    /** Every place the old name may stay, and why. */
    private val permanent = listOf(
        // The app's id, and the launcher components published under it.
        Regex("""applicationId = "app\.cloudsaver""""),
        Regex("""PKG=app\.cloudsaver\b"""),
        Regex("""targetPackage="app\.cloudsaver""""),
        Regex("""\bapp\.cloudsaver\.(MainActivity|AliasSaver|OpenEnteActivity|IconPackActivity)\b"""),
        // Work that WorkManager already holds on updated phones.
        Regex("""\bapp\.cloudsaver\.work\.(CompressWorker|MaintainWorker)\b"""),
        Regex("""\bcloudsaver\.(compress|maintain|trigger|now)\b"""),
        // The database file every install keeps its records in.
        Regex("""\bcloudsaver\.db\b"""),
        // Folders and files older versions wrote, still watched or read -
        // and, in tests, the look-alike folder names that must not match them.
        Regex("""(?i)\bpictures/cloudsaver\w*"""),
        Regex("""\bDocuments/CloudSaver\b"""),
        Regex("""\.cloudsaver\b"""),
        Regex("""\bcloudsaver_keep\b"""),
        // The old name as a name: the "Cloud Saver" home-screen choice, and
        // old folder names.
        Regex(""""CloudSaver\w*""""),
        Regex(""""Cloud Saver""""),
        Regex(""">Cloud Saver</string>"""),
        // The alias inside the release key, which is part of the key.
        Regex("""SECRET_KEY_ALIAS:-cloudsaver\b"""),
    )

    @Test
    fun `the app's name is Ente Saver`() {
        assertTrue(Regex("""<string name="app_name"[^>]*>Ente Saver</string>""").containsMatchIn(strings))
    }

    @Test
    fun `the old name is left only where it is a permanent identifier`() {
        val tracked = runCatching {
            val git = ProcessBuilder("git", "ls-files", "-z")
                .directory(root).redirectErrorStream(true).start()
            val names = git.inputStream.bufferedReader().readText()
            if (git.waitFor() != 0) null else names.split('\u0000').filter { it.isNotEmpty() }
        }.getOrNull()
        assertTrue("git must be able to list the repository", tracked != null)
        // A key-rotation lineage holds the original certificate, whose name
        // is the old one; it is binary, and part of the key.
        val binary = setOf("png", "jpg", "webp", "jar", "jks", "keystore", "lineage", "ttf", "otf", "so")
        val oldName = Regex("(?i)cloud\\s*saver")
        val offenders = tracked!!
            .filterNot { it.substringAfterLast('.').lowercase() in binary || it.endsWith("/BrandingTest.kt") }
            .flatMap { path ->
                val file = File(root, path)
                if (!file.isFile) return@flatMap emptyList()
                file.readLines().mapIndexedNotNull { i, line ->
                    val left = permanent.fold(line) { text, allowed -> allowed.replace(text, "") }
                    if (oldName.containsMatchIn(left)) "$path:${i + 1}: ${line.trim()}" else null
                }
            }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the released file carries the new name`() {
        val workflow = File(root, ".github/workflows/build.yml").readText()
        assertTrue(workflow.contains("EnteSaver-v\$VERSION-release.apk"))
        assertTrue(workflow.contains("Ente Saver \$TAG"))
        val e2e = File(root, ".github/scripts/emulator-e2e.sh").readText()
        assertTrue(e2e.contains("adb install -r -g EnteSaver-release.apk"))
    }
}
