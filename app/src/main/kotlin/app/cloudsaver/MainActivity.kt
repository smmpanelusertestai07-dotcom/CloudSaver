package app.cloudsaver

import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import app.cloudsaver.ui.App
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.screens.RecoveryScreen
import app.cloudsaver.util.CrashLog
import app.cloudsaver.util.Notifications

class MainActivity : AppCompatActivity() {

    private val vm: AppViewModel by viewModels()

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            vm.onMediaChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A launch that dies twice in a row within seconds is a launch that
        // would die a third time. Composing the same screen again would make
        // the app a dead icon with its own log out of reach; the recovery
        // page keeps the log shareable and offers the retry as a choice.
        CrashLog.noteLaunchStarted(this)
        if (CrashLog.startupCrashStreak(this) >= CrashLog.RECOVERY_AFTER) {
            setContent {
                RecoveryScreen(
                    onTryAgain = {
                        CrashLog.clearStartupStreak(this)
                        recreate()
                    }
                )
            }
            return
        }
        // Alive past the window, the streak is over: the next crash starts
        // its own count rather than adding to an old one.
        Handler(Looper.getMainLooper()).postDelayed(
            { if (!isFinishing) CrashLog.clearStartupStreak(this) },
            CrashLog.STARTUP_WINDOW_MS
        )
        vm.consumeDeepLink(intent?.getStringExtra(Notifications.EXTRA_ROUTE))
        setContent {
            App(vm)
        }
    }

    /**
     * An alert tapped while the app is already open reuses this instance, so
     * the route arrives here rather than in onCreate. Without this the second
     * tap of the day would just bring up whatever screen was last shown.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.consumeDeepLink(intent.getStringExtra(Notifications.EXTRA_ROUTE))
    }

    override fun onStart() {
        super.onStart()
        vm.noteScreenOn()
        // Watch the output folder while foreground (part of MaintainWorker's spec).
        try {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
            )
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
            )
        } catch (e: Exception) {
            // Observer is an optimization only.
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()
    }

    override fun onStop() {
        super.onStop()
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (e: Exception) {
            // ignore
        }
    }
}
