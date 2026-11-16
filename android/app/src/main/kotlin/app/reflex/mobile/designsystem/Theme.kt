package app.reflex.mobile.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

private val LocalHealthPalette = staticCompositionLocalOf { HealthLightColors }

object HealthTheme {
    val colors: HealthPalette
        @Composable get() = LocalHealthPalette.current
}

@Composable
fun HealthTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) HealthDarkColors else HealthLightColors
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = palette.primary, onPrimary = palette.background,
        primaryContainer = palette.secondaryBackground, onPrimaryContainer = palette.primary,
        inversePrimary = palette.background,
        secondary = palette.secondary, onSecondary = palette.background,
        secondaryContainer = palette.tertiaryBackground, onSecondaryContainer = palette.primary,
        tertiary = palette.primary, onTertiary = palette.background,
        tertiaryContainer = palette.secondaryBackground, onTertiaryContainer = palette.primary,
        background = palette.background, onBackground = palette.primary,
        surface = palette.background, onSurface = palette.primary,
        surfaceVariant = palette.secondaryBackground, onSurfaceVariant = palette.secondary,
        surfaceTint = palette.primary, inverseSurface = palette.primary, inverseOnSurface = palette.background,
        surfaceBright = palette.tertiaryBackground, surfaceDim = palette.background,
        surfaceContainerLowest = palette.background, surfaceContainerLow = palette.secondaryBackground,
        surfaceContainer = palette.secondaryBackground, surfaceContainerHigh = palette.tertiaryBackground,
        surfaceContainerHighest = palette.tertiaryBackground,
        outline = palette.strongBorder, outlineVariant = palette.border,
        error = palette.destructive, onError = palette.background,
        errorContainer = palette.destructiveBackground, onErrorContainer = palette.destructive,
    )
    CompositionLocalProvider(LocalHealthPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = HealthTypography,
            shapes = Shapes(small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
            content = content)
    }
}
