package com.pocketide

import android.content.Context
import com.pocketide.cloud.Computers
import com.pocketide.cloud.createComputers
import com.pocketide.core.Clock
import com.pocketide.core.KeystoreBox
import com.pocketide.core.SecureStore
import com.pocketide.core.SettingsStore
import com.pocketide.core.createSettingsStore
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubAuth
import com.pocketide.github.createGitHubApi
import com.pocketide.github.createGitHubAuth
import com.pocketide.ui.web.ComputerWebView
import com.pocketide.usage.UsageReporter
import com.pocketide.usage.createUsageReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The app's parts, made on first use. PocketIDE keeps no data of its own: GitHub holds the code,
 * the cloud computers and the agents' chats; this phone holds the sign-in and the settings.
 */
class AppGraph(val context: Context) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock.SYSTEM

    val secureStore: SecureStore by lazy { SecureStore(File(context.noBackupFilesDir, "secure"), KeystoreBox()) }
    val settings: SettingsStore by lazy { createSettingsStore(this) }

    val gitHubAuth: GitHubAuth by lazy { createGitHubAuth(this) }
    val gitHub: GitHubApi by lazy { createGitHubApi(this) }
    val computers: Computers by lazy { createComputers(this) }
    val usage: UsageReporter by lazy { createUsageReporter(this) }

    /** The computer screen's page, kept while the process lives. Main thread only. */
    val computerPage: ComputerWebView by lazy { ComputerWebView(context) }
}
