package app.cloudsaver.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * CloudSaver palette: indigo primary (the brand), a cyan-blue secondary
 * and amber tertiary (attention). Full Material 3 role set so every component
 * gets correct contrast in both themes without per-widget alpha hacks.
 */

/**
 * Content colours for the brand gradient.
 *
 * The hero banner is the one surface whose colour does not change with the
 * theme, so its foreground cannot come from the colour scheme. These three
 * live here rather than as Color.White literals inside screens, so the app
 * has exactly one file that names a colour.
 */
val OnBrand = Color(0xFFFFFFFF)
val OnBrandMuted = Color(0xE6FFFFFF)
val OnBrandFaint = Color(0xBFFFFFFF)

// Brand tones used by gradients and the logo.
//
// Deliberately indigo through violet, with no green or cyan member. Those two
// were here, and every soft-edged glow drawn from them read on a dark screen
// as a green stain over whatever it fell behind - reported as a bug twice
// before the cause was found. Keeping them out of the palette is what stops
// the next gradient from reintroducing it.
val BrandIndigo = Color(0xFF5B63F0)
val BrandViolet = Color(0xFF4B3FD4)

val LightScheme = lightColorScheme(
    primary = Color(0xFF4B4DDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF120B63),
    inversePrimary = Color(0xFFC1C1FF),

    // The secondary family is a blue that belongs to the indigo brand, not a
    // mint green. The navigation bar draws its selected indicator from
    // secondaryContainer, and a green pill under an indigo header reads as a
    // rendering fault rather than as a selection.
    secondary = Color(0xFF2A5C9A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF0B1B33),

    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF261A00),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceTint = Color(0xFF4B4DDB),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFECF4),
    surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),

    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF3EFF7)
)

val DarkScheme = darkColorScheme(
    primary = Color(0xFFC1C1FF),
    onPrimary = Color(0xFF1F1B87),
    primaryContainer = Color(0xFF3835AF),
    onPrimaryContainer = Color(0xFFE2E0FF),
    inversePrimary = Color(0xFF4B4DDB),

    // See the light scheme: the same blue family, so the navigation indicator
    // belongs with everything above it.
    secondary = Color(0xFFA9C7FF),
    onSecondary = Color(0xFF102A4C),
    secondaryContainer = Color(0xFF2B4468),
    onSecondaryContainer = Color(0xFFD6E3FF),

    tertiary = Color(0xFFEBC248),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDF9B),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceTint = Color(0xFFC1C1FF),

    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A30),
    surfaceContainerHighest = Color(0xFF34353B),

    outline = Color(0xFF928F9A),
    outlineVariant = Color(0xFF47464F),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036)
)
