package app.entesaver

import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import app.entesaver.ui.App
import app.entesaver.ui.AppViewModel
import app.entesaver.util.AppLooks
import app.entesaver.util.Errand
import app.entesaver.util.Formats
import app.entesaver.util.Notifications

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
        // Only on a real start: a rotation, fold or window resize recreates
        // this activity with the same intent, and taking its route again
        // threw the person back to the alert's screen every time they turned
        // the phone.
        if (savedInstanceState == null) takeRoute(intent)
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
        takeRoute(intent)
    }

    /** Opens the screen an alert or shortcut asked for, once: the route is spent. */
    private fun takeRoute(intent: Intent?) {
        vm.consumeDeepLink(intent?.getStringExtra(Notifications.EXTRA_ROUTE))
        intent?.removeExtra(Notifications.EXTRA_ROUTE)
    }

    /**
     * The app itself opened another screen - a file picker, a settings page,
     * Ente - and is coming back to it. Every launch from this activity, for
     * a result or not, passes through [startActivityForResult].
     */
    private var sentOut = false

    @Suppress("OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        sentOut = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStart() {
        super.onStart()
        sentOut = false
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
        // The phone's clock setting can change while the app is away.
        Formats.clock24 = DateFormat.is24HourFormat(this)
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
        // rotation, which stops and starts the same screen, and not while the
        // app has sent the person somewhere it expects them back from - the
        // switch would close the file picker a backup is being saved through.
        if (!isChangingConfigurations && !sentOut && !Errand.expecting()) AppLooks.applyPending(this)
    }
}
