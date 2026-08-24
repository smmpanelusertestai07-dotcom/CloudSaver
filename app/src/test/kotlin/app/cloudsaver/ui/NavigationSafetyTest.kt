package app.cloudsaver.ui

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
}
