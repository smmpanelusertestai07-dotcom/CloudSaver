package app.cloudsaver.strings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps implementation vocabulary out of what the user reads.
 *
 * Words like "fingerprint", "evidence" and "paced" are precise inside the
 * codebase and meaningless on a phone screen - and they crept back in twice,
 * each time by someone naming a string after the field behind it. This reads
 * the shipped strings and fails the build rather than waiting for it to be
 * noticed in a screenshot.
 */
class PlainEnglishTest {

    private val stringsFile = File("src/main/res/values/strings.xml")

    /**
     * Words that mean something to a developer and nothing to a reader.
     *
     * "hash" is matched as a whole word: it appears inside "hashes" fairly
     * only as the same jargon, but a substring match would also flag ordinary
     * words that happen to contain it.
     */
    private val banned = listOf(
        "fingerprint" to Regex("""fingerprint""", RegexOption.IGNORE_CASE),
        "hash" to Regex("""\bhash(es|ed|ing)?\b""", RegexOption.IGNORE_CASE),
        "evidence" to Regex("""\bevidence\b""", RegexOption.IGNORE_CASE),
        "confirmation type" to Regex("""confirmation type""", RegexOption.IGNORE_CASE),
        "in flight" to Regex("""\bin[- ]flight\b""", RegexOption.IGNORE_CASE),
        "paced" to Regex("""\bpaced\b""", RegexOption.IGNORE_CASE),
        "sha256" to Regex("""sha-?256""", RegexOption.IGNORE_CASE),
        "mediastore" to Regex("""mediastore""", RegexOption.IGNORE_CASE)
    )

    /**
     * The biometric sense of "fingerprint" is the actual finger, which is what
     * the unlock prompt is about and the right word for it. Nothing else may
     * use it.
     */
    private val allowed = setOf("lock_subtitle", "opt_lock_hint")

    private val entry = Regex(
        """<string name="([^"]+)"[^>]*>(.*?)</string>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val pluralBlock = Regex(
        """<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun userFacingStrings(): List<Pair<String, String>> {
        assertTrue("strings.xml must be readable", stringsFile.isFile)
        val text = stringsFile.readText()
        val singles = entry.findAll(text).map { it.groupValues[1] to it.groupValues[2] }
        val plurals = pluralBlock.findAll(text).map { it.groupValues[1] to it.groupValues[2] }
        return (singles + plurals).filter { it.first !in allowed }.toList()
    }

    @Test
    fun thereAreStringsToAudit() {
        assertTrue("expected a populated strings.xml", userFacingStrings().size > 100)
    }

    @Test
    fun noImplementationVocabularyReachesTheUser() {
        val offenders = mutableListOf<String>()
        for ((name, body) in userFacingStrings()) {
            for ((word, pattern) in banned) {
                if (pattern.containsMatchIn(body)) offenders += "$name says \"$word\""
            }
        }
        assertTrue(
            "these strings use implementation words: ${offenders.joinToString("; ")}",
            offenders.isEmpty()
        )
    }

    /**
     * "Waiting for proof" describes the app's bookkeeping, not the file. The
     * reader wants to know where their photo stands, and the answer is one of
     * the real states.
     */
    @Test
    fun nothingTellsTheUserItIsWaitingForProof() {
        val offenders = userFacingStrings()
            .filter { it.second.contains("waiting for proof", ignoreCase = true) }
            .map { it.first }
        assertTrue("these still say \"waiting for proof\": $offenders", offenders.isEmpty())
    }

    /**
     * Shouting at someone is not emphasis. Any all-caps run of four or more
     * letters is either a proper noun or a mistake, and the proper nouns are
     * listed.
     */
    @Test
    fun nothingShoutsAtTheUser() {
        val properNouns = setOf(
            "MEGA", "DCIM", "LICENSE", "JPEG", "HEVC", "HDR", "CloudSaver",
            "SDCARD", "HEIC", "AVIF", "WEBP", "PIN", "APK"
        )
        val shouty = Regex("""\b[A-Z]{4,}\b""")
        val offenders = mutableListOf<String>()
        for ((name, body) in userFacingStrings()) {
            for (match in shouty.findAll(body)) {
                if (match.value !in properNouns) offenders += "$name says \"${match.value}\""
            }
        }
        assertTrue("these shout: ${offenders.joinToString("; ")}", offenders.isEmpty())
    }
}
