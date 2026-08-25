package app.cloudsaver.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.cloudsaver.core.logic.ThemeMode

/** True when the app is currently painting its dark palette. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** Slightly tighter, more confident headings than the M3 defaults. */
private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        ),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.25).sp
        ),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp)
    )
}

/** Big numbers on the dashboard get their own style. */
val MetricTextStyle = TextStyle(
    fontSize = 34.sp,
    lineHeight = 40.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-1).sp,
    // Tabular figures: a counter that ticks 8 -> 9 must not change width.
    fontFeatureSettings = "tnum"
)

/**
 * Merge into any style that prints a size or a count. Proportional digits make
 * a column of sizes look ragged and make a live number jitter as it updates.
 */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun CloudSaverTheme(
    mode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val dark = isDarkTheme(mode)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkScheme
        else -> LightScheme
    }
    // The status-bar icon colour must follow the palette actually painted,
    // not the system's dark setting. enableEdgeToEdge()'s default follows the
    // system, so choosing the light theme on a dark-mode phone (or on OEMs
    // that resolve the two differently) drew white clock and icons over the
    // app's own light background - an unreadable status bar, reported from a
    // device. Driven here, beside the palette decision, the two cannot
    // disagree in any theme: light, dark, system or wallpaper colours.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            // The view's context can be a ContextThemeWrapper on some OEMs;
            // unwrap until the Activity appears rather than assuming.
            var ctx = view.context
            while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
                ctx = ctx.baseContext
            }
            val window = (ctx as? android.app.Activity)?.window ?: return@SideEffect
            val controller = androidx.core.view.WindowCompat
                .getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalIsDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
