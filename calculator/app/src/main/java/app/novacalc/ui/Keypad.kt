package app.novacalc.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class KeyColors(val container: Color, val content: Color)

@Composable
private fun keyColors(style: KeyStyle): KeyColors {
    val c = MaterialTheme.colorScheme
    return when (style) {
        KeyStyle.DIGIT -> KeyColors(c.surfaceContainerHigh, c.onSurface)
        KeyStyle.OPERATOR -> KeyColors(c.tertiaryContainer, c.onTertiaryContainer)
        KeyStyle.EQUALS -> KeyColors(c.primary, c.onPrimary)
        KeyStyle.CLEAR -> KeyColors(c.errorContainer, c.onErrorContainer)
        KeyStyle.FUNCTION -> KeyColors(c.secondaryContainer, c.onSecondaryContainer)
        KeyStyle.SCI -> KeyColors(c.surfaceContainerLow, c.onSurfaceVariant)
        KeyStyle.SCI_ACTIVE -> KeyColors(c.primaryContainer, c.onPrimaryContainer)
        KeyStyle.MEMORY -> KeyColors(c.surfaceContainer, c.onSurfaceVariant)
    }
}

/**
 * One key. Presses shrink it slightly and square its corners, the Material 3
 * expressive gesture, and fire a light haptic when the setting is on.
 */
@Composable
fun KeyButton(
    key: CalcKey,
    haptics: Boolean,
    onAction: (CalcAction) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 28.sp,
    restRadius: Dp = 26.dp,
) {
    val colors = keyColors(key.style)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "keyScale",
    )
    val radius by animateDpAsState(
        targetValue = if (pressed) restRadius / 2 else restRadius,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "keyRadius",
    )
    val view = LocalView.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(colors.container)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = colors.content),
                enabled = key.enabled,
                onClickLabel = key.description,
                role = Role.Button,
            ) {
                if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onAction(key.action)
            }
            .semantics { contentDescription = key.description }
            .testTag("key:" + key.description),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = colors.content.copy(alpha = if (key.enabled) 1f else 0.38f),
            fontSize = fontSize,
            fontWeight = if (key.style == KeyStyle.EQUALS || key.style == KeyStyle.OPERATOR) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun KeyGrid(
    rows: List<List<CalcKey>>,
    haptics: Boolean,
    onAction: (CalcAction) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 28.sp,
    restRadius: Dp = 26.dp,
    gap: Dp = 10.dp,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach { key ->
                    KeyButton(
                        key = key,
                        haptics = haptics,
                        onAction = onAction,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        fontSize = fontSize,
                        restRadius = restRadius,
                    )
                }
            }
        }
    }
}

@Composable
fun BasicPad(haptics: Boolean, onAction: (CalcAction) -> Unit, modifier: Modifier = Modifier) {
    KeyGrid(rows = BasicKeys, haptics = haptics, onAction = onAction, modifier = modifier, fontSize = 28.sp)
}

@Composable
fun ScientificPad(
    inverse: Boolean,
    angleUnit: app.novacalc.engine.AngleUnit,
    hasMemory: Boolean,
    haptics: Boolean,
    onAction: (CalcAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    KeyGrid(
        rows = scientificKeys(inverse, angleUnit, hasMemory),
        haptics = haptics,
        onAction = onAction,
        modifier = modifier.padding(bottom = 4.dp),
        fontSize = 16.sp,
        restRadius = 16.dp,
        gap = 6.dp,
    )
}
