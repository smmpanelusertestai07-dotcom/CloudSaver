package app.cloudsaver.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One screen that says what the app has been allowed to do, and re-reads it
 * every time the person comes back.
 *
 * Before it existed, the answer to "why did it stop overnight?" was spread
 * over setup step five, a chip on Home and a paragraph in Settings, and
 * none of them changed until the next tap. The screen reads every state the
 * platform can report, says plainly which ones it cannot, and every road
 * that used to end in "check your settings" now ends here.
 */
class PermissionsCenterTest {

    private val main = File("src/main/kotlin/app/cloudsaver")
    private val screen = File(main, "ui/screens/PermissionsScreen.kt").readText()

    @Test
    fun `the screen is a route and both roads to it are wired`() {
        val app = File(main, "ui/App.kt").readText()
        assertTrue(app.contains("const val PERMISSIONS = \"permissions\""))
        assertTrue(app.contains("composable(Routes.PERMISSIONS) { PermissionsScreen(vm, nav) }"))
        val all = app.substringAfter("val ALL: Set<String> = setOf(").substringBefore(")")
        assertTrue("the route must be in the set the navigation guard allows", all.contains("PERMISSIONS"))
        val home = File(main, "ui/screens/HomeScreen.kt").readText()
        assertTrue("the stopped chip on Home leads here", home.contains("nav.goTo(Routes.PERMISSIONS)"))
        val options = File(main, "ui/screens/OptionsScreen.kt").readText()
        assertTrue("Settings leads here", options.contains("nav.goTo(Routes.PERMISSIONS)"))
    }

    @Test
    fun `every state is re-read when the person comes back from the system page`() {
        // The whole point of the screen is that a switch flipped in Settings
        // shows as Allowed the moment the person returns. Each state is
        // keyed on the resume tick, so none of them can go stale.
        assertTrue(screen.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)"))
        for (read in listOf(
            "Permissions.mediaAccess(context)",
            "Permissions.hasNotifications(context)",
            "UsageVerifier.hasUsageAccess(context)",
            "Permissions.isIgnoringBatteryOptimizations(context)"
        )) {
            assertTrue("$read must be re-read on resume", screen.contains("remember(tick) { $read }"))
        }
        // The view model's own battery rows follow the same rule, so the
        // Home chip agrees with the screen.
        val vm = File(main, "ui/AppViewModel.kt").readText()
            .substringAfter("fun onResumed()").substringBefore("\n    }\n")
        assertTrue(vm.contains("refreshPowerRequirements()"))
    }

    @Test
    fun `a switch Android cannot read is never shown as off`() {
        // Claiming "Off" for a setting the platform cannot report trains
        // people to ignore the app. The maker's own switches get a third
        // state and the path to the switch, never a red mark.
        val makerRows = screen.substringAfter("for (requirement in makerRows)").substringBefore("\n            }\n")
        assertTrue(makerRows.contains("state = State.UNKNOWN"))
        assertTrue(makerRows.contains("status = stringResource(R.string.perm_unknown)"))
        assertTrue(makerRows.contains("detail = PowerPages.pathHint(vendor, requirement.id)"))
        // And the readable ones are judged, not hedged.
        assertTrue(screen.contains("state = if (battery) State.OK else State.PROBLEM"))
        assertTrue(screen.contains("state = if (usage) State.OK else State.PROBLEM"))
    }

    @Test
    fun `the battery row still has somewhere to go once Android's own switch is set`() {
        // Android closes the "ignore optimisations" dialog silently when the
        // app is already exempt, so on the one phone this row was written for
        // the tap did nothing. Already exempt, it opens the maker's page.
        val power = File(main, "util/PowerPages.kt").readText()
            .substringAfter("fun open(context: Context, requirementId: String)")
        val branch = power.substringAfter("ID_BATTERY_UNRESTRICTED ->").substringBefore("ID_AUTO_LAUNCH ->")
        assertTrue(branch.contains("if (Permissions.isIgnoringBatteryOptimizations(context))"))
        assertTrue(branch.contains("openBackgroundActivity(context)"))
        assertTrue(branch.contains("OemPages.requestIgnoreBatteryOptimizations(context)"))
        // And the request itself never dead-ends: settings page, then app info.
        val oem = File(main, "util/OemPages.kt").readText()
            .substringAfter("fun requestIgnoreBatteryOptimizations(")
        assertTrue(oem.contains("Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"))
        assertEquals(
            "the per-app page is offered before the whole list",
            true,
            oem.indexOf("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") <
                oem.indexOf("ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
        )
    }
}
