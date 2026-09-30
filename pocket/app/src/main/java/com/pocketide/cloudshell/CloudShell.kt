package com.pocketide.cloudshell

import com.pocketide.BuildConfig
import com.pocketide.agents.Agent
import com.pocketide.core.Settings
import java.net.URLEncoder

/**
 * Google Cloud Shell, PocketIDE's computer: Google's own Linux computer, free with a Google account.
 * Google allows its sign-in only in a real browser, so PocketIDE opens it in a Chrome tab, with the
 * account the owner picked in Android's own chooser. The set-up is one command, from a script
 * pinned by its SHA-256, that gives Claude Code and Codex each its own VS Code and Antigravity its
 * own screen, which Cloud Shell then starts by itself whenever it starts.
 */
object CloudShell {
    /** The commit that holds the setup script this app version gives out. */
    const val SCRIPT_COMMIT = "9f35ac9371d62c0a7a1a522535e51f2772ad0099"
    const val SCRIPT_PATH = "pocket/cloudshell/pocketide-cloudshell.sh"

    /** The script's SHA-256: the command runs it only when the download matches. */
    const val SCRIPT_SHA256 = "5f3e08546a925920a19ceb63eff00d9a95eff9ba5fc90db07521279e8c3f6388"
    val SCRIPT_URL = "https://raw.githubusercontent.com/${BuildConfig.RELEASES_REPO}/$SCRIPT_COMMIT/$SCRIPT_PATH"

    /** Google deletes Cloud Shell's home folder after this many days without use. */
    const val DELETED_AFTER_DAYS = 120

    /** PocketIDE asks for the set-up again this many days after it last opened Cloud Shell. */
    const val ASK_AGAIN_AFTER_DAYS = 110
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    const val NEW_ACCOUNT = "https://accounts.google.com/signup"
    const val MOBILE_APP = "https://play.google.com/store/apps/details?id=com.google.android.apps.cloudconsole"
    const val LIMITS = "https://docs.cloud.google.com/shell/docs/limitations"
    const val FILES = "https://docs.cloud.google.com/shell/docs/uploading-and-downloading-files"
    const val RESET = "https://docs.cloud.google.com/shell/docs/resetting-cloud-shell"
    const val TERMS = "https://cloud.google.com/terms"
    const val PRIVACY = "https://cloud.google.com/terms/cloud-privacy-notice"

    /** The one command to paste into Cloud Shell: download the script, check it, run it. */
    val setupCommand: String =
        "curl -fsSL -o ~/pocketide-cloudshell.sh $SCRIPT_URL && " +
            "echo \"$SCRIPT_SHA256  \$HOME/pocketide-cloudshell.sh\" | sha256sum -c - && " +
            "bash ~/pocketide-cloudshell.sh"

    /** The first agent's port; each next agent takes the next one, as the script sets them up. */
    private const val FIRST_PORT = 8080

    /** Each agent's port in Cloud Shell: Claude Code 8080, Codex 8081, Antigravity 8082. */
    fun port(agent: Agent): Int = FIRST_PORT + agent.ordinal

    fun projects(agent: Agent): String = when (agent) {
        Agent.CLAUDE -> "~/projects/claude-code"
        Agent.CODEX -> "~/projects/codex"
        Agent.ANTIGRAVITY -> "~/projects/antigravity"
    }

    /** Cloud Shell's terminal, with [account]. Opening it starts Cloud Shell, and with it the agents' VS Code. */
    fun terminal(account: String): String = "https://shell.cloud.google.com/?show=terminal" + authUser(account)

    /** Cloud Shell's own editor, where the home folder's files show. */
    fun editor(account: String): String = "https://shell.cloud.google.com/?show=ide%2Cterminal" + authUser(account)

    fun console(account: String): String = "https://console.cloud.google.com/" + authUser(account, first = true)

    /**
     * [agent] full screen, through Cloud Shell's Web Preview, which only [account] can open: Claude
     * Code's and Codex's own VS Code, and Antigravity's own screen, which the script's bridge shows.
     */
    fun screen(agent: Agent, account: String): String =
        webPreview(port(agent), if (agent == Agent.ANTIGRAVITY) "/pocketide/" else "/", account)

    /** [path] on Cloud Shell's [port], through Web Preview, which only [account] can open. */
    internal fun webPreview(port: Int, path: String, account: String): String =
        "https://ssh.cloud.google.com/devshell/proxy?authuser=${encode(account)}&port=$port" +
            "&cloudshell_retry=true&devshellProxyPath=${encode(path)}&environment_name=default&environment_id=default"

    /**
     * True until the owner has picked an account and finished the set-up, and again when PocketIDE
     * has not opened Cloud Shell for [ASK_AGAIN_AFTER_DAYS]: by then Google may have deleted the
     * home folder, and the set-up only adds what is missing.
     */
    fun needsSetUp(settings: Settings, now: Long): Boolean =
        settings.cloudAccount.isBlank() || settings.cloudSetUpAt == 0L || daysUnused(settings, now) >= ASK_AGAIN_AFTER_DAYS

    /** True when the owner's Cloud Shell was set up with another script than the one this app version gives out. */
    fun newerSetUp(settings: Settings): Boolean = settings.cloudScript != SCRIPT_COMMIT

    /** Whole days since PocketIDE last opened Cloud Shell (or finished its set-up). */
    fun daysUnused(settings: Settings, now: Long): Long {
        val last = maxOf(settings.cloudOpenedAt, settings.cloudSetUpAt)
        return if (last == 0L) 0 else ((now - last) / DAY_MS).coerceAtLeast(0)
    }

    private fun authUser(account: String, first: Boolean = false): String =
        if (account.isBlank()) "" else (if (first) "?" else "&") + "authuser=" + encode(account)

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
