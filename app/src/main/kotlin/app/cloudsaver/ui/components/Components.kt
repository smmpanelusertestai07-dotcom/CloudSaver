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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cloudsaver.ui.theme.BrandIndigo
import app.cloudsaver.ui.theme.BrandViolet
import app.cloudsaver.ui.theme.Dimens
import app.cloudsaver.ui.theme.LocalIsDarkTheme
import app.cloudsaver.ui.theme.MetricTextStyle
import app.cloudsaver.ui.theme.OnBrand
import kotlinx.coroutines.launch

/**
 * The shared design system: one card style, one tile style, one selector.
 * Everything animates with the same spring so the app feels like one piece.
 */

private val CardShape = RoundedCornerShape(24.dp)

/**
 * The text size at which two things stop being able to share one line.
 *
 * Above it a row of [words][control] is drawn as the words with the control
 * underneath them, because at 150% and up on a narrow phone the two halves
 * each get a few words' width and both become unreadable - the label worst of
 * all, since it is the half that says what the control does. Below it nothing
 * changes at all, so an ordinary phone at an ordinary text size is drawn the
 * way it has always been drawn.
 *
 * It is shared rather than judged per component: rows that stack at different
 * moments make one screen look like two.
 */
internal const val StackedTextScale = 1.5f

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

/**
 * App-wide background: theme surface plus two soft brand glows.
 *
 * It also provides LocalContentColor. Material 3 leaves that to Surface, not
 * to MaterialTheme, so a plain Box root leaves every Text that does not name
 * its own colour painting in the default black - invisible on a dark
 * background, and correct-looking in light mode purely by accident.
 */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDarkTheme.current
    val glow = if (dark) 0.20f else 0.14f
    androidx.compose.runtime.CompositionLocalProvider(
        LocalContentColor provides scheme.onBackground
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        // The glows live in their own clipped layer behind the content.
        //
        // They used to be siblings of the content inside an unclipped Box, so
        // a blurred circle offset past the edge spilled over whatever sat
        // above it - most visibly as a green smear across the navigation bar,
        // which reads as a rendering fault rather than as depth. Clipping the
        // layer keeps the wash inside the page, and the second circle is now
        // the brand indigo: a cyan glow on a dark background is simply green.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RectangleShape)
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
                    .background(BrandViolet.copy(alpha = glow * 0.7f), CircleShape)
            )
        }
        content()
    }
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

/**
 * Brand gradient card used for the dashboard hero. Its content is white, so
 * both ends of the gradient have to carry white text: indigo is 4.7:1 and
 * violet 7.1:1, while the brand cyan would be 1.7:1 and unreadable.
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // The banner keeps its colours in both themes, so it hands its own
    // content colour down rather than letting screens name white themselves.
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides OnBrand) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(Brush.linearGradient(listOf(BrandIndigo, BrandViolet)))
                .padding(20.dp),
            content = content
        )
    }
}

/** A number that counts up when it changes. */
@Composable
fun AnimatedNumber(
    value: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MetricTextStyle,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    // Always one Text, always fully opaque, never moved.
    //
    // This used to cross-fade two of them: mid-transition the old and new
    // counts overlapped at partial alpha, which on a tile read as a faded
    // number smeared behind its own label - and on a slow count it never
    // settled. Only the digits change now; the text block does not.
    Text(
        value,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        // Without this the default is TextOverflow.Clip, so a number too wide
        // for its cell simply lost its end - and half a number is worse than
        // an obviously shortened one.
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** One dashboard metric: big number, small caption. */
/**
 * One count in the progress grid.
 *
 * Fixed height and a fixed-width figure style, because these tiles sit in a
 * 2x2 grid and a count going from 9 to 10 used to make its tile - and then
 * the whole grid - jump. A number that reflows while it counts reads as a
 * glitch, not as progress. Tapping explains what the count means.
 */
@Composable
fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    var box = modifier
        // A minimum, not a fixed height. The reason the height was pinned -
        // a count going 9 to 10 must not make the grid jump - is already
        // handled by the tabular figures on the number below, and pinning it
        // meant the label was cut off instead: at a large font the two lines
        // of "In upload folder" need more than the 126 dp box and the clip on
        // the next line sliced the second one away. The label is the half
        // that says what the number counts.
        .heightIn(min = TileHeight)
        .clip(RoundedCornerShape(20.dp))
        .background(if (highlight) scheme.secondaryContainer else scheme.surfaceContainer)
        .border(
            1.dp,
            scheme.outlineVariant.copy(alpha = 0.5f),
            RoundedCornerShape(20.dp)
        )
    if (onClick != null) {
        box = box.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }
    Column(
        modifier = box
            .padding(vertical = 14.dp, horizontal = 12.dp)
            .clearAndSetSemantics {
                contentDescription = "$label: $value"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The icon says what the number counts before the label is read. It
        // is decorative in the accessibility tree because the tile already
        // announces itself as "label: value".
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = if (highlight) {
                    scheme.onSecondaryContainer.copy(alpha = 0.8f)
                } else {
                    scheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp).padding(bottom = 2.dp)
            )
        }
        AnimatedNumber(
            value = value,
            style = MaterialTheme.typography.headlineSmall.copy(
                // Tabular figures: every digit the same width, so 9 becoming
                // 10 changes the number without moving the grid.
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold
            ),
            color = if (highlight) scheme.onSecondaryContainer else scheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            // One line for a single word, which has nowhere to wrap and would
            // otherwise be broken in half; two for a label that can wrap.
            maxLines = if (label.trim().contains(' ')) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            // A long label must shrink rather than push the number out of
            // the fixed cell.
            autoSize = TextAutoSize.StepBased(
                minFontSize = 9.sp,
                maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                stepSize = 0.5.sp
            ),
            color = if (highlight) {
                scheme.onSecondaryContainer.copy(alpha = 0.8f)
            } else {
                scheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/**
 * The progress grid.
 *
 * Every tile is the same cell whatever the row holds: three tiles are three
 * equal thirds, not two-and-a-stretched-one, and a single remaining tile is
 * centred at that same width rather than spanning the screen. A grid whose
 * cells resize as counts appear and disappear reads as a glitch.
 */
@Composable
fun MetricGrid(tiles: List<@Composable (Modifier) -> Unit>) {
    if (tiles.isEmpty()) return
    val perRow = if (tiles.size <= 3) tiles.size else 2
    for (row in tiles.chunked(perRow)) {
        Row(
            Modifier
                .fillMaxWidth()
                // IntrinsicSize.Min so a tile that had to grow for its label
                // lifts the others with it. Without this the tiles are all
                // still the same width but no longer the same height, which
                // is the ragged grid the fixed height was there to prevent.
                .height(IntrinsicSize.Min)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
            // Keep the last row's cells the same width as every other row's.
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * The height every progress tile starts at, and never goes below.
 *
 * A floor rather than a fixed size. It is generous enough for a big number
 * and two lines of label at ordinary font sizes, so the grid is even in the
 * normal case; when a label genuinely needs more - a long one at a large font
 * scale - the tile grows and takes its row with it, rather than clipping the
 * line that says what the number counts.
 */
val TileHeight = 126.dp

/**
 * One settings row: icon, title, one line of what it does, then the control.
 *
 * Every row has a leading icon, because a wall of text rows is the thing that
 * makes a settings screen feel like a form. The label and the control sit on
 * one line with a real gap between them and the label wraps rather than
 * sliding under the switch - which is exactly what it used to do at large
 * font sizes.
 */
@Composable
fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    var row = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
    if (onClick != null) {
        row = row.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    }
    Row(
        modifier = row.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp)
        ) {
            Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

/** A group of settings rows: one card, one header above it. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionHeader(title)
    AppCard(content = content)
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
                // A clipped option is a broken option: "Unlimited" showing as
                // "Unlimi" tells the user nothing. Shrinking alone was not
                // enough - "Photos and videos" across a third of the row still
                // ran out of space at the smallest size allowed and came out
                // as "Photos and vide" - so a label that will not fit on one
                // line wraps onto a second instead of losing its end.
                //
                // Only where there is a space to wrap at, though. Given two
                // lines to fill, the shrinking stops as soon as the text fits
                // across both, and a single long word simply breaks in half:
                // four options across a phone turned "Unlimited" into
                // "Unlimit" above "ed". A one-word label gets one line and has
                // to shrink until it really fits.
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = if (label.trim().contains(' ')) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 9.sp,
                        maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                        stepSize = 0.5.sp
                    )
                )
            }
        }
    }
}

/**
 * Rounded capacity bar that animates to its new value. [fraction] is how full
 * the volume is, so the filled part is what is already used.
 */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    warn: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "meter"
    )
    val fill = if (warn) scheme.error else scheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(fill.copy(alpha = 0.75f), fill)
                    )
                )
        )
    }
}

/** Severity a status badge should read as. */
enum class BadgeTone { NEUTRAL, PROGRESS, SUCCESS, MUTED }

/** Small filled pill that carries an item's state at a glance. */
@Composable
fun StateBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when (tone) {
        BadgeTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        BadgeTone.PROGRESS -> scheme.primaryContainer to scheme.onPrimaryContainer
        BadgeTone.SUCCESS -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BadgeTone.MUTED -> scheme.surfaceContainerHigh to scheme.outline
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
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
/**
 * The app's own mark, exactly as the launcher draws it.
 *
 * The flat vector that used to stand in for it here is the notification
 * icon: the system tints that to one colour, so it has no gradient, no
 * gloss and no navy behind the cloud. Inside the app there is nothing to
 * tint, so the real artwork goes in and the icon on the home screen and the
 * icon on the Home screen are finally the same object.
 */
@Composable
fun BrandMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = painterResource(app.cloudsaver.R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = modifier.size(size)
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandMark(size = 64.dp)
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

/**
 * Puts a folder path on the clipboard, and says so where the system does not.
 *
 * Returned as a callback so the two places that print a path - the setup
 * summary and the Storage screen - copy it identically.
 */
@Composable
fun rememberPathCopier(): (String) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val copied = androidx.compose.ui.res.stringResource(app.cloudsaver.R.string.path_copied)
    return { path ->
        scope.launch {
            clipboard.setClipEntry(
                androidx.compose.ui.platform.ClipEntry(
                    android.content.ClipData.newPlainText(copied, path)
                )
            )
            // Android 13 and up shows its own clipboard confirmation; a second
            // toast on top of it is noise.
            if (android.os.Build.VERSION.SDK_INT < 33) {
                val toast = android.widget.Toast.makeText(
                    context, copied, android.widget.Toast.LENGTH_SHORT
                )
                toast.show()
            }
        }
    }
}

/**
 * A folder path, with a one-tap copy.
 *
 * Paths are shown so they can be typed into a cloud app's folder picker, and
 * a path typed slightly wrong backs up nothing while looking correct. They are
 * set in the normal text face rather than monospace: a column of monospace
 * paths on a settings screen reads as a log file.
 */
@Composable
fun PathLine(path: String, modifier: Modifier = Modifier) {
    val copyPath = rememberPathCopier()
    val copyLabel = androidx.compose.ui.res.stringResource(app.cloudsaver.R.string.copy_path_action)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // No line limit on purpose. This path is here to be read and typed
            // into a cloud app's folder picker, and a path cut short still
            // looks like a path - so the user adds it, and the half that was
            // never shown is never backed up. Nothing bounds this row's
            // height, so it simply wraps.
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.IconButton(
            onClick = { copyPath(path) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.ContentCopy,
                contentDescription = copyLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * A short warning under the control it belongs to.
 *
 * Shared rather than re-declared per screen: an inconsistent warning colour
 * is how "this matters" quietly stops meaning anything.
 */
@Composable
fun WarningText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp)
    )
}
