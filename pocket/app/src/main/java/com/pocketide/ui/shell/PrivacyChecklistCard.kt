package com.pocketide.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The privacy checklist's rows: a tick box, what to switch, and the page in Chrome. */
@Composable
fun PrivacyChecklistCard(checked: Set<String>, onToggle: (id: String, on: Boolean) -> Unit, onOpen: (OutsideLink) -> Unit) {
    OutlinedCard {
        Links.privacyChecklist.forEachIndexed { index, link ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ChecklistRow(
                link = link,
                checked = link.id in checked,
                onToggle = { on -> onToggle(link.id, on) },
                onOpen = { onOpen(link) },
            )
        }
    }
}

@Composable
private fun ChecklistRow(link: OutsideLink, checked: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { stateDescription = if (checked) "Done" else "Not done" },
        )
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open page") { onOpen() }
                .padding(vertical = 4.dp),
        ) {
            Text(link.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(link.what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open ${link.title}", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
