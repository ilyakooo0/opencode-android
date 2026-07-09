package soy.iko.opencode.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = Emerald,
    onPrimary = Color(0xFF0F1115),
    primaryContainer = Color(0xFF1A3D2A),
    onPrimaryContainer = Color(0xFFA8E6C1),
    secondary = EmeraldDark,
    tertiaryContainer = Color(0xFF2A2A3D),
    onTertiaryContainer = Color(0xFFC5C5E6),
    errorContainer = Color(0xFF4D2626),
    onErrorContainer = Color(0xFFFFB4B4),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurface = DarkOnSurface,
    outline = DarkOutline,
)

private val LightColors = lightColorScheme(
    primary = EmeraldDark,
    primaryContainer = Color(0xFFD7F5E3),
    onPrimaryContainer = Color(0xFF0A3D22),
    secondary = Emerald,
    tertiaryContainer = Color(0xFFE8E8F5),
    onTertiaryContainer = Color(0xFF2A2A4D),
    errorContainer = Color(0xFFFFE0E0),
    onErrorContainer = Color(0xFF660000),
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurface = LightOnSurface,
    outline = LightOutline,
)

@Composable
fun OpencodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
