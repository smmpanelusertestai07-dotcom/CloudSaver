package app.cloudsaver.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Permissions {

    /** Full or partial (user-selected) media access counts as usable. */
    fun hasMediaRead(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                (Build.VERSION.SDK_INT >= 34 &&
                    granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

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
