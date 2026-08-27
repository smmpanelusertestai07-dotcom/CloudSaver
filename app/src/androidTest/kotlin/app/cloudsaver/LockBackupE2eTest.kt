package app.cloudsaver

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.SnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The lock, and the one file the user can carry their settings away in.
 *
 * Both are places where being almost right is worse than not existing: a lock
 * that shows the content behind it protects nothing, and a restore that
 * half-applies a file leaves someone's history in a state they cannot reason
 * about.
 */
@RunWith(AndroidJUnit4::class)
class LockBackupE2eTest {

    /** Any failure below leaves a picture of the screen behind it. */
    @get:Rule
    val shotOnFailure = ScreenshotOnFailure()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.forThisDevice())

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target: Context get() = instrumentation.targetContext
    private fun s(id: Int): String = target.getString(id)

    private val repo get() = OptionsRepo.get(target)
    private fun backupFile() = File(target.cacheDir, "e2e-backup.csb")

    @Before
    fun setUp(): Unit = runBlocking {
        AppDb.get(target).clearAllTables()
        repo.setBool(OptionsRepo.K.ONBOARDING_DONE, true)
        repo.setBool(OptionsRepo.K.APP_LOCK, false)
        backupFile().delete()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        repo.setBool(OptionsRepo.K.APP_LOCK, false)
        backupFile().delete()
        AppDb.get(target).clearAllTables()
    }

    // ---- the lock ------------------------------------------------------------

    /**
     * The bug this pins: the lock used to cover only the current tab, so
     * tapping a different one in the bar underneath showed that screen in
     * full. Nothing of the app may be on screen while it is locked - and the
     * bar itself is part of the app.
     */
    @Test
    fun nothingOfTheAppIsReachableWhileItIsLocked(): Unit = runBlocking {
        repo.setBool(OptionsRepo.K.APP_LOCK, true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            // The options flow starts on defaults and the stored value lands a
            // frame or two later, so the bar is briefly on screen before the
            // lock is even known about. Waiting for the state to settle is the
            // difference between testing the lock and testing that first frame.
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodes(hasText(s(R.string.lock_title), substring = true))
                    .fetchSemanticsNodes().isNotEmpty() ||
                    !runBlocking { repo.current().appLock }
            }
            // Either the lock screen is up, or the phone had no screen lock
            // and the app disabled the lock rather than trapping the user.
            // Both are correct; a bar full of tabs over a locked app is not.
            val locked = compose.onAllNodes(hasText(s(R.string.lock_title), substring = true))
                .fetchSemanticsNodes().isNotEmpty()
            if (!locked) {
                assertFalse(
                    "with no screen lock the app must turn its own lock off",
                    repo.current().appLock
                )
                return@runBlocking
            }
            // The bar leaves through a transition, and Compose transitions do
            // not honour the system's animations-off switch - so for a few
            // frames after the lock title appears, the outgoing bar is still
            // in the tree. A frame of exit animation is not reachability;
            // what must be true is that the bar is gone once the lock has
            // settled, which is the same settling this test already waits
            // for above. On a slow emulator the old immediate count sampled
            // exactly that frame.
            val tabs = listOf(
                R.string.nav_home, R.string.nav_files, R.string.nav_storage, R.string.nav_options
            )
            compose.waitUntil(timeoutMillis = 20_000) {
                tabs.all {
                    compose.onAllNodes(hasText(s(it))).fetchSemanticsNodes().isEmpty()
                }
            }
            for (tab in tabs) {
                assertEquals(
                    "the ${s(tab)} tab must not be on screen while locked",
                    0,
                    compose.onAllNodes(hasText(s(tab))).fetchSemanticsNodes().size
                )
            }
            compose.onNodeWithText(s(R.string.lock_unlock)).assertIsDisplayed()
        }
    }

    /**
     * A lock whose key has been thrown away is a door nobody can open. When
     * the phone has no screen lock at all the app must say so and let the
     * user back in, not sit on a screen with a button that cannot work.
     */
    @Test
    fun aLockNeverLeavesTheUserWithNoWayIn(): Unit = runBlocking {
        repo.setBool(OptionsRepo.K.APP_LOCK, true)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            // One of two things must be true within a few seconds: the lock
            // screen is up with something to unlock it, or the app has turned
            // its own lock off because this phone has no screen lock to verify
            // against. A locked screen with a dead button is the failure.
            compose.waitUntil(timeoutMillis = 20_000) {
                val locked = compose
                    .onAllNodes(hasText(s(R.string.lock_unlock), substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
                locked || !runBlocking { repo.current().appLock }
            }
            val locked = compose
                .onAllNodes(hasText(s(R.string.lock_unlock), substring = true))
                .fetchSemanticsNodes().isNotEmpty()
            assertTrue(
                "with the lock on, the app must either ask to unlock or turn " +
                    "the lock off; it did neither",
                locked || !repo.current().appLock
            )
        }
    }

    // ---- the backup file -----------------------------------------------------

    private fun exportTo(file: File, password: String?): Boolean = runBlocking {
        file.parentFile?.mkdirs()
        file.delete()
        file.createNewFile()
        SnapshotStore(target, AppDb.get(target), repo).exportTo(Uri.fromFile(file), password)
    }

    private fun importFrom(file: File, password: String?) = runBlocking {
        SnapshotStore(target, AppDb.get(target), repo).importFrom(Uri.fromFile(file), password)
    }

    @Test
    fun aBackupRoundTripsThroughARealFileWithItsPassword(): Unit = runBlocking {
        repo.setString(OptionsRepo.K.PRESET, Preset.MAX_SAVER.name)
        val before = repo.current().preset
        assertTrue("export must succeed", exportTo(backupFile(), "correct horse battery"))
        assertTrue("the file must have content", backupFile().length() > 0)

        // Change the thing that was saved, then put the file back.
        repo.setString(OptionsRepo.K.PRESET, Preset.BALANCED.name)
        assertNotEquals(before, repo.current().preset)

        val result = importFrom(backupFile(), "correct horse battery")
        assertTrue(
            "a correct password must restore, got $result",
            result is SnapshotStore.ImportResult.Success
        )
        assertEquals("the saved setting must come back", before, repo.current().preset)
    }

    @Test
    fun theWrongPasswordRestoresNothingAtAll(): Unit = runBlocking {
        repo.setString(OptionsRepo.K.PRESET, Preset.MAX_SAVER.name)
        assertTrue(exportTo(backupFile(), "the real password"))
        repo.setString(OptionsRepo.K.PRESET, Preset.BALANCED.name)

        val result = importFrom(backupFile(), "not the real password")
        assertEquals(
            "a wrong password must be reported as such",
            SnapshotStore.ImportResult.WrongPassword,
            result
        )
        assertEquals(
            "and must leave every setting exactly as it was",
            Preset.BALANCED.name,
            repo.current().preset.name
        )
    }

    @Test
    fun anEncryptedFileWithNoPasswordAsksForOneInsteadOfFailing(): Unit = runBlocking {
        assertTrue(exportTo(backupFile(), "a password"))
        assertEquals(
            SnapshotStore.ImportResult.NeedsPassword,
            importFrom(backupFile(), null)
        )
    }

    @Test
    fun aFileThatIsNotABackupIsRefusedAndChangesNothing(): Unit = runBlocking {
        repo.setString(OptionsRepo.K.PRESET, Preset.MAX_SAVER.name)
        val junk = File(target.cacheDir, "not-a-backup.csb")
        junk.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        val result = importFrom(junk, null)
        assertEquals(
            "unreadable input must be refused, not half-applied",
            SnapshotStore.ImportResult.Unreadable,
            result
        )
        assertEquals(Preset.MAX_SAVER.name, repo.current().preset.name)
        junk.delete()
    }

    @Test
    fun anUnencryptedBackupStillRoundTrips(): Unit = runBlocking {
        repo.setString(OptionsRepo.K.PRESET, Preset.MAX_SAVER.name)
        val before = repo.current().preset
        assertTrue(exportTo(backupFile(), null))
        repo.setString(OptionsRepo.K.PRESET, Preset.BALANCED.name)
        assertTrue(importFrom(backupFile(), null) is SnapshotStore.ImportResult.Success)
        assertEquals(before, repo.current().preset)
    }

    // ---- the dialog that asks for the password -------------------------------

    @Test
    fun theSaveBackupRowOpensThePasswordDialogAndCancellingChangesNothing(): Unit = runBlocking {
        val presetBefore = repo.current().preset
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(s(R.string.nav_options)).performClick()
            compose.onNode(hasText(s(R.string.transfer_export), substring = true))
                .performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNode(hasText(s(R.string.backup_password_label), substring = true))
                .assertIsDisplayed()
            // The save dialog offers Skip - save without a password - rather
            // than Cancel, and skipping would write a file, which is not what
            // abandoning means. Backing out is how a person abandons it.
            androidx.test.uiautomator.UiDevice
                .getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
                .pressBack()
            compose.waitForIdle()
            compose.onNodeWithText(s(R.string.backup_password_label)).assertDoesNotExist()
            compose.onNode(hasText(s(R.string.transfer_export), substring = true))
                .assertIsDisplayed()
        }
        assertEquals(presetBefore, repo.current().preset)
    }
}
