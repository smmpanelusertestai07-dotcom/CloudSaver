package app.cloudsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two promises with no natural home in any other test: what this app will
 * never grow into, and how little it is allowed to interrupt.
 *
 * Both are the kind of thing that erodes by good intentions - one clever
 * feature, one extra notification at a time - so they are stated as tests
 * rather than as a paragraph nobody re-reads.
 */
class ProductBoundariesTest {

    private val main = File("src/main/kotlin/app/cloudsaver")

    private fun code(): List<Pair<String, String>> =
        main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()

    /** Comment lines: where a refusal is allowed to be *named* and explained. */
    private fun withoutComments(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `the refused features never grow back`() {
        // Every one of these was considered and refused on purpose: they need
        // judgement about a photograph that no app should make on someone
        // else's behalf, or they need the internet, or they create second
        // copies in a cloud the owner is paying for.
        val banned = mapOf(
            "similar-photo detection" to Regex("""similarityScore|findSimilar|perceptualHash|pHash\b"""),
            "blur or quality scoring" to Regex("""blurScore|sharpnessScore|qualityScore"""),
            "automatic deletion of originals" to Regex("""autoDeleteOriginals|deleteOriginalsAutomatically"""),
            "cloud recommendations or prices" to Regex("""recommendCloud|pricePerGb|planPrice"""),
            "re-optimise everything" to Regex("""reoptimiseAll|reprocessAll|optimiseEverything""")
        )
        val offenders = mutableListOf<String>()
        for ((name, body) in code()) {
            val live = withoutComments(body)
            for ((feature, pattern) in banned) {
                if (pattern.containsMatchIn(live)) offenders += "$name introduces $feature"
            }
        }
        assertTrue(offenders.joinToString("; "), offenders.isEmpty())
    }

    @Test
    fun `nothing in the app can reach a scheduler that only reminds`() {
        // A background job whose only outcome is a notification is an app
        // nagging its owner. Every scheduled path here does work.
        val scheduler = File(main, "work/Scheduler.kt").readText()
        assertFalse(scheduler.contains("reminder"))
        val workers = File(main, "work").listFiles()!!.filter { it.extension == "kt" }
        assertTrue("the worker set stays small and each one does work", workers.size <= 6)
    }

    @Test
    fun `clearing leftover work files cannot delete work in progress`() {
        // The button is in Storage and in the Free up hub, so it can be
        // pressed while a compression is writing its output. Deleting that
        // file costs the user the item they asked for.
        val storage = File(main, "util/Storage.kt").readText()
        assertTrue(storage.contains("TEMP_ABANDONED_MS"))
        val clean = storage.substringAfter("fun cleanTemp(").substringBefore("\n    }")
        assertTrue(
            "young files must be skipped, not deleted",
            clean.contains("if (now - f.lastModified() < TEMP_ABANDONED_MS) return@forEach")
        )
        // Long enough to be beyond any single run, short enough that a crash
        // is cleaned up on the same day.
        val hours = Regex("""TEMP_ABANDONED_MS = (\d+)L \* 60 \* 1000""")
            .find(storage)!!.groupValues[1].toInt()
        assertTrue("an abandoned file is one no run could still own", hours >= 60)
    }

    @Test
    fun `there are exactly two notification channels, and the retired one is removed`() {
        val notifications = File(main, "util/Notifications.kt").readText()
        val created = Regex("""createNotificationChannel\(""").findAll(notifications).count()
        assertEquals("one channel for work, one for problems - no more", 2, created)
        assertTrue(notifications.contains("CH_WORKING"))
        assertTrue(notifications.contains("CH_ALERTS"))
        assertTrue(
            "an upgrade must not leave a dead channel in system settings",
            notifications.contains("deleteNotificationChannel(CH_LEGACY_WARNINGS)")
        )
        // The working channel must stay silent and badge-free: it is on screen
        // for as long as a run lasts.
        val working = notifications.substringAfter("val working = NotificationChannel")
            .substringBefore("val alerts")
        assertTrue(working.contains("IMPORTANCE_LOW"))
        assertTrue(working.contains("setSound(null, null)"))
        assertTrue(working.contains("enableVibration(false)"))
        assertTrue(working.contains("setShowBadge(false)"))
    }

    @Test
    fun `an alert repeats at most once a day, and mutes for a week`() {
        val notifications = File(main, "util/Notifications.kt").readText()
        assertTrue(notifications.contains("DEDUP_MS = 86_400_000L"))
        assertTrue(notifications.contains("MUTE_MS = 7 * 86_400_000L"))
        assertTrue("every alert must be able to open the screen it is about",
            notifications.contains("EXTRA_ROUTE"))
    }

    @Test
    fun `the app keeps working when notifications are denied`() {
        val notifications = File(main, "util/Notifications.kt").readText()
        // Posting is gated on permission and every post is wrapped, so a
        // refusal is silence - never a crash and never a blocked run.
        assertTrue(notifications.contains("fun canPost"))
        val alert = notifications.substringAfter("fun alert(").substringBefore("private fun post(")
        assertTrue("alerts must check permission before posting", alert.contains("canPost"))
        val post = notifications.substringAfter("private fun post(")
        assertTrue("the post itself checks again", post.contains("if (!canPost(context)) return"))
        assertTrue(
            "and survives permission being revoked between the check and the notify",
            post.contains("catch (e: SecurityException)")
        )
        // The foreground notification is the one exception - it must exist for
        // the service - and it is taken down on every exit path.
        assertTrue(notifications.contains("fun clearWorking"))
        val worker = File(main, "work/CompressWorker.kt").readText()
        assertTrue(
            "the ongoing icon must never outlive the run",
            worker.contains("Notifications.clearWorking")
        )
    }

    /**
     * A figure on one screen may not be a different question from the screen
     * it links to.
     *
     * The Free up space hub summed `reclaimCandidates()` straight - every
     * original with any evidence at all - and printed it as "you could free
     * about X". The Reclaim screen behind that card put the same rows through
     * `ReclaimRules.isEligible`, which refuses anything under thirty days
     * settled, anything while the cloud app is missing or flagged, anything
     * too small, and any favourite. So the card advertised gigabytes and the
     * list opened empty - most visibly in a user's first month, which is
     * everyone at first. Room's own comment shows this class of bug was
     * already fixed once and fixed from the wrong end.
     *
     * `ReclaimEligibility` is now the only caller of the raw query, so the
     * two answers cannot drift apart again.
     */
    @Test
    fun `only the shared gate decides what can be freed`() {
        val allowed = setOf("ReclaimEligibility.kt", "Db.kt")
        val offenders = code()
            .filter { (name, _) -> name !in allowed }
            .filter { (_, text) -> withoutComments(text).contains("reclaimCandidates()") }
            .map { (name, _) -> name }
        assertTrue(
            "these ask the database for raw candidates instead of asking " +
                "ReclaimEligibility what may actually be freed: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `every name that runs the compressor is one runningFlow watches`() {
        // Home hides "Optimise now" while a run is going, because the running
        // one holds the lock and a second would do nothing. That decision is
        // runningFlow's, and it watched two of the three names that start a
        // CompressWorker - so through a run triggered by taking a photo the
        // button was offered and the tap was swallowed.
        //
        // Resolved through the request VARIABLE each call is handed, per
        // function. Nothing coarser works: one function enqueues the
        // compressor and the maintenance pass side by side, and "the text near
        // the call" is fooled by a comment that merely names CompressWorker.
        val src = withoutComments(
            File("src/main/kotlin/app/cloudsaver/work/Scheduler.kt").readText()
        )
        val enqueue = Regex(
            """enqueueUnique(?:Periodic)?Work\(\s*(W_[A-Z_]+)\s*,[^,]+,\s*(\w+)\s*\)"""
        )
        val starters = src.split(Regex("""\n    (?:private )?fun """))
            .flatMap { fn ->
                enqueue.findAll(fn).filter { call ->
                    val request = call.groupValues[2]
                    Regex("""val $request = [^\n]*WorkRequestBuilder<CompressWorker>""")
                        .containsMatchIn(fn)
                }.map { it.groupValues[1] }
            }
            .toSet()
        assertTrue("no compression work found - has Scheduler moved?", starters.size >= 2)

        val watched = src.split(Regex("""\n    (?:private )?fun """))
            .single { it.startsWith("runningFlow(") }
        val unwatched = starters.filterNot { watched.contains(it) }
        assertTrue(
            "these start a compression run but runningFlow cannot see them, " +
                "so Home offers a button the running one will refuse: $unwatched",
            unwatched.isEmpty()
        )
    }

    @Test
    fun `a selection bar counts the same set it sizes`() {
        // Three screens made the same mistake: the count came from the whole
        // selection and the size from the rows the filters happened to be
        // showing. A tick survives a filter change, so narrowing the list left
        // "10 selected" beside the size of six, above a button that acted on
        // six - and on the duplicates screen the same split understated how
        // much was about to be removed, because that removal re-scans
        // everything.
        //
        // Whatever a bar counts, it must size, and its action must reach the
        // same set. `selection.size` beside a `selectedBytes` computed from a
        // filtered list is the shape that keeps coming back.
        val screens = File("src/main/kotlin/app/cloudsaver/ui/screens")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }
        val offenders = mutableListOf<String>()
        for (f in screens) {
            val text = withoutComments(f.readText())
            Regex("""selectionSummary\(\s*([A-Za-z.]+)\s*,\s*Formats\.bytes\((\w+)\)""")
                .findAll(text)
                .forEach { m ->
                    val counted = m.groupValues[1]
                    // The size is derived from some list; find which.
                    val sizeFrom = Regex("""val ${m.groupValues[2]} = remember\((\w+)""")
                        .find(text)?.groupValues?.get(1)
                    // Only the mismatch that misleads: a whole-selection
                    // count beside a size summed over the rows the filters
                    // happen to be showing. Summing the selection over the
                    // FULL list is right - that is what the duplicates dialog
                    // does, because its removal re-scans everything.
                    //
                    // These four names are what a filtered list is called in
                    // this codebase; a new one has to be added here, which is
                    // the point at which someone re-reads this rule.
                    val filtered = setOf("shown", "rows", "chosen", "visible")
                    if (counted == "selection.size" && sizeFrom in filtered) {
                        offenders += "${f.name}: counts $counted but sizes over the filtered $sizeFrom"
                    }
                }
        }
        assertTrue(
            "a bar must count the set it sizes and acts on: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `a modified build cannot remove anything`() {
        // TamperCheck's contract says a mismatched signing certificate
        // "disables the Free-up tool and all deletions", and the banner on Home
        // tells the user deleting is turned off and stays off. Neither was
        // true. The flag reached a banner and one hidden button; every path
        // that removes a file ran exactly as before - originals, duplicate
        // extras, restores from the trash, leftover work files.
        //
        // This matters more than an unkept promise. That banner appears when
        // the signing certificate does not match, which is what an APK signed
        // with someone else's key looks like - and the private key really was
        // published for eleven releases. The one moment the check exists for is
        // the one where it did nothing.
        val vms = File("src/main/kotlin/app/cloudsaver/ui")
        val entryPoints = mapOf(
            "ReclaimViewModel.kt" to listOf(
                "fun start(permanent: Boolean) {",
                "fun removeDuplicateExtras(chosen: Set<Long>) {",
                "fun restore(items: List<ReclaimItemRow>) {"
            ),
            "AppViewModel.kt" to listOf(
                "fun requestDelete(uris: List<Uri>, onDone: (List<Uri>) -> Unit): IntentSender? {"
            )
        )
        val ungated = mutableListOf<String>()
        for ((file, signatures) in entryPoints) {
            val text = File(vms, file).readText()
            for (sig in signatures) {
                val body = text.substringAfter(sig, "")
                assertTrue("$file no longer has `$sig`", body.isNotEmpty())
                // The refusal has to be the first thing the function does, not
                // a check somewhere after the work has started.
                if (!body.take(700).contains("TamperCheck.isModified")) {
                    ungated += "$file: ${sig.substringBefore('(')}"
                }
            }
        }
        assertTrue(
            "these remove files without checking the signature first, while the " +
                "app tells the user deleting is turned off: $ungated",
            ungated.isEmpty()
        )
    }

    @Test
    fun `the maintenance pass runs one at a time`() {
        // Locks exists because "WorkManager's unique names stop two workers
        // racing, but the UI can start a trial run, an Optimise now, or a
        // removal while a scheduled run is mid-way" - its own words. The
        // maintenance pass took none of them, and TWO paths start it: the
        // hourly MaintainWorker, and CompressWorker at the end of every
        // compression run. Different unique names, so nothing serialised them,
        // with the UI able to ask for a confirm pass on top.
        //
        // Two passes over the same rows is how self-heal reverts an item
        // another pass has just released - back to NEW, staged file forgotten -
        // and the cloud receives it a second time.
        val engine = File("src/main/kotlin/app/cloudsaver/engine/MaintainEngine.kt").readText()
        val entries = listOf("run()", "confirmPass()", "snapshotNow()")
        val ungated = entries.filterNot { entry ->
            Regex(
                """suspend fun ${Regex.escape(entry.dropLast(2))}\([^)]*\)[^\n]*""" +
                    """Locks\.maintain\.withLock"""
            ).containsMatchIn(engine)
        }
        assertTrue(
            "these start a maintenance pass without taking Locks.maintain, so " +
                "two passes can write the same rows: $ungated",
            ungated.isEmpty()
        )
        // And it must not take the other three, or holding this one could
        // deadlock against a release or a removal already in flight.
        val body = engine.substringAfter("private suspend fun runLocked")
        for (other in listOf("Locks.release", "Locks.reclaim", "Locks.ledger")) {
            assertFalse(
                "the maintenance pass must not also take $other while holding " +
                    "Locks.maintain - that is a deadlock waiting for a busy phone",
                body.contains("$other.withLock")
            )
        }
    }

    /**
     * A result the user is waiting for has to reach them.
     *
     * "Export the list" wrote a CSV, set a done-or-failed message, and that
     * was the end of it: nothing in the app read the property, so the button
     * did its work in silence either way. Someone who picked a folder the
     * write could not reach was left believing they had the list of the
     * photographs they were about to remove.
     */
    @Test
    fun `every message a view model sets is read by a screen`() {
        val screens = File("src/main/kotlin/app/cloudsaver/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.name.endsWith("ViewModel.kt") }
            .joinToString("\n") { withoutComments(it.readText()) }
        val unread = mutableListOf<String>()
        for (file in File("src/main/kotlin/app/cloudsaver/ui")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith("ViewModel.kt") }) {
            val declared = Regex("""val\s+(\w*[Mm]essage)\s*=\s*MutableStateFlow""")
                .findAll(withoutComments(file.readText()))
                .map { it.groupValues[1] }
            for (name in declared) {
                if (!screens.contains(".$name")) unread += "${file.name}: $name"
            }
        }
        assertTrue(
            "these are set for the user and never shown to them, so the action " +
                "that set one succeeds and fails in exactly the same silence: $unread",
            unread.isEmpty()
        )
    }
}
