package app.cloudsaver.strings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads strings.xml directly and audits what the user will actually see.
 *
 * Android only unescapes %% when a string goes through String.format, which
 * happens only when arguments are passed - and arguments are only passed when
 * the string has positional placeholders. A %% in a string without one is
 * therefore rendered literally, which is how "30%%" reached the Settings
 * screen. Nothing in the normal build catches that, so this does.
 */
class StringResourceAuditTest {

    private val stringsFile = File("src/main/res/values/strings.xml")

    private val entry = Regex(
        """<string name="([^"]+)"(?:\s+formatted="false")?>(.*?)</string>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val positional = Regex("""%\d+\$""")

    private fun strings(): List<Pair<String, String>> {
        assertTrue("strings.xml must be readable at ${stringsFile.absolutePath}", stringsFile.isFile)
        return entry.findAll(stringsFile.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

    @Test
    fun thereAreStringsToAudit() {
        assertTrue("expected a populated strings.xml", strings().size > 100)
    }

    @Test
    fun noDoublePercentWithoutFormatArguments() {
        val offenders = strings()
            .filter { (_, body) -> body.contains("%%") && !positional.containsMatchIn(body) }
            .map { it.first }
        assertTrue(
            "these render a literal %% because nothing formats them: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * A literal % is only safe in a string the toolchain knows is never
     * formatted. Without formatted="false", lint reads it as a broken
     * conversion and a stray argument would throw at runtime.
     */
    @Test
    fun literalPercentStringsDeclareThemselvesUnformatted() {
        val raw = stringsFile.readText()
        val all = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val offenders = all.findAll(raw)
            .filter { m ->
                val attrs = m.groupValues[2]
                val body = m.groupValues[3]
                body.contains('%') &&
                    !positional.containsMatchIn(body) &&
                    !attrs.contains("formatted=\"false\"")
            }
            .map { it.groupValues[1] }
            .toList()
        assertTrue("literal % needs formatted=\"false\": $offenders", offenders.isEmpty())
    }

    @Test
    fun noBarePercentInFormattedStrings() {
        // The mirror image: once a string is formatted, a lone % is a crash.
        val offenders = strings()
            .filter { (_, body) -> positional.containsMatchIn(body) }
            .filter { (_, body) ->
                // Take out the escaped pairs first, then the placeholders;
                // whatever % survives is the one that would throw.
                val rest = body.replace("%%", "").replace(positional, "")
                rest.contains('%')
            }
            .map { it.first }
        assertTrue("a lone % in a formatted string throws at runtime: $offenders", offenders.isEmpty())
    }

    @Test
    fun noUnresolvedPlaceholdersLeftInCopy() {
        val markers = listOf("TODO", "FIXME", "{}", "{0}", "XXX", "Lorem ipsum")
        val offenders = strings()
            .filter { (_, body) -> markers.any { body.contains(it, ignoreCase = false) } }
            .map { it.first }
        assertTrue("unfinished copy: $offenders", offenders.isEmpty())
    }

    /** Terms the product deliberately dropped must not reappear. */
    @Test
    fun retiredVocabularyIsGone() {
        val retired = listOf("GlassSaver", "LiteSaver", "CloudShrink", "Likely backed up")
        val offenders = strings()
            .filter { (_, body) -> retired.any { body.contains(it, ignoreCase = true) } }
            .map { it.first }
        assertTrue("retired wording still shipping: $offenders", offenders.isEmpty())
    }

    /** Long numbers in copy mean an unformatted double reached the user. */
    @Test
    fun noRawDoublesInCopy() {
        val raw = Regex("""\d\.\d{4,}""")
        val offenders = strings()
            .filter { (_, body) -> raw.containsMatchIn(body) }
            .map { it.first }
        assertTrue("unformatted numbers in copy: $offenders", offenders.isEmpty())
    }
}
