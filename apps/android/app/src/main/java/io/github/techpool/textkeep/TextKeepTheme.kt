package io.github.techpool.textkeep

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class Appearance(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

private val LightPalette = lightColorScheme(
    primary = Color(0xFF176B59), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F4E7), onPrimaryContainer = Color(0xFF093C30),
    secondary = Color(0xFF52665C), secondaryContainer = Color(0xFFE4ECE5), onSecondaryContainer = Color(0xFF263D30),
    tertiary = Color(0xFF72589A), tertiaryContainer = Color(0xFFEEE5FB),
    background = Color(0xFFF8FAF7), onBackground = Color(0xFF18231E),
    surface = Color(0xFFF8FAF7), onSurface = Color(0xFF18231E),
    surfaceContainer = Color(0xFFEEF2EC), surfaceContainerLow = Color(0xFFF1F5EF),
    surfaceContainerHigh = Color(0xFFE8EDE6), surfaceContainerHighest = Color(0xFFE2E8E0),
    surfaceVariant = Color(0xFFE6ECE5), onSurfaceVariant = Color(0xFF536158),
    outline = Color(0xFF78867C), outlineVariant = Color(0xFFD9E1D8),
    inverseSurface = Color(0xFF25382D), inverseOnSurface = Color(0xFFF1F5EF), inversePrimary = Color(0xFF92D9BD)
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF92D9BD), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF164E3E), onPrimaryContainer = Color(0xFFC6F2DF),
    secondary = Color(0xFFB9CDBE), secondaryContainer = Color(0xFF34483B), onSecondaryContainer = Color(0xFFDCEBDD),
    tertiary = Color(0xFFD2BAF4), tertiaryContainer = Color(0xFF493561),
    background = Color(0xFF101813), onBackground = Color(0xFFE4EBE2),
    surface = Color(0xFF101813), onSurface = Color(0xFFE4EBE2),
    surfaceContainer = Color(0xFF1C261F), surfaceContainerLow = Color(0xFF172019),
    surfaceContainerHigh = Color(0xFF263028), surfaceContainerHighest = Color(0xFF303A32),
    surfaceVariant = Color(0xFF37463B), onSurfaceVariant = Color(0xFFBBC9BC),
    outline = Color(0xFF87998B), outlineVariant = Color(0xFF3D4B40),
    inverseSurface = Color(0xFFE4EBE2), inverseOnSurface = Color(0xFF233027), inversePrimary = Color(0xFF176B59)
)

@Composable fun TextKeepTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkPalette else LightPalette,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content
    )
}
