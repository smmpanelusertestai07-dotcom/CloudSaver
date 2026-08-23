package app.cloudsaver.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cloudsaver.ui.theme.BrandCyan
import app.cloudsaver.ui.theme.BrandIndigo
import app.cloudsaver.ui.theme.BrandMint
import app.cloudsaver.ui.theme.LocalIsDarkTheme
import app.cloudsaver.ui.theme.MetricTextStyle

/**
 * The shared design system: one card style, one tile style, one selector.
 * Everything animates with the same spring so the app feels like one piece.
 */

private val CardShape = RoundedCornerShape(24.dp)

/** Shrinks slightly while pressed - cheap, universal touch feedback. */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pressScale"
    )
    return this.scale(scale)
}

/** App-wide background: theme surface plus two soft brand glows. */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current
    val glow = if (dark) 0.20f else 0.14f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .offset(x = (-90).dp, y = (-120).dp)
                .blur(90.dp)
                .background(BrandIndigo.copy(alpha = glow), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = 110.dp)
                .blur(90.dp)
                .background(BrandCyan.copy(alpha = glow * 0.8f), CircleShape)
        )
        content()
    }
}

/** Standard content card. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tonal: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    var box = modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.pressScale(interaction) else it }
        .clip(CardShape)
        .background(if (tonal) scheme.primaryContainer else scheme.surfaceContainer)
        .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), CardShape)
    if (onClick != null) {
        box = box.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }
    Column(modifier = box.padding(18.dp), content = content)
}

/** Brand gradient card used for the dashboard hero. */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(
                Brush.linearGradient(listOf(BrandIndigo, BrandCyan.copy(alpha = 0.85f)))
            )
            .padding(20.dp),
        content = content
    )
}

/** A number that counts up when it changes. */
@Composable
fun AnimatedNumber(
    value: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MetricTextStyle,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn(tween(220)))
                .togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(160)))
        },
        label = "number",
        modifier = modifier
    ) { shown ->
        Text(shown, style = style, color = color, maxLines = 1)
    }
}

/** One dashboard metric: big number, small caption. */
@Composable
fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (highlight) scheme.secondaryContainer else scheme.surfaceContainer)
            .border(
                1.dp,
                scheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(20.dp)
            )
            .padding(vertical = 16.dp, horizontal = 12.dp)
            .clearAndSetSemantics {
                contentDescription = "$label: $value"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedNumber(
            value = value,
            style = MaterialTheme.typography.headlineMedium,
            color = if (highlight) scheme.onSecondaryContainer else scheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = if (highlight) {
                scheme.onSecondaryContainer.copy(alpha = 0.8f)
            } else {
                scheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Tappable status pill (needs attention). */
@Composable
fun StatusChip(text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .pressScale(interaction)
            .clip(CircleShape)
            .background(scheme.tertiaryContainer)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(scheme.tertiary, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onTertiaryContainer,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp)
    )
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1.2f)
                .padding(start = 12.dp)
        )
    }
}

/** Segmented selector with an animated, filled selection. */
@Composable
fun SegmentedChoice(
    options: List<Pair<String, String>>, // value to label
    selected: String,
    onSelect: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for ((value, label) in options) {
            val active = value == selected
            val alpha by animateFloatAsState(
                targetValue = if (active) 1f else 0f,
                animationSpec = tween(200),
                label = "segment"
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = alpha))
                    .clickable(
                        interactionSource = interaction,
                        indication = null
                    ) { onSelect(value) }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/** Inline note used for warnings under an option. */
@Composable
fun WarningNote(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.tertiaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onTertiaryContainer
        )
    }
}

/** Friendly empty state instead of a blank screen. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(
                    Brush.linearGradient(listOf(BrandIndigo, BrandMint)),
                    CircleShape
                )
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = LocalContentColor.current,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
