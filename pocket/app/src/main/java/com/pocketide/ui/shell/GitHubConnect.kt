package com.pocketide.ui.shell

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.github.DeviceCode
import com.pocketide.github.GitHubAccount
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * GitHub's device-code sign-in: show the code, open GitHub in Chrome, and wait at the pace GitHub
 * sets until the owner approves, denies, or the code expires. Used by set-up and by the "GitHub
 * disconnected" lock. The sign-in itself lives in [DeviceSignIn], so it survives the app lock
 * covering this screen while the owner is in Chrome.
 */
@Composable
fun GitHubConnectPanel(
    signIn: DeviceSignIn,
    openUrl: (String) -> Unit,
    onConnected: (GitHubAccount) -> Unit,
    startLabel: String = "Connect GitHub",
) {
    val state by signIn.state.collectAsState()
    val connected = (state as? DeviceSignIn.State.Connected)?.account
    // Handed over once, then the sign-in is cleared for the next time.
    LaunchedEffect(connected) {
        if (connected != null) {
            onConnected(connected)
            signIn.reset()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val current = state) {
            DeviceSignIn.State.Idle -> PrimaryAction(startLabel, onClick = signIn::start)
            DeviceSignIn.State.Starting -> PrimaryAction("Getting a code…", onClick = {}, busy = true)
            is DeviceSignIn.State.Waiting -> WaitingForApproval(current.code, current.offline, openUrl)
            DeviceSignIn.State.Denied -> Retry("You pressed Cancel on GitHub. Nothing was connected.", signIn::start)
            DeviceSignIn.State.Expired -> Retry("The code expired before it was used. Get a new one.", signIn::start)
            is DeviceSignIn.State.Failed -> Retry(current.why, signIn::start)
            is DeviceSignIn.State.Connected -> Unit
        }
    }
}

@Composable
private fun WaitingForApproval(code: DeviceCode, offline: Boolean, openUrl: (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(code) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    OutlinedCard {
        Column(Modifier.padding(20.dp)) {
            Text("Your one-time code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectableText(
                text = codeText(code.userCode),
                sizeSp = 34f,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = "Code ${code.userCode.toList().joinToString(" ")}" },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(
                    if (offline) "No connection, still trying" else "Waiting for you on GitHub",
                    if (offline) Tone.WARN else Tone.NEUTRAL,
                )
                Text(
                    "Expires in ${Formats.countdown(code.expiresAtMs - now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "Open GitHub, sign in if asked, and type this code. Long-press the code to copy it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryAction("Open GitHub", onClick = { openUrl(code.verificationUri) })
    FinePrint("Official GitHub sign-in. PocketIDE never sees your password.")
}

@Composable
private fun Retry(message: String, onRetry: () -> Unit) {
    NoticeCard(message, Tone.WARN)
    PrimaryAction("Try again", onClick = onRetry)
}

/** The signed-in account, as set-up and Settings show it. */
@Composable
fun GitHubAccountCard(account: GitHubAccount) {
    OutlinedCard {
        Row(
            Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GitHubAvatar(account.login, account.avatarUrl, size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(account.name ?: account.login, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (account.name != null) {
                    Text("@${account.login}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StatusChip("Connected", Tone.OK)
        }
    }
}

private fun codeText(userCode: String): CharSequence = SpannableString(userCode).apply {
    setSpan(TypefaceSpan("monospace"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
