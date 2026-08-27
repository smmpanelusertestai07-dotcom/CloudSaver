package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compressed copies are ordinary images to MediaStore, so without these rules
 * the app re-compresses its own output - and other tools' - forever.
 */
class ScanSourcesTest {

    @Test
    fun ourOwnOutputIsNeverScanned() {
        assertEquals(
            ScanSources.Reason.OUR_OUTPUT,
            ScanSources.exclusionReason("Pictures/CloudSaver/", "CloudSaver")
        )
        assertEquals(
            ScanSources.Reason.OUR_OUTPUT,
            ScanSources.exclusionReason("Pictures/CloudSaver/Photos/", "Photos")
        )
    }

    @Test
    fun hiddenFoldersAreNeverScanned() {
        assertEquals(
            ScanSources.Reason.HIDDEN,
            ScanSources.exclusionReason("Pictures/.thumbnails/", "thumbnails")
        )
        assertEquals(
            ScanSources.Reason.HIDDEN,
            ScanSources.exclusionReason("DCIM/.trash/sub/", "sub")
        )
    }

    /** The folder that put _ente_keep.jpg into the queue. */
    @Test
    fun knownPipelineFoldersAreNeverScanned() {
        for (name in listOf("EnteUpload", "GlassSaver", "LiteSaver", "CloudShrink")) {
            assertEquals(
                "$name must be excluded",
                ScanSources.Reason.LEGACY_OUTPUT,
                ScanSources.exclusionReason("Pictures/$name/", name)
            )
        }
        // Case should not be a way around it.
        assertEquals(
            ScanSources.Reason.LEGACY_OUTPUT,
            ScanSources.exclusionReason("Pictures/enteupload/", "enteupload")
        )
    }

    /**
     * The scanner already skipped these - it asked isCloudLocalPath by itself
     * - but the picker was told nothing, so a cloud app's own download folder
     * sat in the album list looking tickable and ignored every tick. The
     * reason has to come out of the same function the picker reads.
     */
    @Test
    fun aCloudAppsOwnMediaFolderIsExcludedWithItsReason() {
        assertEquals(
            ScanSources.Reason.CLOUD_LOCAL,
            ScanSources.exclusionReason("Android/media/io.ente.photos/Downloads/", "Downloads")
        )
        assertEquals(
            "case must not matter - paths come back in either",
            ScanSources.Reason.CLOUD_LOCAL,
            ScanSources.exclusionReason("android/media/MEGA.privacy.android.app/x/", "x")
        )
        // Another app's media directory holds somebody's real photos.
        assertNull(
            ScanSources.exclusionReason(
                "Android/media/com.whatsapp/WhatsApp Images/", "WhatsApp Images"
            )
        )
        // And a caller may still name the packages itself.
        assertNull(
            ScanSources.exclusionReason(
                relativePath = "Android/media/io.ente.photos/Downloads/",
                bucketName = "Downloads",
                cloudPackages = emptyList()
            )
        )
    }

    @Test
    fun ordinaryGalleryFoldersAreScanned() {
        assertNull(ScanSources.exclusionReason("DCIM/Camera/", "Camera"))
        assertNull(ScanSources.exclusionReason("Pictures/Screenshots/", "Screenshots"))
        assertNull(ScanSources.exclusionReason("Pictures/WhatsApp Images/", "WhatsApp Images"))
        // A name that merely starts the same is somebody else's folder.
        assertNull(ScanSources.exclusionReason("Pictures/CloudSaverBackup/", "CloudSaverBackup"))
    }

    @Test
    fun pipelineNamesAreRecognised() {
        assertTrue(ScanSources.isPipelineName("IMG_0001__0123456789abcdef.jpg"))
        assertTrue(ScanSources.isPipelineName("clip__fedcba9876543210.mp4"))
        // MediaStore's de-duplication suffix must not hide it.
        assertTrue(ScanSources.isPipelineName("IMG_0001__0123456789abcdef (1).jpg"))

        assertFalse(ScanSources.isPipelineName("IMG_0001.jpg"))
        assertFalse(ScanSources.isPipelineName("Screenshot_2026-08-23.png"))
        // Too short, too long, and not hex.
        assertFalse(ScanSources.isPipelineName("a__0123456789abcde.jpg"))
        assertFalse(ScanSources.isPipelineName("a__0123456789abcdef0.jpg"))
        assertFalse(ScanSources.isPipelineName("a__zzzzzzzzzzzzzzzz.jpg"))
    }

    @Test
    fun aFolderOfCompressedCopiesIsDetectedByItsContents() {
        val copies = (1..10).map { "IMG_%04d__0123456789abcde%x.jpg".format(it, it % 16) }
        assertTrue(ScanSources.looksLikePipelineOutput(copies))

        // One stray ordinary photo does not rescue it: 9 of 10 is still 90%.
        assertTrue(ScanSources.looksLikePipelineOutput(copies.dropLast(1) + "holiday.jpg"))

        // At 70% the folder is a mix, and the user gets to decide.
        val mixed = copies.take(7) + listOf("a.jpg", "b.jpg", "c.jpg")
        assertFalse(ScanSources.looksLikePipelineOutput(mixed))
    }

    @Test
    fun anOrdinaryFolderIsNotDetectedByItsContents() {
        assertFalse(
            ScanSources.looksLikePipelineOutput(
                listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg", "IMG_4.jpg", "IMG_5.jpg")
            )
        )
    }

    /** Judging a folder on one or two files would exclude real albums. */
    @Test
    fun tinyFoldersAreNotJudgedByContents() {
        assertFalse(ScanSources.looksLikePipelineOutput(emptyList()))
        assertFalse(ScanSources.looksLikePipelineOutput(listOf("a__0123456789abcdef.jpg")))
        assertFalse(
            ScanSources.looksLikePipelineOutput(
                listOf("a__0123456789abcdef.jpg", "b__0123456789abcdef.jpg")
            )
        )
    }

    @Test
    fun contentHeuristicExcludesAnOtherwiseOrdinaryFolder() {
        assertEquals(
            ScanSources.Reason.LOOKS_LIKE_OUTPUT,
            ScanSources.exclusionReason(
                relativePath = "Pictures/MyBackups/",
                bucketName = "MyBackups",
                looksLikeOutput = true
            )
        )
    }
}
