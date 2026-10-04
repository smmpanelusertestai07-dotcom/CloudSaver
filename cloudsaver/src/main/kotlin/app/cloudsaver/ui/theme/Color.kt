package app.cloudsaver.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Ente Saver palette: Ente's green as the primary (the brand, the same green
 * as the app icon), a quiet sage secondary, amber tertiary (attention), and
 * near-neutral surfaces so the green is an accent rather than a wash. Built
 * from Ente's green with Material's colour tools: primary at full strength
 * (Fidelity), containers and secondary soft (Tonal Spot), surfaces neutral.
 * Full Material 3 role set so every component gets correct contrast in both
 * themes without per-widget alpha hacks.
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

/**
 * Ente's own green, for Ente's tile while Ente is not on the phone - once it
 * is, its real icon is drawn instead. A brand looks the same in both themes,
 * so this does not come from the scheme; [OnBrand] is drawn on it.
 */
val EnteGreen = Color(0xFF08C225)

// The two ends of the hero banner's gradient: Ente's green taken deep enough
// to carry white text (5.1:1 and 8.5:1). Nothing soft-edged is drawn from
// them - a blurred green glow reads on a dark screen as a stain over whatever
// it falls behind, which is why the page has no glows at all.
val BrandGreen = Color(0xFF08801F)
val BrandGreenDeep = Color(0xFF045A17)

val LightScheme = lightColorScheme(
    primary = Color(0xFF006E0F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBEF0B1),
    onPrimaryContainer = Color(0xFF265022),
    inversePrimary = Color(0xFF08C225),

    // The secondary family is a soft sage from the same green, not another
    // hue. The navigation bar draws its selected indicator from
    // secondaryContainer, and it has to belong with the green above it.
    secondary = Color(0xFF53634E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E8CD),
    onSecondaryContainer = Color(0xFF3C4B37),

    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF261A00),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFBF9F6),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFBF9F6),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE4E2DF),
    onSurfaceVariant = Color(0xFF474745),
    surfaceTint = Color(0xFF006E0F),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3F0),
    surfaceContainer = Color(0xFFF0EEEA),
    surfaceContainerHigh = Color(0xFFEAE8E5),
    surfaceContainerHighest = Color(0xFFE4E2DF),

    outline = Color(0xFF777774),
    outlineVariant = Color(0xFFC8C6C3),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF30312F),
    inverseOnSurface = Color(0xFFF2F0ED)
)

val DarkScheme = darkColorScheme(
    // Ente's own green itself: on a dark screen it is bright enough to read
    // (7.6:1 on the surface) without the neon of a lighter tone.
    primary = Color(0xFF08C225),
    onPrimary = Color(0xFF003A04),
    primaryContainer = Color(0xFF265022),
    onPrimaryContainer = Color(0xFFBEF0B1),
    inversePrimary = Color(0xFF006E0F),

    // See the light scheme: the same sage family, so the navigation indicator
    // belongs with everything above it.
    secondary = Color(0xFFBACCB2),
    onSecondary = Color(0xFF263422),
    secondaryContainer = Color(0xFF3C4B37),
    onSecondaryContainer = Color(0xFFD6E8CD),

    tertiary = Color(0xFFEBC248),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDF9B),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF131412),
    onBackground = Color(0xFFE4E2DF),
    surface = Color(0xFF131412),
    onSurface = Color(0xFFE4E2DF),
    surfaceVariant = Color(0xFF474745),
    onSurfaceVariant = Color(0xFFC8C6C3),
    surfaceTint = Color(0xFF08C225),

    surfaceContainerLowest = Color(0xFF0E0E0D),
    surfaceContainerLow = Color(0xFF1B1C1A),
    surfaceContainer = Color(0xFF1F201E),
    surfaceContainerHigh = Color(0xFF2A2A28),
    surfaceContainerHighest = Color(0xFF343533),

    outline = Color(0xFF91918E),
    outlineVariant = Color(0xFF474745),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE4E2DF),
    inverseOnSurface = Color(0xFF30312F)
)
