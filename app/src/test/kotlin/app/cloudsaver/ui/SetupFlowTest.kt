package app.cloudsaver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first ten minutes of owning this app, held to what the screens promise.
 *
 * Every rule here comes from a real run on a real phone: a card headed "1."
 * under a header reading "Step 2 of 8", a restore button nobody could explain,
 * a trial that refused to run during setup, a correction that threw the user
 * five steps back, and a ticked album that came back unticked after a restart.
 */
class SetupFlowTest {

    private val strings = File("src/main/res/values/strings.xml").readText()
    private fun src(path: String) = File("src/main/kotlin/app/cloudsaver/$path").readText()

    private val entry = Regex(
        """<string name="([^"]+)"[^>]*>(.*?)</string>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun stringsNamed(prefix: String): List<Pair<String, String>> =
        entry.findAll(strings)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first.startsWith(prefix) }
            .toList()

    @Test
    fun `no card counts the steps - the stepper already does`() {
        // "Step 2 of 8" over a card headed "1. Media permission" is two
        // counters disagreeing in the same glance. One counter, in one place.
        val numbered = Regex("""^\s*\d+\s*[.)]\s""")
        val offenders = stringsNamed("onb")
            .filter { (name, body) -> name.endsWith("_title") && numbered.containsMatchIn(body) }
            .map { it.first }
        assertTrue("these setup titles number themselves: $offenders", offenders.isEmpty())
    }

    @Test
    fun `restoring says what it is for, and a wrong file says what to pick`() {
        assertTrue(strings.contains("name=\"onb0_import_hint\""))
        val hint = stringsNamed("onb0_import_hint").single().second
        assertTrue("the hint must say who it is for", hint.contains("Only if"))
        assertTrue("the hint must promise photos are untouched", hint.contains("never touches your photos"))
        assertTrue(
            "the welcome step must render the hint beside the button",
            src("ui/screens/OnboardingScreen.kt").contains("onb0_import_hint")
        )
        val failed = stringsNamed("transfer_failed").single().second
        assertTrue("a wrong file must say nothing changed", failed.contains("nothing was changed"))
        assertTrue("and name what to pick instead", failed.contains("Backup and restore"))
    }

    @Test
    fun `a correction from the summary returns to the summary`() {
        val onb = src("ui/screens/OnboardingScreen.kt")
        assertTrue(onb.contains("returnToSummary"))
        // Both ways into the album list from the last step set the return,
        // and the album step honours it instead of walking on.
        assertTrue(
            "the no-albums warning must remember where it came from",
            onb.contains("returnToSummary = true; go(Step.ALBUMS)")
        )
        val albums = onb.substringAfter("Step.ALBUMS ->").substringBefore("Step.NOTIFICATIONS ->")
        assertTrue(albums.contains("if (returnToSummary)"))
        assertTrue(albums.contains("go(Step.READY)"))
        // And the promise dies with the detour. Stepping Back out of the album
        // list, or reaching it on the ordinary forward path, must cancel it -
        // otherwise the next confirm would jump a first-time user to the
        // summary over notifications, battery, usage access and the cloud step.
        assertTrue(onb.contains("fun leaveDetour()"))
        assertTrue(
            "Back must cancel the detour",
            onb.contains("leaveDetour(); goTo(step - 1)")
        )
        assertTrue(
            "so must arriving from the permission step",
            onb.contains("leaveDetour(); go(Step.ALBUMS)")
        )
    }

    @Test
    fun `the trial is offered on a chosen album, not on a scan that has not run`() {
        val trial = src("ui/components/TrialCard.kt")
        assertTrue(trial.contains("albumsChosen"))
        assertTrue(trial.contains("trial_needs_albums"))
        assertTrue(trial.contains("trial_ready"))
        // The old rule hid the button whenever nothing was queued, which is
        // every phone during setup, because the scan runs inside the trial.
        assertFalse(trial.contains("if (size > 0) {\n            OutlinedButton"))
        val vm = src("ui/AppViewModel.kt")
        val run = vm.substringAfter("fun startTestRun").substringBefore("// ---- Free-up")
        assertTrue("the trial must scan before it picks", run.contains("MediaScanner(ctx, db).scan()"))
        // Setup and Home ask the same question of the same card.
        assertTrue(src("ui/screens/OnboardingScreen.kt").contains("albumsChosen = includedAlbums > 0"))
        val home = src("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("albumsChosen ="))
        assertTrue(home.contains("onChooseAlbums ="))
        assertFalse(
            "Home must not hide the card just because nothing is queued yet",
            home.contains("processed == 0 && trialSize > 0")
        )
    }

    @Test
    fun `a snapshot restores once per install, and never over a live choice`() {
        val recovery = src("engine/StartupRecovery.kt")
        assertTrue(recovery.contains("if (o.restoreDone) return 0"))
        assertTrue(recovery.contains("RESTORE_DONE"))
        // The shipped bug: "restore whenever the item table is empty" was true
        // on every launch until the first file was optimised, and each launch
        // re-imported the snapshot's settings - a ticked album came back
        // unticked after every restart.
        assertTrue(recovery.contains("importOptions = untouched"))
        assertTrue(recovery.contains("!o.onboardingDone && o.onboardingStep == 0"))
        val store = src("engine/SnapshotStore.kt")
        assertTrue(store.contains("importOptions && snapshot.options.isNotEmpty()"))
        // A reinstall without gallery access must not skip the one screen
        // that asks for it.
        assertTrue(recovery.contains("MediaAccess.FULL"))
    }

    @Test
    fun `the app lock covers the whole app`() {
        val app = src("ui/App.kt")
        assertTrue(app.contains("val needsLock = options.appLock && !unlocked"))
        assertFalse(
            "a lock that covers only some routes is walked around by a tab tap",
            app.contains("route in Routes.LOCKED")
        )
        assertFalse("the route list it used should be gone", app.contains("val LOCKED = setOf"))
        assertTrue("the tab bar must not stay live under the lock", app.contains("if (!needsLock &&"))
        // Still re-armed every time the app leaves the foreground.
        assertTrue(app.contains("Lifecycle.Event.ON_STOP) { unlocked = false }"))
        val locked = src("ui/screens/LockedScreen.kt")
        assertTrue("the prompt opens itself", locked.contains("LaunchedEffect(Unit) { onUnlock() }"))
        assertTrue("and the screen is not screenshotable", locked.contains("SecureScreen()"))
    }

    @Test
    fun `an alert tapped on a locked phone waits instead of crashing`() {
        val app = src("ui/App.kt")
        val effect = app.substringAfter("LaunchedEffect(deepLink").substringBefore("Scaffold(")
        // While the app is locked there is no NavHost in composition, so the
        // controller has no graph and navigating into it throws.
        assertTrue(
            "the deep link must re-evaluate when the lock opens",
            app.contains("LaunchedEffect(deepLink, needsLock)")
        )
        assertTrue(
            "and must not navigate while the gate is shut",
            effect.contains("if (needsLock) return@LaunchedEffect")
        )
        val clearAt = effect.indexOf("clearDeepLink")
        val guardAt = effect.indexOf("if (needsLock)")
        assertTrue(
            "the link must not be consumed before it is honoured",
            guardAt in 0 until clearAt
        )
    }

    @Test
    fun `the gallery is only enumerated where its answer is used`() {
        val home = src("ui/screens/HomeScreen.kt")
        val startup = home.substringAfter("LaunchedEffect(Unit) {").substringBefore("}")
        assertFalse(
            "reading the album list walks the whole gallery; it must not run " +
                "on every visit to Home",
            startup.contains("loadBuckets")
        )
        assertTrue(
            "it belongs with the one card that reads it",
            home.substringAfter("if (processed == 0) {").substringBefore("TrialCard(")
                .contains("vm.loadBuckets()")
        )
    }

    @Test
    fun `About states the requirement plainly, with nothing hidden behind Advanced`() {
        val help = src("ui/screens/HelpScreens.kt")
        assertTrue(help.contains("about_requires_min"))
        assertTrue(help.contains("about_tech_title"))
        assertTrue(help.contains("about_permissions_title"))
        assertFalse("the Advanced expander is gone", help.contains("about_advanced"))
        val requires = stringsNamed("about_requires_min").single().second
        assertTrue("the minimum must be named", requires.contains("Android 10"))
        assertTrue("and what it is tested against", requires.contains("Android 16"))
    }
}
