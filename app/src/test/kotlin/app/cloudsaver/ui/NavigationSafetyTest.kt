package app.cloudsaver.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stops the navigation trap coming back.
 *
 * A plain `navigate()` to one of the four bottom-tab routes pushes a *second*
 * copy of that tab onto the back stack. The bar then reads the app as already
 * being on that tab and ignores taps on it, while the pop target for Home is
 * buried underneath - so tapping a chip on Home landed the user in Files with
 * the Home tab dead and no way back except the system button.
 *
 * The fix is that every route goes through `NavHostController.goTo`, which
 * knows the difference between switching tabs and pushing a screen. This test
 * enforces the rule mechanically, because the failure is invisible in code
 * review: `nav.navigate(Routes.FILES)` looks exactly like the correct thing.
 */
class NavigationSafetyTest {

    private val sources: List<File>
        get() = File("src/main/kotlin/app/cloudsaver/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** App.kt defines goTo and is the one place allowed to call navigate. */
    private val definesGoTo = "App.kt"

    @Test
    fun thereAreSourcesToAudit() {
        assertTrue("expected UI sources to audit", sources.size > 5)
    }

    @Test
    fun noScreenNavigatesToATabDirectly() {
        val offenders = mutableListOf<String>()
        val direct = Regex("""\bnav(?:Controller)?\.navigate\s*\(""")
        for (file in sources) {
            if (file.name == definesGoTo) continue
            for ((index, line) in file.readLines().withIndex()) {
                if (direct.containsMatchIn(line)) {
                    offenders += "${file.name}:${index + 1}"
                }
            }
        }
        assertTrue(
            "these call navigate() directly instead of goTo(), which strands " +
                "the user when the target is a tab: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * goTo has to keep treating tabs differently from pushes. If the branch
     * disappears the helper silently becomes the bug it replaced.
     */
    @Test
    fun goToStillDistinguishesTabsFromPushes() {
        val app = sources.first { it.name == definesGoTo }.readText()
        assertTrue("goTo must exist", app.contains("fun NavHostController.goTo("))
        assertTrue(
            "goTo must special-case the tab routes",
            app.contains("route in Routes.TABS")
        )
        assertTrue(
            "tab switching must pop to the start destination, not a fixed route",
            app.contains("popUpTo(graph.findStartDestination().id)")
        )
        assertTrue("tab switching must reuse the existing entry", app.contains("launchSingleTop"))
        assertTrue("tab switching must restore the tab's own state", app.contains("restoreState"))
    }

    /**
     * Every destination the NavHost can reach must offer a way out. A screen
     * that can be entered and not left is the worst kind of dead end: the
     * system back button is the only escape and nothing on screen says so.
     */
    @Test
    fun everyNonTabScreenCanBeLeft() {
        val app = sources.first { it.name == definesGoTo }.readText()
        val routes = Regex("""composable\(Routes\.(\w+)\)\s*\{\s*(\w+)\(""")
            .findAll(app)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertTrue("expected the NavHost to declare routes", routes.size > 10)

        val tabs = setOf("HOME", "FILES", "STORAGE", "OPTIONS")
        val screenSources = sources.associateBy({ it.name }, { it.readText() })
        val offenders = routes
            .filter { (route, _) -> route !in tabs }
            .filter { (_, screen) ->
                val owner = screenSources.values.firstOrNull { it.contains("fun $screen(") }
                owner == null || !owner.contains("popBackStack")
            }
            .map { it.second }
        assertTrue("these screens have no way back: $offenders", offenders.isEmpty())
    }

    /**
     * The launcher activity is exported, so the route on its intent is a
     * string from another app. Handing an unknown one to the navigator throws
     * and takes the app down on launch, which any app on the phone could do
     * in one line and repeat forever.
     */
    @Test
    fun aRouteFromOutsideIsCheckedBeforeItIsNavigatedTo() {
        val vm = File("src/main/kotlin/app/cloudsaver/ui/AppViewModel.kt").readText()
        val fn = vm.substringAfter("fun consumeDeepLink(", "")
        assertTrue("consumeDeepLink is gone; the rule below has nothing to guard", fn.isNotEmpty())
        assertTrue(
            "the route arriving on an exported activity's intent is stored without " +
                "checking it is a screen this app has, so an unknown one reaches " +
                "the navigator and throws",
            fn.take(400).contains("Routes.isKnown(")
        )
    }

    /**
     * The allow-list has to be the graph, not a copy of it that drifts. A
     * route missing from it is a notification that opens nothing; a route in
     * it that the graph lacks is the crash the check exists to stop.
     */
    @Test
    fun theAllowedRoutesAreExactlyTheScreensTheGraphHas() {
        val app = File("src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        val listed = app.substringAfter("val ALL: Set<String> = setOf(")
            .substringBefore(")")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val inGraph = Regex("""composable\(Routes\.([A-Z_]+)\)""")
            .findAll(app)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("no routes were found in App.kt; the parser is broken", inGraph.size > 10)
        assertEquals(
            "a screen the graph has is not reachable from an alert or a shortcut",
            emptySet<String>(), inGraph - listed
        )
        assertEquals(
            "these are allowed in but the graph has no such screen, so navigating " +
                "to one throws",
            emptySet<String>(), listed - inGraph
        )
    }

    /**
     * A route the app sends itself has to be one it has.
     *
     * An alert and a launcher shortcut both travel as a plain string on an
     * intent. Nothing checked those strings against the graph, so renaming a
     * screen would have left a shortcut that opens the app and then sits on
     * Home, and an alert about a problem that never takes you to it - with no
     * error anywhere, because an unknown route is now dropped rather than
     * thrown.
     */
    @Test
    fun `every route the app sends itself is a route it has`() {
        val app = File("src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        val known = Regex("""const val [A-Z_]+ = "([a-z_]+)"""")
            .findAll(app.substringAfter("object Routes {").substringBefore("\n}"))
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("no route constants were found; the parser is broken", known.size > 10)

        val sent = mutableListOf<Pair<String, String>>()
        for (file in File("src/main/kotlin/app/cloudsaver").walkTopDown()) {
            if (!file.isFile || file.extension != "kt") continue
            for (m in Regex("""route = "([a-z_]+)"""").findAll(file.readText())) {
                sent += file.name to m.groupValues[1]
            }
        }
        val shortcuts = File("src/main/res/xml/shortcuts.xml").readText()
        for (m in Regex("""android:name="app\.cloudsaver\.route" android:value="([a-z_]+)"""")
            .findAll(shortcuts)) {
            sent += "shortcuts.xml" to m.groupValues[1]
        }
        assertTrue("nothing was found sending a route; the parser is broken", sent.size >= 4)

        val unknown = sent.filterNot { it.second in known }
        assertTrue(
            "these send the app to a screen the graph does not have, so the alert " +
                "or shortcut opens the app and goes nowhere: $unknown",
            unknown.isEmpty()
        )
    }
}
