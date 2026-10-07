package app.entesaver.core.logic

import org.junit.Assert.assertEquals
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
    fun anInPlaceCopyTheGalleryRenamedOnArrivalStillBelongs() {
        // The copy is written while the original still holds the name, so
        // MediaProvider adds " (1)". Read as a stranger, the scanner queued
        // the copy as a new photo and the re-key in finish() then collided
        // with it on the unique fingerprint index.
        assertTrue(KeptCopies.belongsTo("IMG_0001 (1).jpg", "IMG_0001.jpg", fp))
        assertTrue(KeptCopies.belongsTo("IMG_0001 (12).jpg", "IMG_0001.heic", fp))
        // Only the provider's exact suffix: anything else is a different name.
        assertFalse(KeptCopies.belongsTo("IMG_0001 (1)x.jpg", "IMG_0001.jpg", fp))
        assertFalse(KeptCopies.belongsTo("IMG_0001 copy.jpg", "IMG_0001.jpg", fp))
    }

    @Test
    fun anInPlaceCopyTheCameraFolderRenamedStillBelongs() {
        // Under DCIM the provider renames like a camera, not with " (1)".
        // Read as a stranger, every in-place copy of a camera photo on
        // these phones was taken back, and Free up freed nothing.
        assertTrue(KeptCopies.belongsTo("IMG_20240101_123456~2.jpg", "IMG_20240101_123456.jpg", fp))
        assertTrue(KeptCopies.belongsTo("VID_20240101_123456~3.mp4", "VID_20240101_123456.mp4", fp))
        assertTrue(KeptCopies.belongsTo("MVIMG_20240101_123456~2.jpg", "MVIMG_20240101_123456.heic", fp))
        // The provider counts up from the original's own number.
        assertTrue(KeptCopies.belongsTo("IMG_20240101_123456~3.jpg", "IMG_20240101_123456~2.jpg", fp))
        // Not a later number, not the same second: a different photo.
        assertFalse(KeptCopies.belongsTo("IMG_20240101_123456.jpg", "IMG_20240101_123456~2.jpg", fp))
        assertFalse(KeptCopies.belongsTo("IMG_20240101_123457~2.jpg", "IMG_20240101_123456.jpg", fp))
        // The camera's next numbered photo is never taken for the copy.
        assertFalse(KeptCopies.belongsTo("IMG_1235.jpg", "IMG_1234.jpg", fp))
    }

    @Test
    fun a121CopyTheProviderNumberedIsReadBackOnlyWithTheOriginalsTime() {
        // 12.1 asked for "IMG_1234.JPG" in DCIM and kept "IMG_1235.JPG", with
        // the original's modified time stamped on it. Its history lookup
        // missed that row, and a restored original came back as new work.
        val t = 1_600_000_000L
        assertTrue(KeptCopies.isLegacyCountUp("IMG_1235.JPG", "IMG_1234.JPG", t, t))
        assertTrue(KeptCopies.isLegacyCountUp("DSC_0050.jpg", "DSC_0042.JPG", t, t))
        // Another time is the camera's next real photo.
        assertFalse(KeptCopies.isLegacyCountUp("IMG_1235.JPG", "IMG_1234.JPG", t + 1, t))
        assertFalse(KeptCopies.isLegacyCountUp("IMG_1235.JPG", "IMG_1234.JPG", 0L, 0L))
        // Not a later number, not the same prefix, not the same type.
        assertFalse(KeptCopies.isLegacyCountUp("IMG_1234.JPG", "IMG_1234.JPG", t, t))
        assertFalse(KeptCopies.isLegacyCountUp("IMG_1233.JPG", "IMG_1234.JPG", t, t))
        assertFalse(KeptCopies.isLegacyCountUp("DSC_1235.JPG", "IMG_1234.JPG", t, t))
        assertFalse(KeptCopies.isLegacyCountUp("IMG_1235.mp4", "IMG_1234.JPG", t, t))
        assertFalse(KeptCopies.isLegacyCountUp("holiday.jpg", "IMG_1234.jpg", t, t))
        // The general test still never takes the next number.
        assertFalse(KeptCopies.belongsTo("IMG_1235.JPG", "IMG_1234.JPG", fp))
    }

    @Test
    fun a121NumberedCopyStaysKnownOnceItsRowIsBackOnTheOriginal() {
        // The restore pointed the row back at "IMG_1234.jpg"; its copy at
        // keptUri is still "IMG_1235.jpg". Read as a stranger, the scanner
        // optimised it again and sent it to Ente as a new photo, and "Remove
        // the light copy" could not find it.
        val copyBytes = 812_345L
        val origFp = "fedcba9876543210"
        assertTrue(KeptCopies.isRowsCopy("IMG_1235.jpg", copyBytes, "IMG_1234.jpg", origFp, copyBytes))
        assertTrue(KeptCopies.isRowsCopy("IMG_1240.JPG", copyBytes, "IMG_1234.jpg", origFp, copyBytes))
        // Another size is the camera's next real photo under a stale number.
        assertFalse(KeptCopies.isRowsCopy("IMG_1235.jpg", copyBytes + 1, "IMG_1234.jpg", origFp, copyBytes))
        assertFalse(KeptCopies.isRowsCopy("IMG_1235.jpg", copyBytes, "IMG_1234.jpg", origFp, null))
        // Not a later number, not the same prefix, not the same type.
        assertFalse(KeptCopies.isRowsCopy("IMG_1233.jpg", copyBytes, "IMG_1234.jpg", origFp, copyBytes))
        assertFalse(KeptCopies.isRowsCopy("DSC_1235.jpg", copyBytes, "IMG_1234.jpg", origFp, copyBytes))
        assertFalse(KeptCopies.isRowsCopy("IMG_1235.mp4", copyBytes, "IMG_1234.jpg", origFp, copyBytes))
        assertFalse(KeptCopies.isRowsCopy("PXL_20260101_120000.jpg", copyBytes, "IMG_0001.jpg", fp, copyBytes))
        // Whatever belongsTo accepts still belongs, at any size.
        assertTrue(KeptCopies.isRowsCopy("IMG_0001 (1).jpg", 1L, "IMG_0001.jpg", fp, copyBytes))
    }

    @Test
    fun aNumberedCameraNameAsksForTheProvidersOrdinarySuffix() {
        // Asked for as itself, "IMG_1234" in DCIM lands as "IMG_1235",
        // which belongsTo cannot accept. Asked for with " (1)", it lands
        // under a name that belongs.
        val asked = KeptCopies.inPlaceRequest("DCIM/Camera/", "IMG_1234.jpg")
        assertEquals("IMG_1234 (1).jpg", asked)
        assertTrue(KeptCopies.belongsTo(asked, "IMG_1234.jpg", fp))
        assertEquals("DSC_0001 (1).jpg", KeptCopies.inPlaceRequest("DCIM/100ANDRO/", "DSC_0001.jpg"))
        // Outside DCIM, and for every other name, the provider's own rename
        // already belongs.
        assertEquals("IMG_1234.jpg", KeptCopies.inPlaceRequest("Pictures/Screenshots/", "IMG_1234.jpg"))
        assertEquals(
            "IMG_20240101_123456.jpg",
            KeptCopies.inPlaceRequest("DCIM/Camera/", "IMG_20240101_123456.jpg")
        )
        assertEquals("holiday.jpg", KeptCopies.inPlaceRequest("DCIM/Camera/", "holiday.jpg"))
        assertEquals("img_1234.jpg", KeptCopies.inPlaceRequest("DCIM/Camera/", "img_1234.jpg"))
    }

    @Test
    fun removingTheCopyOfARestoredRowLeavesItDone() {
        // Its original is back on the phone; calling the row freed would
        // be a wrong label on a file that is there.
        assertEquals(ItemState.FREED.name, KeptCopies.stateAfterRemoval(ItemState.FREED_KEPT.name))
        assertEquals(ItemState.DONE.name, KeptCopies.stateAfterRemoval(ItemState.DONE.name))
        assertEquals(ItemState.FREED.name, KeptCopies.stateAfterRemoval(ItemState.FREED.name))
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
