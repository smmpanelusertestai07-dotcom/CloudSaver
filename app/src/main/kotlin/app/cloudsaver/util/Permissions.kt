package app.cloudsaver.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Permissions {

    /**
     * How much of the gallery the app can actually see.
     *
     * The three are not interchangeable. Under PARTIAL - Android 14's "Select
     * photos" - MediaStore answers every query as if the handful the user
     * picked were the whole library, and the app used to believe it: counts,
     * the calculator and the queue all reported a gallery of nine photos as
     * fact. A grant for photos but not videos - which Android 13 and later
     * ask for separately - hides just as much and counts as PARTIAL too.
     * Anything that scans, counts or projects must ask for this level;
     * [hasMediaRead] stays only for "can we read anything at all".
     */
    enum class MediaAccess { FULL, PARTIAL, NONE }

    fun mediaAccess(context: Context): MediaAccess = mediaAccessFor(
        sdk = Build.VERSION.SDK_INT,
        imagesGranted = granted(context, Manifest.permission.READ_MEDIA_IMAGES),
        videoGranted = granted(context, Manifest.permission.READ_MEDIA_VIDEO),
        userSelectedGranted = Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        legacyReadGranted = granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    )

    /** The decision alone, so the API 29/33/34 mapping is testable on the JVM. */
    fun mediaAccessFor(
        sdk: Int,
        imagesGranted: Boolean,
        videoGranted: Boolean,
        userSelectedGranted: Boolean,
        legacyReadGranted: Boolean
    ): MediaAccess = when {
        sdk >= 33 -> when {
            // Both halves of the gallery, or it is not a full view of it.
            // Android 13 split the old single read permission into photos and
            // videos, and the system asks for them one after the other, so
            // "photos yes, videos no" is one tap away - and a phone that had
            // only granted videos used to read as FULL. MediaStore then
            // answered every query as if the missing half were not there: the
            // photos the user can see in their own gallery were absent from
            // the count, from the calculator's total and from the queue, with
            // nothing on screen admitting it. That is exactly the lie this
            // three-way level exists to prevent, so half a grant is PARTIAL
            // and the screens say so instead of quietly showing half a phone.
            imagesGranted && videoGranted -> MediaAccess.FULL
            imagesGranted || videoGranted -> MediaAccess.PARTIAL
            sdk >= 34 && userSelectedGranted -> MediaAccess.PARTIAL
            else -> MediaAccess.NONE
        }
        legacyReadGranted -> MediaAccess.FULL
        else -> MediaAccess.NONE
    }

    /** Can the app read any media at all - full or the user-selected few. */
    fun hasMediaRead(context: Context): Boolean =
        mediaAccess(context) != MediaAccess.NONE

    fun mediaPermissionsToRequest(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
