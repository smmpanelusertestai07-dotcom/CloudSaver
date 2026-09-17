package app.cloudsaver.core.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptCopiesTest {

    private val fp = "0123456789abcdef"

    @Test
    fun anInPlaceCopyUnderTheOriginalsNameBelongs() {
        assertTrue(KeptCopies.belongsTo("IMG_0001.jpg", "IMG_0001.jpg", fp))
        // A HEIC original's in-place copy is a JPEG: same stem, new extension.
        assertTrue(KeptCopies.belongsTo("IMG_0001.jpg", "IMG_0001.heic", fp))
        assertTrue(KeptCopies.belongsTo("img_0001.JPG", "IMG_0001.jpg", fp))
    }

    @Test
    fun aCopyInTheAppsOwnAlbumBelongsByItsFingerprint() {
        assertTrue(KeptCopies.belongsTo("IMG_0001__$fp.jpg", "IMG_0001.jpg", fp))
        // The gallery's own dedup suffix on the copy's name does not hide it.
        assertTrue(KeptCopies.belongsTo("IMG_0001__$fp (1).jpg", "IMG_0001.jpg", fp))
    }

    @Test
    fun anotherPhonesFileUnderTheSameNumberDoesNot() {
        // A restored keptUri is a MediaStore number; on a new phone that
        // number is somebody's holiday photo. It must never be deleted or
        // hidden from the queue on the strength of a number.
        assertFalse(KeptCopies.belongsTo("PXL_20260101_120000.jpg", "IMG_0001.jpg", fp))
        assertFalse(KeptCopies.belongsTo("IMG_0002__fedcba9876543210.jpg", "IMG_0001.jpg", fp))
    }

    @Test
    fun aCopyRenamedByHandIsAnOrdinaryPhotoAgain() {
        // Deliberate: there is no way to tell a renamed copy from a
        // stranger's file, and the safe reading is the stranger's.
        assertFalse(KeptCopies.belongsTo("holiday.jpg", "IMG_0001.jpg", fp))
    }
}
