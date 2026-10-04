package app.cloudsaver

import android.content.Intent
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
import app.cloudsaver.util.AppLooks
import app.cloudsaver.util.Notifications

class HostActivity : AppCompatActivity() {

    private val vm: AppViewModel by viewModels()

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            vm.onMediaChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    override fun onNewIntent(intent: Intent) {
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
        // A new home-screen name or icon is put on only now, with the app out
        // of sight: switching the launcher entry the app was opened from can
        // close it, which is no way to answer a tap in Settings. Not on a
        // rotation, which stops and starts the same screen.
        if (!isChangingConfigurations) AppLooks.applyPending(this)
    }
}
