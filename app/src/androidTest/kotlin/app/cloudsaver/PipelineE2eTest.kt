package app.cloudsaver

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.MaintainEngine
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.media.OutputInventory
import app.cloudsaver.media.Releaser
import app.cloudsaver.media.Stager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The real end-to-end run on a device: put genuine photos and a video into the
 * gallery, drive the actual pipeline, then check what landed in the upload
 * folder - sizes, dates, EXIF - and that the originals were never touched.
 */
@RunWith(AndroidJUnit4::class)
class PipelineE2eTest {

    /** Any failure below leaves a picture of the screen behind it. */
    @get:Rule
    val shotOnFailure = ScreenshotOnFailure()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.forThisDevice())

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val captureAt = 1_600_000_000_000L

    @Before
    fun setUp() {
        MediaFixtures.cleanUp(context)
        clearOutputFolder()
        runBlocking { AppDb.get(context).clearAllTables() }
    }

    @After
    fun tearDown() {
        MediaFixtures.cleanUp(context)
        clearOutputFolder()
    }

    @Test
    fun photosAreOptimisedReleasedAndDatedCorrectly() = runBlockingTest {
        val originals = (1..3).map { i ->
            MediaFixtures.insertPhoto(
                context,
                name = "e2e_photo_$i.jpg",
                seed = i,
                captureMillis = captureAt
            )
        }
        val originalSizes = originals.map { sizeOf(it) }
        assertTrue("fixtures should be non-trivial", originalSizes.all { it > 200_000 })

        val db = AppDb.get(context)
        val options = OptionsRepo.get(context).current()
        val found = MediaScanner(context, db).scan()
        assertTrue("scanner must see the fixtures", found >= 3)

        val queued = db.items().byState(ItemState.NEW.name)
            .filter { it.displayName.startsWith("e2e_photo_") }
        assertEquals(3, queued.size)

        val stager = Stager(context, db)
        for (row in queued) {
            assertTrue("staging ${row.displayName} failed", stager.stageOne(row, options))
        }

        val staged = db.items().staged().filter { it.displayName.startsWith("e2e_photo_") }
        assertEquals(3, staged.size)
        for (row in staged) {
            val out = row.outputBytes ?: 0
            assertTrue("copy must exist", File(row.stagePath!!).exists())
            assertTrue(
                "copy of ${row.displayName} ($out) must be smaller than ${row.sizeBytes}",
                out in 1 until row.sizeBytes
            )
            assertNotNull("sha must be recorded", row.outputSha256)
        }

        val released = Releaser(context, db).releaseBatch(options, System.currentTimeMillis())
        assertEquals(3, released)

        // For photos, DATE_TAKEN is MediaProvider's own derivation from EXIF -
        // it ignores what an app writes - so the contract the app can actually
        // keep is that the copy carries the original's shooting metadata, and
        // that its capture date matches the original's whenever MediaStore
        // reports one at all. The EXIF assertions below are the real check.
        val originalTaken = dateTakenOf(originals.first())
        val recordedCaptureAt = staged.first().captureAt
        assertTrue(
            "the app must have recorded a capture date, got $recordedCaptureAt",
            recordedCaptureAt > 0
        )

        val inFolder = OutputInventory(context).query()
        assertNotNull("the output folder must be readable", inFolder)
        assertEquals(3, inFolder!!.size)
        for (entry in inFolder) {
            assertEquals(Defaults.OUTPUT_DIR, entry.relPath.trimEnd('/'))
            assertTrue("copy must be owned by us", entry.ownedByUs)
            if (originalTaken > 0 && entry.dateTaken > 0) {
                assertTrue(
                    "copy DATE_TAKEN ${entry.dateTaken} must match the original's " +
                        "$originalTaken (app recorded $recordedCaptureAt)",
                    abs(entry.dateTaken - originalTaken) < 2000
                )
            }
            // The optimised copy keeps the shooting metadata.
            context.contentResolver.openInputStream(entry.uri)!!.use { input ->
                val exif = ExifInterface(input)
                assertEquals(
                    "2020:09:13 12:26:40",
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                )
                assertEquals("CloudSaverTest", exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNotNull("GPS must survive", exif.latLong)
                assertEquals(
                    "orientation must be baked in",
                    ExifInterface.ORIENTATION_NORMAL,
                    exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED
                    )
                )
            }
        }

        // The whole point of the app: originals are exactly as they were.
        originals.forEachIndexed { index, uri ->
            assertEquals(
                "original ${index + 1} must be untouched",
                originalSizes[index],
                sizeOf(uri)
            )
        }

        // Bookkeeping pass must run clean over real data.
        MaintainEngine(context).run()
        val after = db.items().released().filter { it.displayName.startsWith("e2e_photo_") }
        assertEquals(3, after.size)
    }

    @Test
    fun videoIsOptimisedOrSafelyCopied() = runBlockingTest {
        // Every Android device that can record has an H.264 encoder, and the
        // emulator carries the software one, so a null here is a real failure
        // rather than a reason to pass quietly. A test that returns early on
        // the very condition it exists to exercise proves nothing.
        val uri = MediaFixtures.insertVideo(context, "e2e_clip.mp4")
        assertNotNull("the device must be able to produce a test clip", uri)
        uri!!
        val srcSize = sizeOf(uri)
        val db = AppDb.get(context)
        val options = OptionsRepo.get(context).current()
        MediaScanner(context, db).scan()

        val row = db.items().byState(ItemState.NEW.name)
            .firstOrNull { it.displayName == "e2e_clip.mp4" }
        assertNotNull("scanner must see the clip", row)

        assertTrue("video staging must succeed", Stager(context, db).stageOne(row!!, options))
        val staged = db.items().staged().first { it.displayName == "e2e_clip.mp4" }
        val out = staged.outputBytes ?: 0
        assertTrue("a copy must exist", out > 0)
        // Either it compressed, or it fell back to an as-is copy - never bigger.
        assertTrue("copy must not be larger than the source", out <= srcSize)
        assertTrue(File(staged.stagePath!!).exists())
    }

    /**
     * A clip that goes in with sound comes out with sound.
     *
     * The export sequence names which tracks it carries, and the exporter
     * strips any it does not name - so the one-line choice of how that
     * sequence is built decides whether every optimised video keeps its
     * audio. The wrong choice would fail no other test: the copy would be
     * smaller, valid, and silent.
     */
    @Test
    fun videoKeepsItsSound() = runBlockingTest {
        val uri = MediaFixtures.insertVideo(context, "e2e_talkie.mp4", withAudio = true)
        assertNotNull("the device must be able to produce a clip with an audio track", uri)
        assertTrue("the fixture itself must carry audio", hasAudioTrack(uri!!.toString()))
        val db = AppDb.get(context)
        val options = OptionsRepo.get(context).current()
        MediaScanner(context, db).scan()
        val row = db.items().byState(ItemState.NEW.name)
            .firstOrNull { it.displayName == "e2e_talkie.mp4" }
        assertNotNull("scanner must see the clip", row)
        assertTrue("video staging must succeed", Stager(context, db).stageOne(row!!, options))
        val staged = db.items().staged().first { it.displayName == "e2e_talkie.mp4" }
        val path = staged.stagePath!!
        assertTrue(File(path).exists())
        assertTrue(
            "the optimised copy lost its audio track",
            hasAudioTrack(path)
        )
    }

    /** True when the container at [source] (a path or a content URI) has an audio track. */
    private fun hasAudioTrack(source: String): Boolean {
        val ex = android.media.MediaExtractor()
        return try {
            if (source.startsWith("content:")) {
                ex.setDataSource(context, android.net.Uri.parse(source), null)
            } else {
                ex.setDataSource(source)
            }
            (0 until ex.trackCount).any { i ->
                ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
        } finally {
            runCatching { ex.release() }
        }
    }

    @Test
    fun skippedAndEmptyStatesDoNotCrash() = runBlockingTest {
        val db = AppDb.get(context)
        // No fixtures at all: every stage must be a no-op, not an exception.
        MediaScanner(context, db).scan()
        Releaser(context, db).releaseBatch(
            OptionsRepo.get(context).current(),
            System.currentTimeMillis()
        )
        MaintainEngine(context).run()
    }

    /** JUnit needs void test methods; this pins the return type to Unit. */
    private fun runBlockingTest(body: suspend () -> Unit) {
        runBlocking { body() }
    }

    private fun sizeOf(uri: Uri): Long =
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private fun dateTakenOf(uri: Uri): Long =
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private fun clearOutputFolder() {
        for (entry in OutputInventory(context).query().orEmpty()) {
            runCatching { context.contentResolver.delete(entry.uri, null, null) }
        }
        runCatching {
            File(context.getExternalFilesDir(null), "stage").deleteRecursively()
        }
    }
}
