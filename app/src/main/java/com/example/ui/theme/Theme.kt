package com.example.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

private val FallbackLightColorScheme = lightColorScheme(
    primary = PixelBlue40,
    onPrimary = PixelBlueOn40,
    primaryContainer = PixelBlueContainer90,
    onPrimaryContainer = PixelBlueOnContainer10,
    secondary = PixelSlate40,
    onSecondary = PixelSlateOn40,
    secondaryContainer = PixelSlateContainer90,
    onSecondaryContainer = PixelSlateOnContainer10,
    tertiary = PixelTeal40,
    onTertiary = PixelTealOn40,
    tertiaryContainer = PixelTealContainer90,
    onTertiaryContainer = PixelTealOnContainer10,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

private val FallbackDarkColorScheme = darkColorScheme(
    primary = PixelBlue80,
    onPrimary = PixelBlueOn20,
    primaryContainer = PixelBlueContainer30,
    onPrimaryContainer = PixelBlueOnContainer90,
    secondary = PixelSlate80,
    onSecondary = PixelSlateOn20,
    secondaryContainer = PixelSlateContainer30,
    onSecondaryContainer = PixelSlateOnContainer90,
    tertiary = PixelTeal80,
    onTertiary = PixelTealOn20,
    tertiaryContainer = PixelTealContainer30,
    onTertiaryContainer = PixelTealOnContainer90,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

/**
 * Single source of truth for Download and Upload theme roles across the entire application.
 * Compatible with Dynamic Color on and off, and with light and dark themes.
 */
val DownloadColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val UploadColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.tertiary

val ColorScheme.downloadColor: Color
    get() = primary

val ColorScheme.uploadColor: Color
    get() = tertiary

/**
 * Resolves the DownloadColor and UploadColor ARGB ints for the dark-surface share card bitmap,
 * respecting Android 12+ Dynamic Color when available and falling back to the dark theme palette.
 */
fun resolveShareCardColors(
    context: Context? = null,
    dynamicColor: Boolean = true,
): Pair<Int, Int> {
    val scheme = if (dynamicColor && context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { dynamicDarkColorScheme(context) }.getOrDefault(FallbackDarkColorScheme)
    } else {
        FallbackDarkColorScheme
    }
    return scheme.downloadColor.toArgb() to scheme.uploadColor.toArgb()
}

@Composable
fun SpeedtestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SpeedTestTypography,
        shapes = SpeedTestShapes,
        content = content,
    )
}
