package app.cloudsaver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cloudsaver.R

@Composable
fun LockedScreen(
    modifier: Modifier = Modifier,
    outcome: app.cloudsaver.ui.Lock.Outcome? = null,
    onUnlock: () -> Unit
) {
    app.cloudsaver.ui.components.SecureScreen()
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        modifier = modifier
            .fillMaxSize()
            // The system bars are already paid for by the caller. A cutout in
            // the long edge is not: turned sideways the notch sits beside this
            // text rather than above it, and nothing else on this screen would
            // have moved out of its way.
            .displayCutoutPadding()
            // Centred while it fits, scrollable the moment it does not. This
            // is the whole app when the lock is on, and it is the state a
            // phone is most likely to be in sideways with the largest font
            // set - where a lockout message, a title and a button add up to
            // more than the height of the screen. Unscrolled, the button that
            // is the only way out of this screen was simply past the bottom
            // edge, which reads as an app that has hung.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Centred text, because at a large font every line here wraps, and a
        // wrapped line left-aligned under a centred one looks like a mistake.
        Text(
            stringResource(R.string.lock_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.lock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onUnlock) {
            Text(
                stringResource(R.string.lock_unlock),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // What the last attempt actually was. A silent re-showing of the same
        // screen after a lockout reads as a broken button.
        when (outcome) {
            app.cloudsaver.ui.Lock.Outcome.LockedOut -> Text(
                stringResource(R.string.lock_locked_out),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
            app.cloudsaver.ui.Lock.Outcome.NoMethod -> Text(
                stringResource(R.string.lock_no_method),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
            else -> Unit
        }
    }
}
