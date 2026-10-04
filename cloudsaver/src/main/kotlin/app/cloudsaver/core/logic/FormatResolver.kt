package app.cloudsaver.core.logic

/**
 * The format a photo is actually written in, for this photo on this phone.
 *
 * The setting says what the person would like; this says what can be done
 * without losing anything that matters:
 *
 *  - An Ultra HDR photo carries a gain map that only JPEG keeps, so it stays
 *    JPEG (Android 14 writes the gain map back; older versions cannot, and
 *    the photo is copied as it is rather than lose its HDR).
 *  - HEIC is used only where this phone has passed a real HEIC encode on its
 *    own hardware. A software encode on a budget phone takes many seconds a
 *    photo and heats it up, which is not a trade worth making silently.
 *  - WebP's small, sharp mode arrived in Android 11; before that WebP is
 *    written in the older mode, which is still lossy below quality 100.
 */
object FormatResolver {

    /** What to do with one photo. */
    sealed interface Decision {
        data class Encode(val format: PhotoFormat) : Decision
        /** Copied byte for byte; [reason] is shown in the file's details. */
        data class AsIs(val reason: String) : Decision
    }

    const val ULTRA_HDR_MIN_API = 34

    fun resolve(
        wanted: PhotoFormat,
        heicWorks: Boolean,
        isUltraHdr: Boolean,
        sdkInt: Int
    ): Decision {
        if (isUltraHdr) {
            return if (sdkInt >= ULTRA_HDR_MIN_API) {
                Decision.Encode(PhotoFormat.JPEG)
            } else {
                Decision.AsIs("ultra_hdr_kept")
            }
        }
        return Decision.Encode(
            when (wanted) {
                PhotoFormat.AUTO -> if (heicWorks) PhotoFormat.HEIC else PhotoFormat.JPEG
                // Asked for HEIC on a phone that cannot make it well: the
                // nearest thing that keeps the photo, said in the setting.
                PhotoFormat.HEIC -> if (heicWorks) PhotoFormat.HEIC else PhotoFormat.JPEG
                PhotoFormat.JPEG -> PhotoFormat.JPEG
                PhotoFormat.WEBP -> PhotoFormat.WEBP
            }
        )
    }

    /** The file extension a format is written with. */
    fun extensionOf(format: PhotoFormat): String = when (format) {
        PhotoFormat.HEIC -> "heic"
        PhotoFormat.WEBP -> "webp"
        else -> "jpg"
    }
}
