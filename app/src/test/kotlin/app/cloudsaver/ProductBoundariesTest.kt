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
}
