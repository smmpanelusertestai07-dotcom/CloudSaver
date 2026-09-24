package com.pocketide

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.pocketide.ui.PocketRoot

/**
 * The single activity. FLAG_SECURE keeps chats and code out of screenshots and the recents
 * thumbnail; the app lock itself is part of [PocketRoot].
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { PocketRoot(activity = this) }
    }
}
