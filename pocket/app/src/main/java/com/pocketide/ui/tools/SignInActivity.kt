package com.pocketide.ui.tools

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.pocketide.MainActivity
import com.pocketide.cloudshell.SignInCatcher
import com.pocketide.cloudshell.SignInReturn
import com.pocketide.graph
import com.pocketide.ui.web.IdeTab

/**
 * Where a sign-in comes to PocketIDE from outside it; it shows nothing itself. Exported, so it only
 * acts on what a sign-in looks like:
 *  - pocketide://signin?u=<sign-in page>, from the bar on Antigravity's screen: PocketIDE listens
 *    on the phone for the page's return to localhost (SignInCatcher), then opens the page.
 *  - Share: a sign-in page that ended at "localhost refused to connect", in any browser, shared to
 *    PocketIDE: its return goes to Cloud Shell.
 */
class SignInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = graph.settings.settings.value.cloudAccount
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val page = intent.data?.getQueryParameter("u").orEmpty()
                val port = SignInReturn.pagePort(page)
                if (port != null) {
                    SignInCatcher.catchOn(port, account)
                    IdeTab.page(this, page, current = null)
                } else {
                    say("This is not a sign-in page PocketIDE can finish.")
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val back = LINK.findAll(text).firstNotNullOfOrNull { SignInReturn.address(it.value, account) }
                if (back != null) {
                    say("Finishing the sign-in in Cloud Shell…")
                    IdeTab.finishSignIn(back)
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } else {
                    say("PocketIDE finishes sign-ins: share the page that says \"localhost refused to connect\".")
                }
            }
        }
        finish()
    }

    private fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        val LINK = Regex("""http://[^\s"'<>]+""")
    }
}
