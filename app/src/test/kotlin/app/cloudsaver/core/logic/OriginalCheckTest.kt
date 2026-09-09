package app.cloudsaver.core.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalCheckTest {

    @Test
    fun theSameFileUnderTheSameNumberIsTheOriginal() {
        assertTrue(OriginalCheck.unchanged("IMG_0001.jpg", 4_000_000, "IMG_0001.jpg", 4_000_000))
        // MediaProvider may rewrite the case or the extension's spelling on a
        // re-stat; neither is an edit.
        assertTrue(OriginalCheck.unchanged("IMG_0001.jpg", 4_000_000, "img_0001.JPG", 4_000_000))
    }

    @Test
    fun anEditSavedInPlaceIsNotTheOriginalTheCloudHas() {
        // "Save" in a gallery editor keeps the MediaStore id and rewrites the
        // bytes. The cloud holds the photo as it was; this file is not it.
        assertFalse(OriginalCheck.unchanged("IMG_0001.jpg", 4_000_000, "IMG_0001.jpg", 2_750_000))
    }

    @Test
    fun aRenamedFileIsNotOfferedEither() {
        // Same bytes under a new name is almost certainly the same photo,
        // but the safe reading is the one that keeps a file.
        assertFalse(OriginalCheck.unchanged("IMG_0001.jpg", 4_000_000, "holiday.jpg", 4_000_000))
    }

    @Test
    fun aRowWithNoSizeNeverPasses() {
        assertFalse(OriginalCheck.unchanged("IMG_0001.jpg", 0, "IMG_0001.jpg", 0))
    }
}
