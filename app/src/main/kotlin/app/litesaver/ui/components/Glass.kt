package app.litesaver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * "Glass" look: soft gradient background with big blurred colour blobs
 * (RenderEffect via Modifier.blur on Android 12+, plain translucency below -
 * Modifier.blur is a no-op there) and frosted translucent cards. No animations
 * that cost battery.
 */
@Composable
fun GlassBackground(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(scheme.background, scheme.surfaceVariant, scheme.background)
                )
            )
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = (-60).dp, y = (-40).dp)
                .blur(70.dp)
                .background(scheme.primary.copy(alpha = 0.28f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 70.dp, y = 60.dp)
                .blur(80.dp)
                .background(scheme.secondary.copy(alpha = 0.22f), CircleShape)
        )
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(scheme.surface.copy(alpha = 0.62f))
        .border(1.dp, scheme.onSurface.copy(alpha = 0.08f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(modifier = m.padding(16.dp), content = content)
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surface.copy(alpha = 0.55f))
            .border(
                1.dp, scheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(20.dp)
            )
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = scheme.onSurfaceVariant
        )
    }
}

@Composable
fun HealthChip(text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.primaryContainer.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
fun PillChoice(
    options: List<Pair<String, String>>, // value to label
    selected: String,
    onSelect: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((value, label) in options) {
            val active = value == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) scheme.primary
                        else scheme.surface.copy(alpha = 0.5f)
                    )
                    .border(
                        1.dp,
                        if (active) Color.Transparent else scheme.onSurface.copy(alpha = 0.1f),
                        RoundedCornerShape(50)
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            )
        }
    }
}
