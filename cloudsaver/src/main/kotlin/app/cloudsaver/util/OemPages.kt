package app.cloudsaver.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The system pages the app sends the person to: app info, notifications,
 * usage access, Battery Saver and Android's own battery-optimisation question,
 * each with the page that holds the same switch as its fallback. The phone
 * makers' own pages are in [PowerPages].
 */
object OemPages {

    /**
     * Starts one outside page as a trip the app sent the person on, and takes
     * the trip back if the page refuses to open.
     */
    private fun go(context: Context, intent: Intent): Boolean {
        Errand.begin()
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Errand.cancel()
            false
        }
    }

    /**
     * CloudSaver is a background media pipeline the user explicitly sets up;
     * that is the accepted use case for this dialog.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean =
        go(
            context,
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        ) ||
            // Some skins strip the per-app dialog; the system's own list of
            // optimised apps still exists everywhere and is one tap from the
            // switch, which app info is not.
            go(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppInfo(context)

    fun openAppInfo(context: Context): Boolean = go(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )

    /** The system page that holds this app's notification switch. */
    fun openNotificationSettings(context: Context): Boolean = go(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    ) || openAppInfo(context)

    /**
     * The switch that lets Android take this app's permissions away for not
     * being opened (Android 11: "Remove permissions if app isn't used";
     * 12 and later: "Pause app activity if unused"). Android has a page for
     * exactly this one, and app info as the fallback.
     */
    fun openAutoRevokeSettings(context: Context): Boolean =
        // Some skins do not carry the page; app info holds the same switch.
        (
            Build.VERSION.SDK_INT >= 30 &&
                go(
                    context,
                    Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.parse("package:${context.packageName}"))
                )
            ) ||
            openAppInfo(context)

    /** The phone-wide Battery Saver page. */
    fun openBatterySaverSettings(context: Context): Boolean =
        go(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) ||
            go(context, Intent(Settings.ACTION_SETTINGS))

    /** The system page for one notification category - the Alerts one. */
    fun openAlertsChannelSettings(context: Context): Boolean = go(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CH_ALERTS)
    ) || openNotificationSettings(context)

    fun openUsageAccess(context: Context): Boolean =
        go(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}
