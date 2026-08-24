package app.cloudsaver.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats

/**
 * "Try it on a few photos first", in one place so setup and Home cannot drift.
 *
 * It lives on Home as well as in setup because skipping it during setup used
 * to lose it for good, and the trial is the cheapest possible answer to "what
 * will this actually do to my photos". It stops being offered once there are
 * real results to look at instead: at that point the Files screen shows every
 * before-and-after there is, and a trial would be proving something already
 * proven.
 *
 * [size] is how many photos are genuinely waiting, capped at the trial size.
 * The button used to promise three whatever was there.
 */
@Composable
fun TrialCard(
    size: Int,
    running: Boolean,
    results: List<AppViewModel.TestItem>?,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.trial_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            if (size <= 0) {
                stringResource(R.string.trial_none)
            } else {
                pluralStringResource(R.plurals.trial_body, size, size)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (size > 0) {
            OutlinedButton(
                enabled = !running,
                onClick = onRun,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                if (running) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.onb6_running))
                } else {
                    Text(pluralStringResource(R.plurals.trial_action, size, size))
                }
            }
        }
        results?.takeIf { it.isNotEmpty() }?.let { list ->
            Column(Modifier.padding(top = 10.dp)) {
                Text(
                    stringResource(R.string.trial_done),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                for (item in list) {
                    KeyValueRow(
                        item.name,
                        stringResource(
                            R.string.files_size_saving,
                            Formats.bytes(item.before),
                            Formats.bytes(item.after),
                            Formats.percentOf(
                                (item.before - item.after).coerceAtLeast(0), item.before
                            )
                        )
                    )
                }
                // What the trial is really for: the quality that survived it.
                val before = list.sumOf { it.before }
                val after = list.sumOf { it.after }
                if (before > 0) {
                    Text(
                        stringResource(
                            R.string.trial_kept,
                            Formats.bytes(before - after),
                            Formats.percentOf(before - after, before)
                        ),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // The pixels the encoder really kept on these very files. It
                // replaces a sentence that used to promise they "still look
                // the same", which was a claim nobody could check.
                val measured = list.mapNotNull { it.keptPercent }
                if (measured.isNotEmpty()) {
                    Text(
                        stringResource(R.string.trial_kept_detail, measured.average().toInt()),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
