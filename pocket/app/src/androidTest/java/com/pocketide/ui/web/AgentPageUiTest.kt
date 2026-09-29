package com.pocketide.ui.web

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.ide.IdeState
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One agent screen replacing another (Claude's, then Codex's): Compose makes the new screen's view,
 * which takes the page, before it disposes of the old screen. The old screen going away must leave
 * the page where it is now; taking it out left the new screen blank, and the companion, seeing no
 * screen, took no more requests.
 */
@RunWith(AndroidJUnit4::class)
class AgentPageUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theScreenThatTookThePageKeepsIt() {
        compose.runOnUiThread {
            val activity = compose.activity
            val page = AgentPage(activity.applicationContext)
            // Nothing answers on port 9: the page fails to load, which does not matter here.
            val server = IdeState.On(port = 9, token = "test")
            val first = FrameLayout(activity)
            val second = FrameLayout(activity)
            try {
                val view = page.attach(activity, server, FOLDER, FirstScreen).also(first::addView)
                page.attach(activity, server, FOLDER, SecondScreen).also(second::addView)
                page.detach(FirstScreen)
                assertSame("The page stays on the screen that took it", second, view.parent)
                page.detach(SecondScreen)
                assertNull("The last screen going away takes the page out", view.parent)
            } finally {
                page.release()
            }
        }
    }

    private open class Screen : PageHost {
        override fun openInChrome(url: String, fromTap: Boolean) = Unit

        override fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
            callback.onReceiveValue(null)
        }

        override fun downloadRefused() = Unit

        override fun onPageState(state: PageState) = Unit
    }

    private object FirstScreen : Screen()

    private object SecondScreen : Screen()

    private companion object {
        const val FOLDER = "/root/projects/test"
    }
}
