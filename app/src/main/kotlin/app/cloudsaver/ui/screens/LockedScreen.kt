package app.cloudsaver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.lock_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            stringResource(R.string.lock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        // What the last attempt actually was. A silent re-showing of the same
        // screen after a lockout reads as a broken button.
        when (outcome) {
            app.cloudsaver.ui.Lock.Outcome.LockedOut -> Text(
                stringResource(R.string.lock_locked_out),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp)
            )
            app.cloudsaver.ui.Lock.Outcome.NoMethod -> Text(
                stringResource(R.string.lock_no_method),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            else -> Unit
        }
    }
}
