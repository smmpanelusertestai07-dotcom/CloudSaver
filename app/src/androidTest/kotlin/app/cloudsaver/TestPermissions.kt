package app.cloudsaver

import android.Manifest
import android.os.Build

/**
 * The gallery permissions this Android actually has.
 *
 * READ_MEDIA_IMAGES and READ_MEDIA_VIDEO were introduced in API 33; below
 * that the manifest asks for READ_EXTERNAL_STORAGE instead, capped at 32.
 * GrantPermissionRule cannot grant a permission the platform has never heard
 * of, so a suite that names the API-33 pair unconditionally cannot even start
 * on the oldest Android the app installs on - which is exactly the phone its
 * results would matter most for.
 */
object TestPermissions {

    /** Everything the suites need, named as this API level knows it. */
    fun forThisDevice(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()
}
