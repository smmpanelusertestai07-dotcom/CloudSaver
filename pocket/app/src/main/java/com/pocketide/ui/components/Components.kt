package com.pocketide.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketide.ui.theme.LocalStatusColors

enum class Tone { OK, WARN, ERROR, NEUTRAL }

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.OK -> LocalStatusColors.current.ok
    Tone.WARN -> LocalStatusColors.current.warn
    Tone.ERROR -> LocalStatusColors.current.error
    Tone.NEUTRAL -> LocalStatusColors.current.neutral
}

/** A titled group of rows. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A label on the left, a value on the right. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** How strongly a status chip is tinted with its own colour. */
const val STATUS_CHIP_TINT = 0.14f

/** A small coloured status label. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Surface(modifier = modifier, color = color.copy(alpha = STATUS_CHIP_TINT), shape = MaterialTheme.shapes.small) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/**
 * Long text the owner may want to copy or share: a platform TextView with native selection,
 * so Android's own toolbar offers Copy, Select all, Share and Translate. No copy buttons.
 */
@Composable
fun SelectableText(text: CharSequence, modifier: Modifier = Modifier, sizeSp: Float = 15f) {
    val color = MaterialTheme.colorScheme.onSurface
    val argb = android.graphics.Color.argb(
        (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
    )
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                setTextIsSelectable(true)
                textSize = sizeSp
                setLineSpacing(0f, 1.2f)
            }
        },
        update = {
            it.text = text
            it.setTextColor(argb)
        },
    )
}

/** Temporary body for a screen whose module has not landed yet. */
@Composable
fun PendingScreen(name: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) { Text(name, style = MaterialTheme.typography.titleLarge) }
}
