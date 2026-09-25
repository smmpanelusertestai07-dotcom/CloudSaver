package com.pocketide.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Installs an APK through a PackageInstaller session (ACTION_INSTALL_PACKAGE is deprecated).
 * Android answers through a broadcast only this app can receive: first "pending user action",
 * with the confirm screen to open, then how it ended.
 */
internal class SessionInstaller(context: Context) {
    private val app = context.applicationContext

    /** False when the owner must first allow PocketIDE to install apps; that settings page is opened. */
    fun mayInstall(activity: Activity): Boolean {
        if (app.packageManager.canRequestPackageInstalls()) return true
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
        return false
    }

    suspend fun install(activity: Activity, apk: File, done: (InstallResult) -> Unit) = withContext(Dispatchers.IO) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        var receiver: BroadcastReceiver? = null
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                receiver = listen(WeakReference(activity), sessionId, done)
                session.commit(statusIntent(sessionId).intentSender)
            }
        } catch (failed: IOException) {
            abandon(installer, sessionId, receiver)
            throw failed
        } catch (refused: SecurityException) {
            abandon(installer, sessionId, receiver)
            throw refused
        }
    }

    private fun abandon(installer: PackageInstaller, sessionId: Int, receiver: BroadcastReceiver?) {
        receiver?.let(app::unregisterReceiver)
        installer.abandonSession(sessionId)
    }

    /** One receiver per session, removed once Android says how it ended. */
    private fun listen(activity: WeakReference<Activity>, sessionId: Int, done: (InstallResult) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        (activity.get() ?: app).startActivity(confirm)
                        return
                    }
                }
                app.unregisterReceiver(this)
                done(result(status))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action()), ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    /** Android fills in the status, so the intent must be mutable where that is a choice (Android 12+). */
    private fun statusIntent(sessionId: Int): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(action()).setPackage(app.packageName)
        return PendingIntent.getBroadcast(app, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    private fun action() = "${app.packageName}.action.INSTALL_STATUS"

    private fun result(status: Int): InstallResult = when (status) {
        PackageInstaller.STATUS_SUCCESS -> InstallResult.Installed
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallResult.Failed("The phone does not have room for the update. Free some space and try again.")
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallResult.Failed("This update does not work on this phone.")
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallResult.Failed("Android found the downloaded update damaged. Download it again.")
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallResult.Failed("Android refused the update: it conflicts with the app you have.")
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallResult.Failed("Android or a phone policy blocked the install.")
        else -> InstallResult.Failed("Android's installer could not install the update.")
    }

    private companion object {
        const val APK_NAME = "base.apk"
    }
}
