package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.data.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldBright,
    onPrimary = EmeraldDeep,
    primaryContainer = EmeraldDark,
    onPrimaryContainer = MintLight,
    secondary = EmeraldVibrant,
    onSecondary = EmeraldDeep,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = MintOnlineGlow,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceMuted,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = Color(0xFFFF6B60),
    errorContainer = ErrorContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldDark,
    onPrimary = Color.White,
    primaryContainer = MintLight,
    onPrimaryContainer = EmeraldDeep,
    secondary = EmeraldMid,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = EmeraldDark,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceMuted,
    outline = LightOutline,
    error = ErrorRed,
    errorContainer = ErrorContainerLight
)

@Composable
fun NomiChatTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NomiTypography,
        content = content
    )
}
