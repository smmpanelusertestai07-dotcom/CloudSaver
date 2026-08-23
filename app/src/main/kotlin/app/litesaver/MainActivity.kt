package app.litesaver

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
import app.litesaver.ui.App
import app.litesaver.ui.AppViewModel

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
        setContent {
            App(vm)
        }
    }

    override fun onStart() {
        super.onStart()
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
