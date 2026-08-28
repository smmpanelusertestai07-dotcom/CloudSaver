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

    /**
     * The same file with every comment blanked out.
     *
     * This matters more here than it would anywhere else. The comments in
     * this app are long, and they are mostly about the very mistakes the
     * rules below forbid - the word "TextOverflow.Clip" appears in the
     * comment explaining why nothing uses it, and a comment on setup says
     * that a 28 dp spacer used to stand in for the status bar. A rule read
     * over the prose would fail on its own explanation. Line breaks inside a
     * block comment are kept so the line numbers in a failure still point at
     * the right line of the real file.
     */
    private fun code(text: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
            .replace(text) { m -> "\n".repeat(m.value.count { it == '\n' }) }
            .split("\n")
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * The block body of a function whose opening bracket is at [paren]:
     * skip the parameter list, skip any return type, take what is between
     * the braces after it.
     */
    private fun bodyOf(text: String, paren: Int): String {
        val params = blockAt(text, paren)
        val brace = text.indexOf('{', paren + params.length)
        if (brace < 0) return ""
        return blockAt(text, brace, '{', '}')
    }

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
                // A body whose content lives inside a bounded lazy container
                // scrolls through that container; wrapping it in a second
                // verticalScroll is the nested-scroll crash. Either counts as
                // reachable; a body with neither is the clipped dialog this
                // rule exists for.
                val scrolls = body.contains("verticalScroll") ||
                    body.contains("LazyColumn(") ||
                    body.contains("AlbumGrid(") ||
                    body.contains("LazyVerticalGrid(")
                if (hasText && !scrolls) {
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

    // ---- screens with content past the bottom edge ---------------------------

    /**
     * Every screen can be scrolled to its end.
     *
     * A screen that only draws is fine on the phone it was drawn on and
     * nowhere else. Turn that phone sideways and the height halves; turn the
     * font up to 200% and every line of it doubles. Whatever was at the
     * bottom - and on this app that is usually the button the screen exists
     * for - is then simply past the edge of the glass with no way to reach
     * it, which reads as an app that has frozen rather than an app that is
     * too small. It was true of the lock screen, of setup and of the
     * calculator at once.
     *
     * A screen counts as scrollable when it scrolls itself, or when it hands
     * its content to a page frame in the same file that does - which is how
     * the eight Help screens and the Find space screens are written.
     */
    @Test
    fun everyScreenCanBeScrolledToItsEnd() {
        val scrollers = listOf("verticalScroll", "LazyColumn", "LazyVerticalGrid", "LazyRow")
        val offenders = mutableListOf<String>()
        // The page frames anywhere in the app: a composable whose own body
        // scrolls, so anything handing its content to one is scrollable. They
        // are collected across every file rather than per screen, because the
        // frame that matters most - ListScreenScaffold, which five screens
        // hand their rows to - lives in components rather than beside them.
        // Collected per file, those five screens only counted as scrollable
        // while they happened to hold a scroll of their own somewhere, which
        // is how a screen could lose its last one and this rule stay quiet.
        val frames = mutableSetOf<String>()
        for ((_, raw) in sources()) {
            val text = code(raw)
            Regex("\\bfun\\s+([A-Z][A-Za-z0-9_]*)\\s*\\(").findAll(text).forEach { m ->
                val body = bodyOf(text, m.range.last)
                if (scrollers.any { body.contains(it) }) frames += m.groupValues[1]
            }
        }
        for ((path, raw) in sources()) {
            if (!path.startsWith("ui/screens/")) continue
            val text = code(raw)
            Regex("\\bfun\\s+([A-Za-z0-9_]*Screen)\\s*\\(").findAll(text).forEach { m ->
                val name = m.groupValues[1]
                val body = bodyOf(text, m.range.last)
                val scrollsItself = scrollers.any { body.contains(it) }
                val usesAFrame = frames.any { frame ->
                    frame != name && Regex("\\b" + frame + "\\s*\\(").containsMatchIn(body)
                }
                if (!scrollsItself && !usesAFrame) {
                    offenders += "$path:${lineOf(text, m.range.first)}  $name"
                }
            }
        }
        assertTrue(
            "a screen with no scroll container has its lower half past the " +
                "bottom edge the moment the phone is turned sideways or the " +
                "font is turned up, and nothing can reach it: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- layouts that assume the reading direction ---------------------------

    /**
     * Nothing is positioned from the left of the screen.
     *
     * Compose has two of almost everything here: start/end, which follow the
     * language, and left/right, which do not. The second set is for the rare
     * case where a thing genuinely has to stay on one physical side - a
     * timeline, a ruler - and this app has none of those. Every use of it is
     * therefore a layout that comes out mirrored-but-not-quite in Arabic or
     * Hebrew: the text flips, one padding does not, and the result is a
     * screen with a gap on the wrong side and content jammed against the
     * edge.
     *
     * The back arrow is the same rule in icon form. Icons.Filled.ArrowBack
     * points left whatever the language; Icons.AutoMirrored.Filled.ArrowBack
     * turns round with it, and an arrow that points the wrong way is an arrow
     * people do not press.
     */
    @Test
    fun nothingIsPositionedFromTheLeftOfTheScreen() {
        val banned = listOf(
            "Modifier.absolutePadding" to Regex("absolutePadding"),
            "Alignment.Absolute*" to Regex("Alignment\\.Absolute"),
            "Arrangement.Absolute" to Regex("Arrangement\\.Absolute"),
            "TextAlign.Left/Right" to Regex("TextAlign\\.(Left|Right)\\b"),
            "padding(left/right =" to Regex("padding\\(\\s*(left|right)\\s*="),
            "an arrow that does not turn round" to Regex(
                "Icons\\.(?!AutoMirrored)[A-Za-z.]*" +
                    "(ArrowBack|ArrowForward|KeyboardArrowLeft|KeyboardArrowRight)"
            )
        )
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            val text = code(raw)
            for ((name, pattern) in banned) {
                pattern.findAll(text).forEach { m ->
                    offenders += "$path:${lineOf(text, m.range.first)}  $name"
                }
            }
        }
        assertTrue(
            "left and right do not follow the language, so a layout built " +
                "from them comes out half-mirrored in Arabic and Hebrew - " +
                "start and end, and AutoMirrored icons: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- text that is shortened without saying so ----------------------------

    /**
     * When text has to be shortened, it says so.
     *
     * TextOverflow.Clip cuts mid-letter and leaves nothing behind to say a
     * cut happened, so a folder name and a truncated folder name look
     * equally final - which on the screen that shows where the backup writes
     * is the difference between reassurance and a wrong answer. The line
     * limit rule above forces an overflow to be named; this one forces the
     * named one not to be Clip.
     *
     * softWrap = false is the same cut by another route: it refuses the
     * second line rather than the last letter. It is right for exactly one
     * thing in this app - a formatted number, which is a single token that
     * must not break across lines - and that use is paired with maxLines = 1
     * and an ellipsis so an over-wide figure is visibly shortened. Anywhere
     * near a sentence it is a sentence with its end missing.
     */
    @Test
    fun textThatDoesNotFitIsShortenedVisibly() {
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            val text = code(raw)
            Regex("TextOverflow\\.Clip").findAll(text).forEach { m ->
                offenders += "$path:${lineOf(text, m.range.first)}  TextOverflow.Clip"
            }
            val lines = text.split("\n")
            lines.forEachIndexed { i, line ->
                if (!Regex("softWrap\\s*=\\s*false").containsMatchIn(line)) return@forEachIndexed
                val window = lines.subList(
                    maxOf(0, i - 6),
                    minOf(lines.size, i + 7)
                ).joinToString("\n")
                if (!window.contains("maxLines = 1") || !window.contains("overflow")) {
                    offenders += "$path:${i + 1}  softWrap = false on wrapping text"
                }
            }
        }
        assertTrue(
            "text cut with no ellipsis reads as text that ended - Clip, and " +
                "softWrap = false on anything but a single short token: " +
                offenders,
            offenders.isEmpty()
        )
    }

    // ---- guessing the shape of the window ------------------------------------

    /**
     * Nothing decides a layout from LocalConfiguration.
     *
     * screenWidthDp is the size of the window the app was given, which on a
     * phone is the screen and almost nowhere else: in split screen, in a
     * freeform window, on the second half of a foldable, it is a different
     * number from the space the thing being laid out actually has. It is also
     * rounded to whole dp and, historically, has not always excluded the
     * system bars.
     *
     * BoxWithConstraints answers the same question about the one box that is
     * asking, which is the question every caller here meant. The metric grid
     * and the segmented control both choose their column count that way, and
     * both are right in a half-width window because of it.
     */
    @Test
    fun noScreenDecidesItsLayoutFromTheWindowSize() {
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            val text = code(raw)
            Regex("screenWidthDp|screenHeightDp").findAll(text).forEach { m ->
                offenders += "$path:${lineOf(text, m.range.first)}  ${m.value}"
            }
        }
        assertTrue(
            "the window is not the box: in split screen or on a foldable " +
                "screenWidthDp is a different number from the width the " +
                "layout has - BoxWithConstraints instead: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * No screen writes down how tall a system bar is.
     *
     * The status bar is 24 dp until it is 48 on a phone with a punch-hole,
     * and the navigation bar is 48 dp until the phone is using gestures and
     * it is 16, or the phone is sideways and it is on the end instead of the
     * bottom. A number typed in for either is right on one device. Setup had
     * a 28 dp spacer standing in for the status bar and it was wrong the
     * moment anyone turned the phone; the insets know the real figure.
     */
    @Test
    fun noScreenWritesDownTheHeightOfASystemBar() {
        val named = Regex("(?i)(status|navigation|system)\\s?bar")
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            code(raw).split("\n").forEachIndexed { i, line ->
                if (!named.containsMatchIn(line)) return@forEachIndexed
                if (!Regex("\\d+\\.dp").containsMatchIn(line)) return@forEachIndexed
                offenders += "$path:${i + 1}  ${line.trim().take(60)}"
            }
        }
        assertTrue(
            "a system bar is a different height on every phone and in every " +
                "orientation - the insets know it, a number typed here does " +
                "not: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- figures that grow with the reader's font ----------------------------

    /**
     * Every size multiplied by the font scale is bounded first.
     *
     * The pattern the app uses is a minimum width or height that grows with
     * the text inside it, because what has to fit in a metric tile is words.
     * Multiplying by the raw scale is what makes that a bug: Android's own
     * slider stops at 200%, but the value is not capped there and an OEM
     * accessibility setting can hand over more. A tile floor multiplied by an
     * uncapped scale becomes wider than the phone, the row then fits one
     * column, and the grid the number lives in disappears down the screen.
     * coerceIn(1f, 2f) says "grow with the reader, up to the point Android
     * itself stops asking".
     */
    @Test
    fun everySizeThatGrowsWithTheFontIsBounded() {
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            code(raw).split("\n").forEachIndexed { i, line ->
                if (!line.contains("fontScale")) return@forEachIndexed
                // Only a multiplication turns the scale into a size. Reading
                // it to compare against a threshold is how the stacked-text
                // switch works and is fine.
                if (!line.contains("*")) return@forEachIndexed
                if (line.contains("coerce")) return@forEachIndexed
                offenders += "$path:${i + 1}  ${line.trim().take(60)}"
            }
        }
        assertTrue(
            "an uncapped font scale multiplied into a width makes a floor " +
                "wider than the phone - coerceIn(1f, 2f) first: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- the keyboard covering the field being typed into --------------------

    /**
     * A screen with something to type into makes room for the keyboard.
     *
     * The keyboard is drawn over the app, not beside it. A field in the lower
     * half of a screen is therefore underneath it the moment it opens, and
     * the person typing cannot see what they are typing - which on the
     * calculator, where the whole screen is one number being entered and one
     * answer, is the entire screen. imePadding lifts the content by exactly
     * the height the keyboard took.
     *
     * Scoped to screens on purpose: a field inside a dialog is lifted by the
     * dialog's own window, and a search box that lives at the top of a list
     * is never under the keyboard to begin with.
     */
    @Test
    fun everyScreenWithAFieldMakesRoomForTheKeyboard() {
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            if (!path.startsWith("ui/screens/")) continue
            val text = code(raw)
            if (!text.contains("TextField(")) continue
            if (!text.contains("imePadding()")) offenders += path
        }
        assertTrue(
            "the keyboard is drawn over the app, so without imePadding the " +
                "field being typed into is underneath it: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- a list that measures against nothing at all ------------------------

    /**
     * A LazyColumn has to be told how tall it may be.
     *
     * Given no ceiling - a Column that scrolls, a parent measured with an
     * unbounded height - it refuses outright: "Vertically scrollable component
     * was measured with an infinity maximum height constraints". Not a
     * cosmetic fault, a crash on open, and one that eight emulator jobs across
     * eight Android versions all reported at once while every unit test on the
     * JVM stayed green.
     *
     * There are only two honest ceilings. Inside a Column that fills the
     * screen, weight(1f) hands the list what is left after its siblings. Inside
     * something that scrolls - a card in setup, the body of a dialog - only a
     * heightIn(max = ...) can say how much of that scroll the list may take.
     * fillMaxHeight and fillMaxSize count too, and only where the parent is
     * itself bounded.
     */
    @Test
    fun everyLazyListIsToldHowTallItMayBe() {
        val bounded = listOf("weight(", "heightIn(", "fillMaxHeight", "fillMaxSize")
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            if (!path.startsWith("ui/")) continue
            val text = code(raw)
            Regex("\\bLazyColumn\\(").findAll(text).forEach { m ->
                val body = blockAt(text, m.range.last)
                // Only the modifier matters, and it is written before the
                // trailing lambda; the rows themselves may say anything.
                val head = body.substringBefore("{ ").take(400)
                if (bounded.none { head.contains(it) }) {
                    offenders += "$path:${lineOf(text, m.range.first)}"
                }
            }
        }
        assertTrue(
            "a LazyColumn with no ceiling throws the moment it is measured " +
                "inside anything that scrolls - weight(1f) where it fills " +
                "what is left, heightIn(max = ) where it does not: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- a sheet asks how tall its contents want to be -----------------------

    /**
     * Nothing that scrolls may sit unbounded inside a bottom sheet.
     *
     * A sheet sizes itself to what it holds, so it measures its content with no
     * maximum height - and a scrolling container asked how tall it would like to
     * be throws rather than answers. Every filter chip on every list opened the
     * one sheet in this app, so a scrolling column added inside it crashed Files,
     * Free up space, Kept copies and Activity alike, on all eight Android
     * versions at once.
     *
     * The ceiling is what turns the question into one the list can answer.
     */
    @Test
    fun nothingInsideASheetScrollsWithoutACeiling() {
        val bounded = listOf("heightIn(", "weight(", "fillMaxHeight", "requiredHeight")
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            val text = code(raw)
            Regex("\\bModalBottomSheet\\(").findAll(text).forEach { m ->
                val body = blockAt(text, m.range.last)
                val scrolls = body.contains("verticalScroll(") || body.contains("LazyColumn(")
                if (scrolls && bounded.none { body.contains(it) }) {
                    offenders += "$path:${lineOf(text, m.range.first)}"
                }
            }
        }
        assertTrue(
            "a sheet measures its content with no maximum height, so a list " +
                "inside one has to be told where to stop: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- two people solving the same problem ---------------------------------

    /**
     * The empty state a screen hands to the list framework must not scroll.
     *
     * The framework's own empty branch already scrolls, because it is the only
     * place that knows how much height the title, the search box and the chips
     * above it have taken. A screen that adds a second scroll inside that one
     * is measured by it with no ceiling at all, and a scrolling container asked
     * how tall it would like to be throws rather than answers.
     *
     * This is what a shared framework is for, and it is worth a rule because
     * nothing about either half looks wrong on its own: both were written to
     * fix the same real complaint - that on a phone held sideways at a large
     * font, the lower half of the empty state sat past the bottom edge - and
     * each was right until the other existed. Files and Free up space crashed
     * on every Android version the moment a filter or a search left nothing
     * to show.
     */
    @Test
    fun anEmptyStateHandedToTheFrameworkDoesNotScrollItself() {
        val offenders = mutableListOf<String>()
        for ((path, raw) in sources()) {
            if (!path.startsWith("ui/screens/")) continue
            val text = code(raw)
            Regex("emptyContent = \\{").findAll(text).forEach { m ->
                val body = blockAt(text, m.range.last)
                if (body.contains("verticalScroll(")) {
                    offenders += "$path:${lineOf(text, m.range.first)}"
                }
            }
        }
        assertTrue(
            "the framework's empty branch is what scrolls; a second scroll " +
                "inside it is measured with no maximum height and throws: " +
                "$offenders",
            offenders.isEmpty()
        )
    }
}
