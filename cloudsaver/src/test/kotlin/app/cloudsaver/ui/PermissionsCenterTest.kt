package app.cloudsaver.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "Permissions.alertsChannelOff(context)",
            "UsageVerifier.hasUsageAccess(context)",
            "Permissions.isIgnoringBatteryOptimizations(context)",
            "Permissions.isBackgroundRestricted(context)",
            "Permissions.permissionsAutoResetOn(context)",
            "Permissions.batterySaverOn(context)",
            "Permissions.standbyBucket(context)",
            "Exits.last(context)",
            "PermissionLedger.read(context)"
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

    @Test
    fun `notifications are judged by the system switch, not only the permission`() {
        // Android 13 added a runtime permission; the switch in system settings that silences
        // an app is on every version. Reading the permission alone said "Allowed" on an
        // Android 11 phone whose owner had switched this app off. The sister project in this
        // repository shipped the identical mistake, which is how it was found here.
        val fn = File(main, "util/Permissions.kt").readText()
            .substringAfter("fun hasNotifications(").substringBefore("\n    }\n")
        assertTrue("the system switch must be read", fn.contains("areNotificationsEnabled()"))
        assertTrue("and the runtime permission still gates 13 and up", fn.contains("POST_NOTIFICATIONS"))
        val row = File(main, "ui/screens/OptionsScreen.kt").readText()
            .substringAfter("private fun AlertsPermissionRow(").substringBefore("\n}\n")
        assertFalse("the alerts row must not fall silent below Android 13", row.contains("SDK_INT < 33) return"))
        assertTrue("below 13 the only way to the switch is the settings page", row.contains("openNotificationSettings(context)"))
    }

    @Test
    fun `the switches Android does report are read and judged, not guessed`() {
        // Three of them were missing: the per-app Restricted ban, the
        // unused-app permission reset, and the phone-wide Battery Saver. Each
        // stops the work without a word, and each has a public API.
        val perms = File(main, "util/Permissions.kt").readText()
        assertTrue(perms.contains("am.isBackgroundRestricted"))
        assertTrue(perms.contains("!context.packageManager.isAutoRevokeWhitelisted"))
        assertTrue(perms.contains("pm.isPowerSaveMode"))
        assertTrue(perms.contains("usm.appStandbyBucket"))
        assertTrue(screen.contains("state = if (backgroundRestricted) State.PROBLEM else State.OK"))
        assertTrue(screen.contains("state = if (autoReset) State.PROBLEM else State.OK"))
        assertTrue(screen.contains("state = if (saver) State.PROBLEM else State.OK"))
        // The reset does not exist on Android 10: the row is absent there, never "Off".
        assertTrue(perms.contains("if (Build.VERSION.SDK_INT < 30) return null"))
        assertTrue(screen.contains("if (autoReset != null) {"))
    }

    @Test
    fun `the readable switches reach setup, Home and the Settings dot as well`() {
        val power = File(main, "util/PowerPages.kt").readText()
        val fn = power.substringAfter("fun requirementsFor(").substringBefore("\n    }\n")
        assertTrue(fn.contains("Requirement(ID_BACKGROUND_RESTRICTION, readable = true, satisfied = !backgroundRestricted)"))
        assertTrue(fn.contains("Requirement(ID_KEEP_PERMISSIONS, readable = true, satisfied = !it)"))
        val open = power.substringAfter("fun open(context: Context, requirementId: String)")
        assertTrue(open.contains("ID_BACKGROUND_RESTRICTION -> openBackgroundActivity(context)"))
        assertTrue(open.contains("ID_KEEP_PERMISSIONS -> OemPages.openAutoRevokeSettings(context)"))
        val vm = File(main, "ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("backgroundRestricted = Permissions.isBackgroundRestricted(ctx)"))
        assertTrue(vm.contains("permissionsAutoReset = Permissions.permissionsAutoResetOn(ctx)"))
        val home = File(main, "ui/screens/HomeScreen.kt").readText()
        assertTrue(home.contains("PowerPages.ID_BACKGROUND_RESTRICTION ->"))
        assertTrue(home.contains("PowerPages.ID_KEEP_PERMISSIONS ->"))
        val app = File(main, "ui/App.kt").readText()
        assertTrue(app.contains("phoneWillStopIt = health.backgroundRestricted ||"))
        val onboarding = File(main, "ui/screens/OnboardingScreen.kt").readText()
        assertTrue(onboarding.contains("PowerPages.ID_BACKGROUND_RESTRICTION -> stringResource(R.string.power_background_restriction)"))
        assertTrue(onboarding.contains("PowerPages.ID_KEEP_PERMISSIONS -> stringResource(R.string.power_keep_permissions)"))
    }

    @Test
    fun `the alerts category has its own state and its own page`() {
        // Notifications on with the Alerts category off is "Allowed" by the
        // app-level switch and silence in practice.
        assertTrue(screen.contains("alertsChannelOff -> R.string.perm_alerts_channel_off"))
        assertTrue(screen.contains("notifications && alertsChannelOff -> OemPages.openAlertsChannelSettings(context)"))
        val oem = File(main, "util/OemPages.kt").readText()
        assertTrue(oem.contains("Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS"))
        assertTrue(oem.contains("Settings.EXTRA_CHANNEL_ID, Notifications.CH_ALERTS"))
        // And the reset page is Android's own, with app info as the floor.
        assertTrue(oem.contains("Intent.ACTION_AUTO_REVOKE_PERMISSIONS"))
    }

    @Test
    fun `a row with nowhere to go is a fact, not a task`() {
        // The rationing bucket and the last exit have no switch. They are
        // shown without a button rather than with one that opens app info
        // and changes nothing.
        val row = screen.substringAfter("private fun PermissionRow(").substringBefore("\n}\n")
        assertTrue(row.contains("actionLabel: String? = null"))
        assertTrue(row.contains("if (actionLabel != null && onAction != null) {"))
        val bucket = screen.substringAfter("if (bucket != null) {").substringBefore("for (requirement in makerRows)")
        assertFalse(bucket.contains("actionLabel"))
        val exit = screen.substringAfter("if (lastExit != null) {").substringBefore("stringResource(R.string.perm_why)")
        assertFalse(exit.contains("actionLabel"))
    }

    @Test
    fun `the stall alert and the stopped chip share one rule`() {
        // Two copies of "the phone stopped the work" would disagree one day.
        val vm = File(main, "ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("backgroundWorkStopped = !o.pauseAll && StallAlert.stalled("))
        val engine = File(main, "engine/MaintainEngine.kt").readText()
        assertTrue(engine.contains("StallAlert.stalled("))
        assertTrue(engine.contains("StallAlert.due(stalled, o.stallAlerts, o.stallAlertAt, now)"))
        assertTrue(engine.contains("route = \"permissions\""))
        // A week apart, not the daily cadence the other alerts keep.
        assertTrue(engine.contains("repo.setInt(OptionsRepo.K.STALL_ALERTS, o.stallAlerts + 1)"))
        assertTrue(engine.contains("repo.setLong(OptionsRepo.K.STALL_ALERT_AT, now)"))
    }
}
