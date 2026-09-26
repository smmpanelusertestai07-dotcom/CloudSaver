package com.pocketide.ui.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.IntentSender
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketide.core.Redact
import com.pocketide.docs.DocLinks
import com.pocketide.docs.OwnerSetUp
import com.pocketide.google.DriveAuth
import com.pocketide.google.DriveAuthResult
import com.pocketide.ui.components.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

private sealed interface DriveLink {
    data object Idle : DriveLink
    data object Working : DriveLink
    data object Cancelled : DriveLink
    /** [helpSection]: the Help page that has the fix, when the owner has something to set up. */
    data class Failed(val why: String, val helpSection: String? = null) : DriveLink
    data object Done : DriveLink
}

/** The Help page with the fix for a failed Drive sign-in, or null when trying again is the fix. */
internal fun helpFor(failed: DriveAuthResult.Failed): String? = OwnerSetUp.googleCloud.id.takeIf { failed.unknownBuild }

/**
 * Google's own consent sheet for `drive.appdata`: ask silently first, show Google's sheet only
 * when it is needed, then finish with its result. Used by set-up and by the "Drive disconnected"
 * lock, where Help ([onOpenHelp]) is the only way to the steps for a build Google does not know.
 */
@Composable
fun DriveConnectPanel(
    auth: DriveAuth,
    onAuthorized: (email: String?) -> Unit,
    onOpenHelp: (sectionId: String) -> Unit,
    label: String = "Continue with Google",
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var link by remember { mutableStateOf<DriveLink>(DriveLink.Idle) }
    // Assigned below; the consent result and the request that opens it refer to each other.
    lateinit var consent: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>

    fun handle(result: DriveAuthResult) {
        when (result) {
            is DriveAuthResult.Authorized -> {
                link = DriveLink.Done
                onAuthorized(result.email)
            }
            is DriveAuthResult.NeedsConsent -> {
                try {
                    External.leaving(context)
                    consent.launch(IntentSenderRequest.Builder(result.intent.intentSender).build())
                } catch (_: ActivityNotFoundException) {
                    link = DriveLink.Failed("Google's sign-in could not open. Update Google Play services and try again.")
                } catch (_: IntentSender.SendIntentException) {
                    link = DriveLink.Failed("Google's sign-in could not open. Try again.")
                }
            }
            is DriveAuthResult.Failed -> link = DriveLink.Failed(Redact.text(result.why), helpFor(result))
        }
    }

    fun run(block: suspend () -> DriveAuthResult) {
        link = DriveLink.Working
        scope.launch {
            try {
                handle(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                link = DriveLink.Failed("No connection to Google. Check the internet and try again.")
            } catch (_: Exception) {
                link = DriveLink.Failed("Google Drive could not be connected. Try again in a minute.")
            }
        }
    }

    consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            run { auth.completeConsent(result.data) }
        } else {
            link = DriveLink.Cancelled
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val current = link) {
            DriveLink.Cancelled -> NoticeCard("Google's window was closed before you allowed access. Nothing changed.", Tone.WARN)
            // Selectable: a build Google does not know shows its package and SHA-1 for the owner to copy.
            is DriveLink.Failed -> {
                SelectionContainer { NoticeCard(current.why, Tone.ERROR) }
                current.helpSection?.let { section ->
                    SecondaryAction("Open the set-up steps", onClick = { onOpenHelp(section) })
                    QuietAction("Google Cloud console: Clients", onClick = { External.openUrl(context, DocLinks.GOOGLE_CLOUD_CLIENTS) })
                }
            }
            else -> Unit
        }
        if (link != DriveLink.Done) {
            PrimaryAction(
                text = if (link is DriveLink.Failed || link == DriveLink.Cancelled) "Try again" else label,
                onClick = { run { auth.authorize() } },
                enabled = enabled,
                busy = link == DriveLink.Working,
            )
        }
    }
}
