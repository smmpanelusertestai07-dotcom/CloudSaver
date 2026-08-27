package app.cloudsaver

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout mistakes that have actually shipped, caught on the JVM.
 *
 * Every rule below is here because it broke something real, and every one of
 * them cost a device to find: an emulator boot, a twenty-minute suite, a
 * screenshot read by eye. None of that was necessary - each is a property of
 * the source text, so each can be checked in the unit tests that run on every
 * build in under a second.
 *
 * That is the whole point of this file. A rule proved here does not need to be
 * proved again on a phone.
 */
class LayoutRulesTest {

    private val main = File("src/main/kotlin/app/cloudsaver")

    private fun sources(): List<Pair<String, String>> =
        main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path.substringAfter("app/cloudsaver/") to it.readText() }
            .toList()

    /** The body of a call, from its opening bracket to the matching close. */
    private fun blockAt(text: String, open: Int, o: Char = '(', c: Char = ')'): String {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                o -> depth++
                c -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        return text.substring(open)
    }

    private fun lineOf(text: String, index: Int) = text.take(index).count { it == '\n' } + 1

    // ---- text that is cut instead of wrapped ---------------------------------

    /**
     * maxLines without overflow is TextOverflow.Clip, which is a silent cut
     * through the middle of a letter. Eleven of these were live at once, the
     * worst on the folder path the whole backup depends on.
     */
    @Test
    fun everyLineLimitSaysWhatToDoWhenItIsReached() {
        val offenders = mutableListOf<String>()
        for ((path, text) in sources()) {
            val lines = text.split("\n")
            lines.forEachIndexed { i, line ->
                if (!line.contains("maxLines")) return@forEachIndexed
                val window = lines.subList(
                    maxOf(0, i - 14),
                    minOf(lines.size, i + 15)
                ).joinToString("\n")
                if (!window.contains("overflow")) offenders += "$path:${i + 1}"
            }
        }
        assertTrue(
            "maxLines with no overflow is TextOverflow.Clip - a silent cut " +
                "mid-letter rather than an ellipsis: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- dialogs that cannot be read to the end ------------------------------

    /**
     * A dialog's text slot does not scroll. Material gives it a box that
     * shrinks and then clips, so on a short screen at a large font the lower
     * half of the message - and the buttons under it - are simply past the
     * edge. Nineteen call sites; two rounds were needed to find the last two.
     */
    @Test
    fun everyDialogBodyCanBeScrolled() {
        val offenders = mutableListOf<String>()
        for ((path, text) in sources()) {
            Regex("AlertDialog\\(").findAll(text).forEach { m ->
                val body = blockAt(text, m.range.last)
                val hasText = Regex("\\btext\\s*=\\s*\\{").containsMatchIn(body)
                if (hasText && !body.contains("verticalScroll")) {
                    offenders += "$path:${lineOf(text, m.range.first)}"
                }
            }
        }
        assertTrue(
            "a dialog body with no verticalScroll is cut off on a short " +
                "screen, taking its buttons with it: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- lists that only draw what fits --------------------------------------

    /**
     * performScrollTo does not support lazy lists - issuetracker 178483889. It
     * walks up for a scrollable ancestor and fails outright on a LazyColumn.
     * Four instrumented tests reported "Action performScrollTo() failed" on
     * every CI run from API 30 up because of this.
     *
     * The rule is about the app rather than the tests: a screen that holds a
     * LazyColumn must tag it, so that anything driving it can scroll through
     * the list with performScrollToNode instead.
     */
    @Test
    fun everyLazyListCanBeScrolledByATest() {
        val offenders = mutableListOf<String>()
        for ((path, text) in sources()) {
            if (!path.startsWith("ui/")) continue
            val lazyCount = Regex("\\bLazyColumn\\(").findAll(text).count()
            if (lazyCount == 0) continue
            val tagged = Regex("testTag\\(ListTags\\.ROWS\\)").findAll(text).count()
            if (tagged < lazyCount) {
                offenders += "$path ($lazyCount lists, $tagged tagged)"
            }
        }
        assertTrue(
            "a LazyColumn with no ListTags.ROWS cannot be scrolled with " +
                "performScrollToNode, and performScrollTo does not work on " +
                "one at all: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- boxes that cannot grow with the text inside them --------------------

    /**
     * A fixed height on anything holding text is a clipping bug the moment
     * someone turns the font up. heightIn(min=) says the same thing about the
     * rhythm of the screen without capping what the text may need.
     */
    @Test
    fun nothingThatHoldsTextIsPinnedToAFixedHeight() {
        val allowed = Regex("\\.height\\((\\d+)\\.dp\\)")
        val offenders = mutableListOf<String>()
        for ((path, text) in sources()) {
            if (!path.startsWith("ui/")) continue
            val lines = text.split("\n")
            lines.forEachIndexed { i, line ->
                val m = allowed.find(line) ?: return@forEachIndexed
                // Spacers and dividers are the point of a fixed height.
                val window = lines.subList(
                    maxOf(0, i - 3),
                    minOf(lines.size, i + 2)
                ).joinToString("\n")
                if (window.contains("Spacer") || window.contains("Divider")) return@forEachIndexed
                if (window.contains("HorizontalDivider")) return@forEachIndexed
                // A bar or a rule is a shape, not a container for words.
                val dp = m.groupValues[1].toInt()
                if (dp <= 12) return@forEachIndexed
                offenders += "$path:${i + 1}  ${line.trim().take(60)}"
            }
        }
        assertTrue(
            "a fixed height on something holding text clips it the moment " +
                "the font is turned up - heightIn(min=) instead: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- one rule for every screen -------------------------------------------

    /**
     * Content stops at a readable width and centres itself. It is applied in
     * two places - the NavHost for the app, and setup, which is drawn outside
     * it - and setup was missed the first time, so the app changed shape the
     * moment setup ended.
     */
    @Test
    fun everyRootThatDrawsAScreenCapsItsWidth() {
        val app = File(main, "ui/App.kt").readText()
        val onboarding = File(main, "ui/screens/OnboardingScreen.kt").readText()
        assertTrue(
            "the NavHost must cap its content width",
            app.contains("widthIn(max = Dimens.ContentMaxWidth)")
        )
        assertTrue(
            "setup is drawn outside the Scaffold, so it has to cap its own " +
                "width or the app changes shape when setup ends",
            onboarding.contains("widthIn(max = Dimens.ContentMaxWidth)")
        )
        assertTrue(
            "setup is drawn outside the Scaffold, so nothing applies the " +
                "system bar insets for it unless it does so itself",
            onboarding.contains("safeDrawingPadding()")
        )
    }
}
