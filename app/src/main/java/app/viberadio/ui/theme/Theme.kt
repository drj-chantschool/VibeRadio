package app.viberadio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Muted, monochrome theme modelled on an e-ink tablet.
 *
 * Deliberately no dynamic color and no album-art-derived color: every surface role maps to
 * the same paper tone so Material never tints containers, and "primary" is just ink.
 */
@Composable
fun VibeRadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkInk else LightInk,
        typography = VibeTypography,
        content = content,
    )
}

private fun paperScheme(
    base: ColorScheme,
    paper: Color,
    shade: Color,
    ink: Color,
    inkSoft: Color,
    inkFaint: Color,
    rule: Color,
    ruleFaint: Color,
    error: Color,
): ColorScheme = base.copy(
    primary = ink,
    onPrimary = paper,
    primaryContainer = shade,
    onPrimaryContainer = ink,
    secondary = inkSoft,
    onSecondary = paper,
    secondaryContainer = shade,
    onSecondaryContainer = ink,
    tertiary = inkSoft,
    onTertiary = paper,
    tertiaryContainer = shade,
    onTertiaryContainer = ink,
    background = paper,
    onBackground = ink,
    surface = paper,
    onSurface = ink,
    surfaceVariant = shade,
    onSurfaceVariant = inkFaint,
    surfaceTint = paper,
    surfaceBright = paper,
    surfaceDim = shade,
    surfaceContainerLowest = paper,
    surfaceContainerLow = paper,
    surfaceContainer = paper,
    surfaceContainerHigh = shade,
    surfaceContainerHighest = shade,
    inverseSurface = ink,
    inverseOnSurface = paper,
    inversePrimary = paper,
    outline = rule,
    outlineVariant = ruleFaint,
    error = error,
    onError = paper,
    errorContainer = shade,
    onErrorContainer = error,
    scrim = Color.Black,
)

private val LightInk = paperScheme(
    base = lightColorScheme(),
    paper = Paper, shade = PaperShade, ink = Ink, inkSoft = InkSoft, inkFaint = InkFaint,
    rule = Rule, ruleFaint = RuleFaint, error = ErrorInk,
)

private val DarkInk = paperScheme(
    base = darkColorScheme(),
    paper = NightPaper, shade = NightPaperShade, ink = NightInk, inkSoft = NightInkSoft,
    inkFaint = NightInkFaint, rule = NightRule, ruleFaint = NightRuleFaint, error = NightErrorInk,
)
