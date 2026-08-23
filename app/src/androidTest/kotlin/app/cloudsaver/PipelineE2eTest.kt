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

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    )

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

        // MediaProvider re-derives DATE_TAKEN from EXIF when a file is
        // published, and EXIF carries no timezone, so the absolute value
        // depends on the device's zone. What the app actually promises is that
        // a copy sorts next to its original in the cloud app, so compare the
        // copy against the original rather than against a hard-coded instant.
        val originalTaken = dateTakenOf(originals.first())
        assertTrue("the fixture must have a capture date", originalTaken > 0)

        val inFolder = OutputInventory(context).query()
        assertNotNull("the output folder must be readable", inFolder)
        assertEquals(3, inFolder!!.size)
        for (entry in inFolder) {
            assertEquals(Defaults.OUTPUT_DIR, entry.relPath.trimEnd('/'))
            assertTrue("copy must be owned by us", entry.ownedByUs)
            assertTrue(
                "copy DATE_TAKEN ${entry.dateTaken} must match the original's " +
                    "$originalTaken (fixture asked for $captureAt)",
                abs(entry.dateTaken - originalTaken) < 2000
            )
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
        val uri = MediaFixtures.insertVideo(context, "e2e_clip.mp4")
        if (uri == null) {
            // No usable encoder on this image; nothing to assert.
            return@runBlockingTest
        }
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
