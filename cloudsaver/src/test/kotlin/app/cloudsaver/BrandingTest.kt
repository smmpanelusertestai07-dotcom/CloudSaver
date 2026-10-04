package app.cloudsaver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app is called Ente Saver everywhere a person can read it. The old name
 * survives only where it is a real place: the folder an upgraded install
 * still uses until Move, and the address of the repository.
 */
class BrandingTest {

    private val strings = File("src/main/res/values/strings.xml").readText()

    private val stillReal = listOf(
        "github.com/smmpanelusertestai07-dotcom/CloudSaver",
        "Pictures/CloudSaver"
    )

    @Test
    fun `the app's name is Ente Saver`() {
        assertTrue(Regex("""<string name="app_name"[^>]*>Ente Saver</string>""").containsMatchIn(strings))
    }

    @Test
    fun `no text a person reads still says CloudSaver`() {
        val offenders = Regex("""<(string|item)[^>]*>([^<]*)</\1>""").findAll(strings)
            .map { it.groupValues[2] }
            .filter { text -> stillReal.fold(text) { left, real -> left.replace(real, "") }.contains("CloudSaver") }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the released file carries the new name`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, ".github/workflows/build.yml").isFile }
        val workflow = File(root, ".github/workflows/build.yml").readText()
        assertTrue(workflow.contains("EnteSaver-v\$VERSION-release.apk"))
        assertTrue(workflow.contains("Ente Saver \$TAG"))
        val e2e = File(root, ".github/scripts/emulator-e2e.sh").readText()
        assertTrue(e2e.contains("adb install -r EnteSaver-release.apk"))
    }
}
