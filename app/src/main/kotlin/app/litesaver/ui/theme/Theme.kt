package app.litesaver.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.litesaver.core.logic.ThemeMode

private val Teal = Color(0xFF1FA8D4)
private val TealDeep = Color(0xFF0E7396)
private val Ice = Color(0xFFDFF4FB)
private val Night = Color(0xFF0B1722)
private val NightSurface = Color(0xFF12222F)

private val LightScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = Ice,
    onPrimaryContainer = Color(0xFF06303F),
    secondary = Color(0xFF3E6373),
    surface = Color(0xFFF4FAFD),
    onSurface = Color(0xFF14232B),
    surfaceVariant = Color(0xFFE2EEF3),
    onSurfaceVariant = Color(0xFF41535B),
    background = Color(0xFFEFF7FB),
    error = Color(0xFFB3261E)
)

private val DarkScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00232F),
    primaryContainer = Color(0xFF0D3D4F),
    onPrimaryContainer = Ice,
    secondary = Color(0xFF9BC2D1),
    surface = NightSurface,
    onSurface = Color(0xFFDCE9EF),
    surfaceVariant = Color(0xFF1B2E3B),
    onSurfaceVariant = Color(0xFFA9BEC8),
    background = Night,
    error = Color(0xFFF2B8B5)
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun LiteSaverTheme(
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
    MaterialTheme(colorScheme = colorScheme, content = content)
}
