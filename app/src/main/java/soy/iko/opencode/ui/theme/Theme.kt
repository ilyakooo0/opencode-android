package soy.iko.opencode.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Tokyo Night — a dark, muted blue/purple palette. Every Material 3 color role is set
 * explicitly (not just primary/secondary/tertiary) so components that read `error`,
 * `primaryContainer`, `surfaceVariant`, `outline`, etc. match the brand instead of
 * falling back to stock M3 defaults that clash with it.
 *
 * Source palette (Tokyo Night):
 *   bg      #1A1B26   fg      #C0CAF5   blue    #7AA2F7   cyan    #7DCFFF
 *   green   #9ECE6A   magenta #BB9AF7   red     #F7768E   yellow  #E0AF68
 *   comment #565F89   gutter  #414868
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF112036),
    primaryContainer = Color(0xFF2A3A5C),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFF9ECE6A),
    onSecondary = Color(0xFF13260A),
    secondaryContainer = Color(0xFF2B3B1F),
    onSecondaryContainer = Color(0xFFD2F0A0),
    tertiary = Color(0xFFBB9AF7),
    onTertiary = Color(0xFF221036),
    tertiaryContainer = Color(0xFF3A2A55),
    onTertiaryContainer = Color(0xFFE6D6FF),
    error = Color(0xFFF7768E),
    onError = Color(0xFF370610),
    errorContainer = Color(0xFF5C1A24),
    onErrorContainer = Color(0xFFFFD9DE),
    background = Color(0xFF1A1B26),
    onBackground = Color(0xFFC0CAF5),
    surface = Color(0xFF1F2335),
    onSurface = Color(0xFFC0CAF5),
    surfaceVariant = Color(0xFF2D3247),
    onSurfaceVariant = Color(0xFF9AA5CE),
    surfaceTint = Color(0xFF7AA2F7),
    inverseSurface = Color(0xFFC0CAF5),
    inverseOnSurface = Color(0xFF1A1B26),
    outline = Color(0xFF565F89),
    outlineVariant = Color(0xFF414868),
    scrim = Color(0xFF000000),
)

/**
 * Tokyo Night Light — the day-mode counterpart. Pairs the same hue family with a soft
 * off-white background so the brand identity survives a light setting instead of
 * snapping to stock M3 purple-grey.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF34548A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF485E30),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F0A0),
    onSecondaryContainer = Color(0xFF0E2000),
    tertiary = Color(0xFF5A4A78),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6D6FF),
    onTertiaryContainer = Color(0xFF180038),
    error = Color(0xFF9B1C2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF410008),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1A1B26),
    surface = Color(0xFFF1F3FA),
    onSurface = Color(0xFF1A1B26),
    surfaceVariant = Color(0xFFE0E4F0),
    onSurfaceVariant = Color(0xFF444B6E),
    surfaceTint = Color(0xFF34548A),
    inverseSurface = Color(0xFF2D3247),
    inverseOnSurface = Color(0xFFEEF0FB),
    outline = Color(0xFF747DAA),
    outlineVariant = Color(0xFFC3C8DA),
    scrim = Color(0xFF000000),
)

/**
 * AMOLED — the Tokyo Night dark palette with pure-black backgrounds (#000000) so OLED
 * displays power off pixels in large black areas, extending battery life. The accent
 * colours stay the same so the brand identity is preserved; only the backgrounds and
 * surfaces drop to true black.
 */
private val AmoledColors = DarkColors.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF1A1F2E),
    inverseOnSurface = Color(0xFF000000),
)

@Composable
fun OpencodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // AMOLED takes precedence over dynamic color: a user who explicitly chose pure-black
        // surfaces wants OLED-black even with Material You on, rather than silently getting the
        // wallpaper-derived palette (which left the AMOLED choice appearing to do nothing).
        darkTheme && amoled -> AmoledColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            // Keep the navigation-bar icon/affordance contrast in sync with the status bar so
            // 3-button-nav and gesture-nav devices don't end up with invisible controls on one
            // theme. The system only guesses when this isn't set explicitly.
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = OpencodeTypography,
        shapes = OpencodeShapes,
        content = content,
    )
}

/**
 * Preview swatches for the Tokyo Night palettes, exposed so the Settings theme picker
 * can show color dots without reaching into the (otherwise private) full color schemes.
 * Each list is [primary, secondary, tertiary, background].
 */
val DarkPaletteSwatches: List<Color> = listOf(
    DarkColors.primary,
    DarkColors.secondary,
    DarkColors.tertiary,
    DarkColors.background,
)
val LightPaletteSwatches: List<Color> = listOf(
    LightColors.primary,
    LightColors.secondary,
    LightColors.tertiary,
    LightColors.background,
)
// AMOLED shares the Dark accent colors but uses a pure-black background, so its preview
// must differ from Dark's (#1A1B26) dot — otherwise the two theme rows are visually
// indistinguishable in the picker and the user can't tell them apart before selecting.
val AmoledPaletteSwatches: List<Color> = listOf(
    AmoledColors.primary,
    AmoledColors.secondary,
    AmoledColors.tertiary,
    AmoledColors.background,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
