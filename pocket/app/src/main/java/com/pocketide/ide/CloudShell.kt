package com.pocketide.ide

import com.pocketide.BuildConfig
import java.net.URLEncoder

/**
 * Google Cloud Shell as a second computer: Google's own Linux computer, free with a Google
 * account. Google allows its sign-in only in a real browser, so PocketIDE opens Cloud Shell in a
 * Chrome tab, with Google's account chooser first so the owner picks the account, and gives the
 * one command that sets up VS Code and the three agents there from a script pinned by its SHA-256.
 */
object CloudShell {
    /** The commit that holds the setup script this app version gives out. */
    const val SCRIPT_COMMIT = "0652c8b79213cf94e1506ba6aa59ec41627837f6"
    const val SCRIPT_PATH = "pocket/cloudshell/pocketide-cloudshell.sh"

    /** The script's SHA-256: the command runs it only when the download matches. */
    const val SCRIPT_SHA256 = "344ac40bdd69ffd36f19dd1328309ec406e9a0d27c558dd1cecfe599f79d382b"
    val SCRIPT_URL = "https://raw.githubusercontent.com/${BuildConfig.RELEASES_REPO}/$SCRIPT_COMMIT/$SCRIPT_PATH"

    /** The port the script starts VS Code on: Cloud Shell's Web Preview port. */
    const val PORT = 8080

    const val TERMINAL = "https://shell.cloud.google.com/?show=terminal"
    const val EDITOR = "https://shell.cloud.google.com/?show=ide%2Cterminal"
    const val CONSOLE = "https://console.cloud.google.com/"
    const val MOBILE_APP = "https://play.google.com/store/apps/details?id=com.google.android.apps.cloudconsole"
    const val NEW_ACCOUNT = "https://accounts.google.com/signup"
    const val LIMITS = "https://docs.cloud.google.com/shell/docs/limitations"
    const val FILES = "https://docs.cloud.google.com/shell/docs/uploading-and-downloading-files"
    const val RESET = "https://docs.cloud.google.com/shell/docs/resetting-cloud-shell"
    const val TERMS = "https://cloud.google.com/terms"
    const val PRIVACY = "https://cloud.google.com/terms/cloud-privacy-notice"

    /** Google's account chooser, then [url]: Cloud Shell opens with the account the owner picks. */
    fun chooseAccountThen(url: String): String = "https://accounts.google.com/AccountChooser?continue=" + URLEncoder.encode(url, "UTF-8")

    /** The one command to paste into Cloud Shell: download the script, check it, run it. */
    val setupCommand: String =
        "curl -fsSL -o ~/pocketide-cloudshell.sh $SCRIPT_URL && " +
            "echo \"$SCRIPT_SHA256  \$HOME/pocketide-cloudshell.sh\" | sha256sum -c - && " +
            "bash ~/pocketide-cloudshell.sh"
}
