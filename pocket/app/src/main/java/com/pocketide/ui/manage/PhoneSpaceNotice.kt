package com.pocketide.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketide.sync.PhoneSpace
import com.pocketide.sync.StorageSummary
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import kotlinx.coroutines.launch

/** The words of the "phone limit reached" notice (§6.5). */
object PhoneSpaceText {
    /** The notice for [storage], or null while PocketIDE's share of the phone is fine. */
    fun of(storage: StorageSummary): String? {
        val used = ManageFormat.bytes(storage.phoneBytes)
        val limit = ManageFormat.bytes(storage.phoneLimitBytes)
        val free = ManageFormat.bytes(storage.phoneFreeBytes)
        return when (storage.phone) {
            PhoneSpace.OK -> null
            PhoneSpace.NEARLY_FULL -> "PocketIDE is using most of its space on this phone: $used of its $limit limit, $free free on the phone."
            PhoneSpace.FULL -> "PocketIDE's space on this phone is full: $used of its $limit limit, $free free on the phone. " +
                "Clean now removes caches that are rebuilt when needed."
        }
    }

    fun cleaned(freed: Long): String =
        if (freed > 0) "Freed ${ManageFormat.bytes(freed)}." else "Nothing more to clean now. Raise the limit or delete old sessions."
}

/**
 * Shown on Home and Your data when PocketIDE's share of the phone passes 80 %: Clean now, raise
 * the limit, or delete old sessions ([onLargest], when the screen has no list of them itself).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneSpaceNotice(
    storage: StorageSummary,
    clean: suspend () -> Long,
    onRaise: () -> Unit,
    onLargest: (() -> Unit)?,
    say: (String) -> Unit,
) {
    val text = PhoneSpaceText.of(storage) ?: return
    val scope = rememberCoroutineScope()
    var cleaning by remember { mutableStateOf(false) }
    val color = toneColor(if (storage.phone == PhoneSpace.FULL) Tone.ERROR else Tone.WARN)
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.SdStorage, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !cleaning,
                    onClick = {
                        cleaning = true
                        scope.launch {
                            attempt { clean() }.fold({ say(PhoneSpaceText.cleaned(it)) }, { say(PlainError.of(it)) })
                            cleaning = false
                        }
                    },
                ) { Text(if (cleaning) "Cleaning…" else "Clean now") }
                TextButton(onClick = onRaise) { Text("Raise the limit") }
                onLargest?.let { TextButton(onClick = it) { Text("Largest sessions") } }
            }
        }
    }
}
