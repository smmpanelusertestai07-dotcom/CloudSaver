package app.cloudsaver

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules about the words the app shows, checked against the strings file.
 *
 * Copy drifts in a way code does not: a screen gets rewritten, its strings
 * stay behind, and nobody notices until a user reads a sentence describing a
 * button that no longer exists.
 */
class HelpContentTest {

    private fun strings(): File? {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/res/values/strings.xml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun text(): String = strings()!!.readText()

    private fun values(): List<Pair<String, String>> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    @Test
    fun `the FAQ is exactly twelve questions, each with an answer`() {
        assumeTrue("strings.xml not found", strings() != null)
        val body = text()
        val questions = Regex("""<string name="faq_q(\d+)"""").findAll(body)
            .map { it.groupValues[1].toInt() }.toList().sorted()
        val answers = Regex("""<string name="faq_a(\d+)"""").findAll(body)
            .map { it.groupValues[1].toInt() }.toList().sorted()
        // Grew by exactly the two answers this release owes the owner: where
        // the recommended figures come from, and what "keep it in the same
        // album" does. Nothing else.
        assertEquals("the FAQ must hold eighteen questions", (1..18).toList(), questions)
        assertEquals("every question needs its answer", questions, answers)
    }

    @Test
    fun `the deleted page covers six conditions and is reachable from Help and the FAQ`() {
        assumeTrue("strings.xml not found", strings() != null)
        val body = text()
        for (i in 1..6) {
            assertTrue("condition $i needs a title", body.contains("name=\"deleted_r${i}_t\""))
            assertTrue("condition $i needs an answer", body.contains("name=\"deleted_r${i}_b\""))
        }
        val screens = File("src/main/kotlin/app/cloudsaver/ui/screens/HelpScreens.kt").readText()
        assertTrue(screens.contains("fun HelpDeletedScreen"))
        // Linked from the Help list AND from the bottom of the FAQ - the page
        // exists for a worried moment, and a worried person starts at either.
        val helpList = screens.substringAfter("fun HelpScreen(")
            .substringBefore("fun HelpLink")
        assertTrue("Help must list the page", helpList.contains("HELP_DELETED"))
        val faq = screens.substringAfter("fun HelpFaqScreen(")
            .substringBefore("fun HelpQualityScreen")
        assertTrue("the FAQ must link the page", faq.contains("HELP_DELETED"))
        val app = File("src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        assertTrue(
            "the route must be registered",
            app.contains("composable(Routes.HELP_DELETED)")
        )
    }

    @Test
    fun `no user-visible string shouts a constant`() {
        assumeTrue("strings.xml not found", strings() != null)
        // The bug: Activity printed "STORAGE_SAVER" straight from the enum.
        // Acronyms the user does know are allowed by name.
        val allowed = setOf(
            // File formats and standards people already read as words.
            "HEIC", "HEVC", "JPEG", "JPG", "RAW", "GIF", "EXIF", "GPS", "SVG",
            "MP4", "JSON", "APK", "SHA", "AES", "GCM",
            // Proper nouns, folder names and one URL fragment.
            "MEGA", "DCIM", "LICENSE", "CLOUDSAVER", "ANDROID"
        )
        val shouty = Regex("""\b[A-Z][A-Z_]{3,}\b""")
        val offenders = values().flatMap { (key, value) ->
            shouty.findAll(value)
                .map { it.value }
                .filterNot { it.replace("_", "") in allowed }
                .map { "$key: $it" }
        }
        assertTrue("constants leaking into the UI: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no two attention chips say the same words`() {
        assumeTrue("strings.xml not found", strings() != null)
        // Two chips with identical text appear side by side on Home, and a
        // test asking for that text finds two nodes and fails on all eight
        // emulators - which is how this was found, at the cost of a full
        // matrix. The strings themselves can answer it in a millisecond.
        val chips = values().filter { (name, _) -> name.startsWith("chip_") }
        val duplicates = chips.groupBy { it.second.trim() }
            .filterValues { it.size > 1 }
            .map { (text, keys) -> "\"$text\" is used by ${keys.map { it.first }}" }
        assertTrue("two chips cannot say the same thing: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `a light copy is never described as living in the upload folder`() {
        assumeTrue("strings.xml not found", strings() != null)
        // "Light copy" is the name of one specific thing: the smaller file
        // that stays in your gallery in place of an original you removed,
        // in Pictures/Light copies. The copies waiting in Pictures/CloudSaver
        // for the cloud app are optimised copies, and five strings called
        // those light copies too - teaching the wrong meaning of the word
        // before a reader ever met the feature. Checked per clause, because
        // a sentence may legitimately contrast the two.
        val offenders = values().flatMap { (key, value) ->
            value.split(",", ".", ";")
                .filter { it.contains("ight cop") && it.contains("Pictures/CloudSaver") }
                .map { "$key: ${it.trim()}" }
        }
        assertTrue(
            "a light copy lives in the gallery, not the upload folder: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `no help sentence runs past about fifteen words`() {
        assumeTrue("strings.xml not found", strings() != null)
        // R4: short sentences, everywhere someone is being explained something.
        val prefixes = listOf("privacy_b", "quality_", "onb_ready_", "optimise_", "storage_group")
        val offenders = values()
            .filter { (key, _) -> prefixes.any { key.startsWith(it) } }
            .flatMap { (key, value) ->
                value.split(". ", "\\u2014", " - ")
                    .map { it.trim() }
                    .filter { it.split(Regex("\\s+")).size > 22 }
                    .map { "$key: $it" }
            }
        assertTrue("sentences too long: $offenders", offenders.isEmpty())
    }
}
