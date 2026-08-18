package com.retrovault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * RetroVault's palette.
 *
 * Fixed rather than dynamic. Material You would recolour the app from the
 * user's wallpaper, and the colours here carry meaning - a file that needs
 * attention is warned about in a hue that has to stay recognisable from one
 * device to the next. Colour never carries a state on its own (UX_SPEC.md
 * section 5 and section 14); every status is written in words beside it, and
 * these are what make the words easier to scan rather than what makes them
 * readable.
 *
 * The purple family is the one the app already had, kept deliberately.
 */
private val Purple = Color(0xFF6750A4)
private val PurpleLight = Color(0xFFD0BCFF)
private val PurpleContainer = Color(0xFFEADDFF)
private val PurpleContainerDark = Color(0xFF4F378B)
private val Slate = Color(0xFF1C1B1F)
private val SlateSurface = Color(0xFF141218)
private val PaleSurface = Color(0xFFFEF7FF)
private val PaleSurfaceVariant = Color(0xFFE7E0EC)

private val LightScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleContainer,
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    background = PaleSurface,
    onBackground = Slate,
    surface = PaleSurface,
    onSurface = Slate,
    surfaceVariant = PaleSurfaceVariant,
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF79747E),
)

private val DarkScheme = darkColorScheme(
    primary = PurpleLight,
    onPrimary = Color(0xFF381E72),
    primaryContainer = PurpleContainerDark,
    onPrimaryContainer = PurpleContainer,
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    background = SlateSurface,
    onBackground = Color(0xFFE6E0E9),
    surface = SlateSurface,
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF938F99),
)

@Composable
fun RetroVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}

/**
 * A colour to tint a status with.
 *
 * Returned alongside the label, never instead of it. Attention states borrow
 * the error colour because that is what the theme guarantees will contrast;
 * settled states stay neutral so the ones that need action are the ones that
 * stand out.
 */
object StatusTint {
    val settled: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val attention: Color @Composable get() = MaterialTheme.colorScheme.error
    val confident: Color @Composable get() = MaterialTheme.colorScheme.primary
}
