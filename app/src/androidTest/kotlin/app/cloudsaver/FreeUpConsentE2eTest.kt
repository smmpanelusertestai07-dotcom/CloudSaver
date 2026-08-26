package app.cloudsaver

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.ReclaimRules
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.db.LedgerRow
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.util.Formats
import app.cloudsaver.util.Storage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * The removal path, end to end, through Android's own consent dialog.
 *
 * This is the one part of CloudSaver that can destroy a photograph, so nothing
 * here is faked: real files go into MediaStore, the real screens are driven,
 * and the confirmation that decides whether a file lives or dies is the system
 * dialog MediaProvider puts up - located and tapped with UiAutomator.
 *
 * Locating that dialog: MediaProvider's PermissionActivity builds a plain
 * framework `android.app.AlertDialog` with `setPositiveButton(R.string.allow)`
 * and `setNegativeButton(R.string.deny)`, so the two buttons carry the
 * framework ids `android:id/button1` and `android:id/button2` whatever the
 * device's language is. Those ids are what this suite clicks; the English
 * labels are only a last-resort fallback for a skinned build, and if neither
 * is found the test fails rather than passing quietly.
 *
 * Everything is guarded on API 30+: Android 10 has neither a batch request nor
 * a media trash, and the app deliberately takes a different path there.
 *
 * Eligibility is built in the database rather than by ageing real files.
 * ReclaimRules.refuse wants: the original present, a state of RELEASED/GONE/
 * DONE, a healthy cloud, a ledger row whose hash matches the recorded copy, a
 * per-file evidence grade, thirty days since the confirmation and since the
 * file was added, not a favourite, and at least ReclaimRules.MIN_SIZE_BYTES.
 * [seedBackedUpOriginals] constructs exactly that.
 */
@RunWith(AndroidJUnit4::class)
class FreeUpConsentE2eTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val db get() = AppDb.get(target)

    /** One seeded original: the real gallery file plus the row that describes it. */
    private data class Seeded(val name: String, val uri: Uri, val fingerprint: String)

    private companion object {
        /**
         * MediaProvider's consent sheet is a framework AlertDialog, so its
         * buttons are the framework's own ids - stable across locales and
         * across the trash, delete and write verbs alike.
         */
        const val POSITIVE_ID = "android:id/button1"
        const val NEGATIVE_ID = "android:id/button2"

        /** Only used when a skinned build hides the framework ids. */
        val POSITIVE_TEXT: Pattern = Pattern.compile("(?i)^\\s*(allow|allow all)\\s*$")
        val NEGATIVE_TEXT: Pattern =
            Pattern.compile("(?i)^\\s*(deny|don.t allow|do not allow|cancel)\\s*$")

        const val DIALOG_TIMEOUT = 30_000L
        const val FALLBACK_TIMEOUT = 3_000L
        const val UI_TIMEOUT = 30_000L

        /** Comfortably over ReclaimRules.MIN_SIZE_BYTES, and easy to add up. */
        const val ROW_BYTES = 3L * 1024 * 1024

        /** MediaProvider derives DATE_TAKEN from the fixture's own EXIF stamp. */
        const val FIXTURE_TAKEN_MS = 1_600_000_000_000L
    }

    private fun s(id: Int): String = target.getString(id)
    private fun s(id: Int, vararg args: Any): String = target.getString(id, *args)
    private fun plural(id: Int, quantity: Int, vararg args: Any): String =
        target.resources.getQuantityString(id, quantity, *args)

    @Before
    fun setUp() {
        purgeTestAlbum()
        MediaFixtures.cleanUp(target)
        runBlocking {
            db.clearAllTables()
            val repo = OptionsRepo.get(target)
            repo.setBool(OptionsRepo.K.ONBOARDING_DONE, true)
            repo.setBool(OptionsRepo.K.APP_LOCK, false)
            // The gate re-checks cloud health at the moment of action, and
            // "Other app" is the one choice that needs no installed package -
            // no emulator has Ente or MEGA on it.
            repo.setString(OptionsRepo.K.CLOUD_SINGLE, "other")
            repo.setString(OptionsRepo.K.CLOUD_PROBLEM, "")
            // The "I understand" tick is a one-off acknowledgement, not part of
            // what this suite is testing; its absence is asserted instead.
            repo.setBool(OptionsRepo.K.RECLAIM_UNDERSTOOD, true)
        }
        Storage.tempDir(target).listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        purgeTestAlbum()
        MediaFixtures.cleanUp(target)
        runBlocking { db.clearAllTables() }
        Storage.tempDir(target).listFiles()?.forEach { it.delete() }
    }

    // ---- the four consent outcomes -------------------------------------------

    /**
     * Refusing Android's dialog must leave every original exactly where it was,
     * and must write nothing down as freed.
     */
    @Test
    @RequiresApi(Build.VERSION_CODES.R)
    fun refusingTheSystemDialogKeepsEveryOriginalAndRecordsNothing() {
        assumeTrue("no batch consent dialog before API 30", Build.VERSION.SDK_INT >= 30)
        val seeds = seedBackedUpOriginals(3, "refuse")

        ActivityScenario.launch(MainActivity::class.java).use {
            openBackedUpOriginals()
            chooseFreeUpFullyAndSelectEverything(seeds.size)
            tapMoveToTrashAndContinue()

            answerSystemConsent(allow = false)
            awaitSystemConsentGone()
            awaitText(R.string.reclaim_done_title)

            // The app must say plainly that nothing went.
            compose.onNodeWithText(
                s(R.string.reclaim_done_body, Formats.bytes(0), Formats.count(0))
            ).assertIsDisplayed()
            compose.onNodeWithText(
                plural(R.plurals.reclaim_done_skipped, seeds.size, seeds.size)
            ).assertIsDisplayed()
        }

        for (seed in seeds) {
            assertTrue("${seed.name} must still be in the gallery", isVisible(seed.uri))
            assertFalse("${seed.name} must not be in the trash", isTrashed(seed.uri))
        }
        assertEquals(seeds.size, countInAlbum(MediaStore.MATCH_EXCLUDE))
        assertEquals(0, countInAlbum(MediaStore.MATCH_ONLY))

        runBlocking {
            assertTrue(
                "a refused batch must leave no history behind",
                db.reclaim().recentBatchesFlow(50).first().isEmpty()
            )
            for (seed in seeds) {
                val row = db.items().byFingerprint(seed.fingerprint)
                assertNotNull("${seed.name} row must survive", row)
                assertEquals(ItemState.DONE.name, row!!.state)
                assertFalse("${seed.name} must not be marked gone", row.originalMissing)
            }
        }
    }

    /**
     * Accepting removes exactly the confirmed files - to the trash, not out of
     * existence - and records every one of them.
     */
    @Test
    @RequiresApi(Build.VERSION_CODES.R)
    fun acceptingTheSystemDialogRemovesAndRecordsExactlyThoseFiles() {
        assumeTrue("no batch consent dialog before API 30", Build.VERSION.SDK_INT >= 30)
        val seeds = seedBackedUpOriginals(3, "accept")
        val expectedFreed = ROW_BYTES * seeds.size

        ActivityScenario.launch(MainActivity::class.java).use {
            openBackedUpOriginals()
            chooseFreeUpFullyAndSelectEverything(seeds.size)
            tapMoveToTrashAndContinue()

            answerSystemConsent(allow = true)
            awaitSystemConsentGone()
            awaitText(R.string.reclaim_done_title)

            compose.onNodeWithText(
                s(
                    R.string.reclaim_done_body,
                    Formats.bytes(expectedFreed),
                    Formats.count(seeds.size)
                )
            ).assertIsDisplayed()
            // Trash, not deletion: the sentence that promises thirty days.
            compose.onNodeWithText(s(R.string.reclaim_done_trash)).assertIsDisplayed()
        }

        for (seed in seeds) {
            assertFalse("${seed.name} must have left the gallery", isVisible(seed.uri))
            assertTrue("${seed.name} must be in the trash, not erased", isTrashed(seed.uri))
        }
        assertEquals(0, countInAlbum(MediaStore.MATCH_EXCLUDE))
        assertEquals(seeds.size, countInAlbum(MediaStore.MATCH_ONLY))

        runBlocking {
            val batches = db.reclaim().recentBatchesFlow(50).first()
            assertEquals("exactly one batch must be recorded", 1, batches.size)
            val batch = batches.first()
            assertEquals(ReclaimRules.Mode.FREE_UP_FULLY.name, batch.mode)
            assertEquals(seeds.size, batch.itemCount)
            assertEquals(expectedFreed, batch.freedBytes)
            assertTrue("the batch went to the trash", batch.trashed)

            val recorded = db.reclaim().itemsOf(batch.id)
            assertEquals(seeds.size, recorded.size)
            assertEquals(
                seeds.map { it.uri.toString() }.toSet(),
                recorded.mapNotNull { it.contentUri }.toSet()
            )

            for (seed in seeds) {
                val row = db.items().byFingerprint(seed.fingerprint)!!
                assertEquals(ItemState.FREED.name, row.state)
                assertTrue("${seed.name} must be marked gone", row.originalMissing)
            }
        }
    }

    /**
     * A selection larger than ReclaimRules.MAX_URIS_PER_REQUEST is confirmed in
     * more than one dialog, and refusing the second keeps what the first agreed
     * to and nothing beyond it.
     *
     * If the app ever went back to one dialog for the whole selection, the
     * single "allow" would take all 501 files and the counts below would say so.
     */
    @Test
    @RequiresApi(Build.VERSION_CODES.R)
    fun aBatchOverTheRequestLimitIsConfirmedInMoreThanOneDialog() {
        assumeTrue("no batch consent dialog before API 30", Build.VERSION.SDK_INT >= 30)
        val chunk = ReclaimRules.MAX_URIS_PER_REQUEST
        val seeds = seedBackedUpOriginals(chunk + 1, "chunk")

        ActivityScenario.launch(MainActivity::class.java).use {
            openBackedUpOriginals()
            chooseFreeUpFullyAndSelectEverything(seeds.size)
            tapMoveToTrashAndContinue()

            // First dialog: allow. It must carry a whole chunk, no more.
            answerSystemConsent(allow = true)
            compose.waitUntil(timeoutMillis = 180_000) {
                countInAlbum(MediaStore.MATCH_ONLY) >= chunk
            }
            assertEquals(
                "the first dialog must cover exactly one chunk",
                chunk,
                countInAlbum(MediaStore.MATCH_ONLY)
            )

            // Second dialog: the app has to ask again for the remainder.
            answerSystemConsent(allow = false)
            awaitSystemConsentGone()
            awaitText(R.string.reclaim_done_title)
        }

        assertEquals(
            "the refused remainder must still be in the gallery",
            1,
            countInAlbum(MediaStore.MATCH_EXCLUDE)
        )
        assertEquals(chunk, countInAlbum(MediaStore.MATCH_ONLY))

        val survivors = seeds.filter { isVisible(it.uri) }
        assertEquals("exactly one original was refused", 1, survivors.size)

        runBlocking {
            val batches = db.reclaim().recentBatchesFlow(50).first()
            assertEquals(1, batches.size)
            val batch = batches.first()
            assertEquals(
                "only what the first dialog agreed to may be recorded",
                chunk,
                batch.itemCount
            )
            assertEquals(ROW_BYTES * chunk, batch.freedBytes)

            val recorded = db.reclaim().itemsOf(batch.id)
            assertEquals(chunk, recorded.size)
            val recordedUris = recorded.mapNotNull { it.contentUri }.toSet()
            assertFalse(
                "the refused original must not appear in the history",
                survivors.first().uri.toString() in recordedUris
            )
            for (uri in recordedUris) {
                assertTrue("recorded $uri must really be in the trash", isTrashed(Uri.parse(uri)))
            }
            val survivorRow = db.items().byFingerprint(survivors.first().fingerprint)!!
            assertEquals(ItemState.DONE.name, survivorRow.state)
            assertFalse(survivorRow.originalMissing)
        }
    }

    /**
     * Restore is the same walk in reverse: it asks, and only what the user
     * allows back actually comes back - and the history says so.
     */
    @Test
    @RequiresApi(Build.VERSION_CODES.R)
    fun restoreFromHistoryPutsBackOnlyWhatTheUserAllows() {
        assumeTrue("no media trash before API 30", Build.VERSION.SDK_INT >= 30)
        val seeds = seedBackedUpOriginals(2, "restore")

        ActivityScenario.launch(MainActivity::class.java).use {
            openBackedUpOriginals()
            chooseFreeUpFullyAndSelectEverything(seeds.size)
            tapMoveToTrashAndContinue()
            answerSystemConsent(allow = true)
            awaitSystemConsentGone()
            awaitText(R.string.reclaim_done_title)

            for (seed in seeds) {
                assertTrue("${seed.name} must be in the trash first", isTrashed(seed.uri))
            }

            // "See history" is the dialog's second button.
            compose.onNodeWithText(s(R.string.reclaim_history_open)).performClick()
            awaitText(R.string.reclaim_history)

            val line = plural(
                R.plurals.history_line, seeds.size, seeds.size,
                Formats.bytes(ROW_BYTES * seeds.size)
            )
            awaitText(line)
            compose.onNodeWithText(line).performScrollTo().performClick()
            awaitText(R.string.history_restore)

            // Refused: nothing may come back, and nothing may be marked as if
            // it had.
            compose.onNodeWithText(s(R.string.history_restore)).performScrollTo().performClick()
            answerSystemConsent(allow = false)
            awaitSystemConsentGone()
            compose.waitForIdle()

            for (seed in seeds) {
                assertFalse("${seed.name} must stay in the trash", isVisible(seed.uri))
                assertTrue(isTrashed(seed.uri))
            }
            runBlocking {
                val batch = db.reclaim().recentBatchesFlow(50).first().first()
                for (item in db.reclaim().itemsOf(batch.id)) {
                    assertNull(
                        "${item.displayName} must not be recorded as restored",
                        item.restoredAt
                    )
                }
            }

            // Allowed: everything comes back, and the offer disappears with it.
            compose.onNodeWithText(s(R.string.history_restore)).performScrollTo().performClick()
            answerSystemConsent(allow = true)
            awaitSystemConsentGone()
            compose.waitUntil(timeoutMillis = UI_TIMEOUT) {
                compose.onAllNodesWithText(s(R.string.history_restore))
                    .fetchSemanticsNodes().isEmpty()
            }
        }

        for (seed in seeds) {
            assertTrue("${seed.name} must be back in the gallery", isVisible(seed.uri))
            assertFalse(isTrashed(seed.uri))
        }
        runBlocking {
            val batch = db.reclaim().recentBatchesFlow(50).first().first()
            for (item in db.reclaim().itemsOf(batch.id)) {
                assertNotNull("${item.displayName} must be marked restored", item.restoredAt)
            }
            for (seed in seeds) {
                val row = db.items().byFingerprint(seed.fingerprint)!!
                assertEquals(ItemState.DONE.name, row.state)
                assertFalse("${seed.name} is an original again", row.originalMissing)
            }
        }
    }

    // ---- the other two ways in -----------------------------------------------

    /**
     * Removing a duplicate extra needs no upload evidence - an identical file
     * stays on the phone - but it still goes through Android's dialog, and a
     * refusal there still means nothing happens.
     */
    @Test
    @RequiresApi(Build.VERSION_CODES.R)
    fun removingADuplicateExtraGoesThroughTheSystemDialog() {
        assumeTrue("no batch consent dialog before API 30", Build.VERSION.SDK_INT >= 30)
        val (keeper, extra) = seedIdenticalPair()

        ActivityScenario.launch(MainActivity::class.java).use {
            openHubCard(R.string.find_duplicates)
            awaitText(extra.name)
            compose.onNodeWithText(s(R.string.dupes_this_one_stays)).assertIsDisplayed()

            // Two rows, two overflow buttons: the keeper first, then its extra.
            openExtraRowMenu()
            compose.onNodeWithText(s(R.string.dupes_remove_extra_one)).performClick()

            answerSystemConsent(allow = false)
            awaitSystemConsentGone()
            compose.waitForIdle()

            assertTrue("the keeper must be untouched", isVisible(keeper.uri))
            assertTrue("a refused extra must stay", isVisible(extra.uri))
            assertTrue(
                "nothing may be reported as removed",
                compose.onAllNodesWithText(s(R.string.dupes_removed_title))
                    .fetchSemanticsNodes().isEmpty()
            )

            awaitText(extra.name)
            openExtraRowMenu()
            compose.onNodeWithText(s(R.string.dupes_remove_extra_one)).performClick()

            answerSystemConsent(allow = true)
            awaitSystemConsentGone()
            awaitText(R.string.dupes_removed_title)
            compose.onNodeWithText(plural(R.plurals.dupes_removed_body, 1, 1)).assertIsDisplayed()
        }

        assertTrue("the identical copy that stays must never move", isVisible(keeper.uri))
        assertFalse("the confirmed extra must have gone", isVisible(extra.uri))
        assertTrue("and it must be in the trash, not erased", isTrashed(extra.uri))
    }

    /**
     * Leftover work files are the app's own half-written scratch, so clearing
     * them must not put a consent dialog in front of anyone - and must not go
     * near the gallery.
     */
    @Test
    @RequiresApi(Build.VERSION_CODES.R)
    fun clearingLeftoverWorkFilesNeedsNoConsentAndTouchesNoMedia() {
        assumeTrue("the rest of this suite is API 30+, so this stays with it", Build.VERSION.SDK_INT >= 30)
        val untouched = MediaFixtures.insertPhoto(
            target, name = "leftovers_bystander.jpg", width = 96, height = 96, seed = 7
        )
        val leftover = File(Storage.tempDir(target), "abandoned_work_file.tmp")
        leftover.writeBytes(ByteArray(512 * 1024))
        // Anything younger than an hour may be the file a run is writing right
        // now, and Storage.cleanTemp deliberately leaves those alone.
        assertTrue(
            "the fixture must look abandoned",
            leftover.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            openHub()
            awaitText(R.string.hub_leftovers)
            // "Clear" is also the Storage screen's own temp-files button, so
            // wait until exactly one of them is on screen before tapping.
            awaitExactlyOne(R.string.hub_clean_now)
            compose.onNodeWithText(s(R.string.hub_clean_now)).performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = UI_TIMEOUT) { !leftover.exists() }
        }

        assertFalse("the leftover must be gone", leftover.exists())
        assertFalse(
            "clearing scratch files must never ask for consent",
            device.hasObject(By.res(POSITIVE_ID))
        )
        assertTrue("no gallery file may be touched", isVisible(untouched))
    }

    // ---- navigation ----------------------------------------------------------

    /** Storage tab, then the one entry that gathers every way to make room. */
    private fun openHub() {
        compose.onNodeWithText(s(R.string.nav_storage)).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(s(R.string.hub_title))).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** The hub, then one of its sections - which only exists when it has bytes. */
    private fun openHubCard(titleRes: Int) {
        openHub()
        awaitText(titleRes)
        compose.onNodeWithText(s(titleRes)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * Lands on Reclaim. "Backed-up originals" is the hub card's label and the
     * screen's own title, so arrival is asserted on something only the screen
     * has: its three modes.
     */
    private fun openBackedUpOriginals() {
        openHubCard(R.string.hub_backed_up)
        awaitText(R.string.reclaim_mode_full)
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.reclaim_mode_full)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.reclaim_target_label)).assertIsDisplayed()
    }

    /**
     * Picks the mode that removes originals outright and ticks everything.
     *
     * Free-up-fully rather than the default replace-with-light: the latter
     * re-encodes a light copy per file before it will ask about anything, which
     * is a compression test, not a consent test.
     */
    private fun chooseFreeUpFullyAndSelectEverything(expected: Int) {
        compose.onNodeWithText(s(R.string.reclaim_mode_full)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.reclaim_all_eligible)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(
            plural(R.plurals.reclaim_selected, expected, expected, expected)
        ).assertIsDisplayed()
        compose.onNodeWithText(
            s(R.string.reclaim_will_free, Formats.bytes(ROW_BYTES * expected))
        ).assertIsDisplayed()
        // The acknowledgement was given in setup, so its row must be absent and
        // the action must be live.
        compose.onNodeWithText(s(R.string.reclaim_understand)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.reclaim_trash)).assertIsEnabled()
    }

    /** The app's own last word before Android's, and through it. */
    private fun tapMoveToTrashAndContinue() {
        compose.onNodeWithText(s(R.string.reclaim_trash)).performClick()
        awaitText(R.string.reclaim_confirm_title)
        compose.onNodeWithText(s(R.string.reclaim_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.reclaim_continue)).performClick()
    }

    /**
     * Opens the overflow of the extra copy in a duplicate group.
     *
     * The keeper's row is drawn first and offers only "Open"; the extra's row
     * is the one that can be removed or promoted, which is asserted on the menu
     * that actually opened rather than assumed from the ordering.
     */
    private fun openExtraRowMenu() {
        val menus = compose.onAllNodesWithContentDescription(s(R.string.list_more_actions))
        menus.assertCountEquals(2)
        menus.onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.dupes_keep_instead)).assertIsDisplayed()
    }

    // ---- the system dialog ---------------------------------------------------

    /**
     * Taps Allow or Deny on MediaProvider's consent sheet.
     *
     * `enabled(true)` matters: the sheet disables both buttons while it carries
     * out an accepted request, so without it a second call could re-find the
     * dying first dialog and tap a dead button.
     */
    private fun answerSystemConsent(allow: Boolean) {
        val button = if (allow) {
            findConsentButton(POSITIVE_ID, POSITIVE_TEXT, "allow")
        } else {
            findConsentButton(NEGATIVE_ID, NEGATIVE_TEXT, "deny")
        }
        button.click()
    }

    private fun findConsentButton(resId: String, label: Pattern, what: String): UiObject2 {
        device.wait(Until.findObject(By.res(resId).enabled(true)), DIALOG_TIMEOUT)
            ?.let { return it }
        // Only reached on a build that renames the framework button ids.
        val byLabel = device.wait(
            Until.findObject(
                By.clazz("android.widget.Button").text(label).enabled(true)
            ),
            FALLBACK_TIMEOUT
        )
        assertNotNull(
            "Android's own consent dialog never showed a \"$what\" button " +
                "(looked for $resId, then for $label)",
            byLabel
        )
        return byLabel!!
    }

    private fun awaitSystemConsentGone() {
        assertTrue(
            "Android's consent dialog stayed on screen after it was answered",
            device.wait(Until.gone(By.res(POSITIVE_ID)), DIALOG_TIMEOUT)
        )
        device.waitForIdle()
    }

    // ---- waiting -------------------------------------------------------------

    private fun awaitText(id: Int, timeoutMs: Long = UI_TIMEOUT) = awaitText(s(id), timeoutMs)

    private fun awaitText(text: String, timeoutMs: Long = UI_TIMEOUT) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Waits for a label that another screen also uses to settle to one node, so
     * a tap cannot land during a navigation transition while both are on screen.
     */
    private fun awaitExactlyOne(id: Int, timeoutMs: Long = UI_TIMEOUT) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(s(id)).fetchSemanticsNodes().size == 1
        }
    }

    // ---- seeding -------------------------------------------------------------

    /**
     * Real gallery files, plus the rows that make them eligible for removal.
     *
     * The row is what ReclaimRules judges, so the ages, the evidence grade and
     * the ledger entry are written directly rather than waited for; the file
     * itself is genuine, because it is the file Android's dialog is about.
     */
    private fun seedBackedUpOriginals(count: Int, prefix: String): List<Seeded> {
        val seeded = mutableListOf<Seeded>()
        runBlocking {
            for (i in 1..count) {
                val name = "%s_%04d.jpg".format(prefix, i)
                val uri = MediaFixtures.insertPhoto(
                    target, name = name, width = 96, height = 96, seed = i
                )
                val fingerprint = "%s%04d0000000000".format(prefix, i)
                val sha = "sha256-of-copy-$fingerprint"
                db.ledger().insert(
                    LedgerRow(
                        outputSha256 = sha,
                        fingerprint = fingerprint,
                        displayName = name,
                        outputBytes = ROW_BYTES / 4,
                        evidence = Evidence.CONFIRMED_EXACT.name,
                        confirmedAt = FIXTURE_TAKEN_MS
                    )
                )
                db.items().insert(
                    baseRow(name, uri, fingerprint).copy(
                        evidence = Evidence.CONFIRMED_EXACT.name,
                        outputName = "$name.out",
                        outputBytes = ROW_BYTES / 4,
                        outputSha256 = sha,
                        releasedAt = FIXTURE_TAKEN_MS,
                        confirmedAt = FIXTURE_TAKEN_MS
                    )
                )
                seeded += Seeded(name, uri, fingerprint)
            }
        }
        assertEquals(count, seeded.size)
        return seeded
    }

    /**
     * Two byte-identical originals. The duplicate screen groups on the hash the
     * app recorded, so the hash is read back off the real files - which also
     * proves the two fixtures really are the same file.
     */
    private fun seedIdenticalPair(): Pair<Seeded, Seeded> {
        val keeperUri = MediaFixtures.insertPhoto(
            target, name = "twin_keeper.jpg", width = 96, height = 96, seed = 42
        )
        val extraUri = MediaFixtures.insertPhoto(
            target, name = "twin_extra.jpg", width = 96, height = 96, seed = 42
        )
        val keeperSha = sha256Of(keeperUri)
        val extraSha = sha256Of(extraUri)
        assertEquals("the two fixtures must be byte-identical", keeperSha, extraSha)

        runBlocking {
            // The keeper is the oldest capture, so the older date decides it.
            db.items().insert(
                baseRow("twin_keeper.jpg", keeperUri, "twinkeeper00000000").copy(
                    captureAt = FIXTURE_TAKEN_MS,
                    originalSha256 = keeperSha
                )
            )
            db.items().insert(
                baseRow("twin_extra.jpg", extraUri, "twinextra000000000").copy(
                    captureAt = FIXTURE_TAKEN_MS + 3_600_000L,
                    originalSha256 = extraSha
                )
            )
        }
        return Seeded("twin_keeper.jpg", keeperUri, "twinkeeper00000000") to
            Seeded("twin_extra.jpg", extraUri, "twinextra000000000")
    }

    /**
     * A finished item that still has its original.
     *
     * DONE rather than RELEASED on purpose: the maintenance pass only re-judges
     * released copies, so a background sweep cannot rewrite these rows out from
     * under the test.
     */
    private fun baseRow(name: String, uri: Uri, fingerprint: String) = ItemRow(
        fingerprint = fingerprint,
        mediaStoreId = ContentUris.parseId(uri),
        contentUri = uri.toString(),
        displayName = name,
        sizeBytes = ROW_BYTES,
        dateModified = FIXTURE_TAKEN_MS,
        captureAt = FIXTURE_TAKEN_MS,
        dateAdded = FIXTURE_TAKEN_MS / 1000,
        mimeType = "image/jpeg",
        isVideo = false,
        bucket = "CloudSaverTest",
        state = ItemState.DONE.name,
        updatedAt = FIXTURE_TAKEN_MS
    )

    private fun sha256Of(uri: Uri): String =
        target.contentResolver.openInputStream(uri).use { input ->
            assertNotNull("could not read back the fixture $uri", input)
            Fingerprint.sha256(input!!)
        }

    // ---- MediaStore truth ----------------------------------------------------

    /**
     * Whether MediaStore still shows the file, and whether it is in the trash,
     * asked separately: a trashed row is hidden from an ordinary query, so
     * "not visible" alone cannot tell a trashed file from a deleted one.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun isVisible(uri: Uri): Boolean = matches(uri, MediaStore.MATCH_EXCLUDE)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isTrashed(uri: Uri): Boolean = matches(uri, MediaStore.MATCH_ONLY)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun matches(uri: Uri, matchTrashed: Int): Boolean {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed) }
        return target.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID), args, null
        )?.use { it.count > 0 } ?: false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun countInAlbum(matchTrashed: Int): Int {
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${MediaFixtures.TEST_ALBUM}%")
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed)
        }
        return target.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            args,
            null
        )?.use { it.count } ?: 0
    }

    /**
     * MediaFixtures.cleanUp queries the ordinary way, which cannot see trashed
     * rows - so a test that trashed files would otherwise leak them into the
     * next one. This takes everything, trashed included.
     */
    private fun purgeTestAlbum() {
        if (Build.VERSION.SDK_INT < 30) return
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${MediaFixtures.TEST_ALBUM}%")
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        for (collection in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )) {
            val ids = mutableListOf<Long>()
            target.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID), args, null
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            for (id in ids) {
                val uri = ContentUris.withAppendedId(collection, id)
                // Owned by this app, so no consent is involved in the cleanup.
                runCatching { target.contentResolver.delete(uri, null, null) }
            }
        }
    }
}
