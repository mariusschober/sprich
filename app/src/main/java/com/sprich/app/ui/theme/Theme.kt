package com.sprich.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Pleasure = Color(0xFFFF4D76)

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Pleasure.copy(alpha = 0.12f),
    onPrimaryContainer = Color(0xFF45202C),
    secondary = Pleasure,
    secondaryContainer = Color(0xFFF8DDE4),
    onSecondaryContainer = Color(0xFF45202C),
    background = Color(0xFFFFFBF6),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFBF6),
    surfaceTint = Pleasure,
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF625A5B),
    surfaceVariant = Color(0xFFF4ECE8),
    surfaceContainer = Color(0xFFFFF4EF),
    surfaceContainerLow = Color(0xFFFFF8F2),
    surfaceContainerLowest = Color(0xFFFFFDFA),
    surfaceContainerHigh = Color(0xFFF6ECE6),
    surfaceContainerHighest = Color(0xFFEFE5E1),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFE8E8E8),
    error = Color(0xFFC62828),
    tertiary = Pleasure,
    onTertiary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F3),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFF6EAEE),
    secondary = Pleasure,
    secondaryContainer = Color(0xFF50313E),
    onSecondaryContainer = Color(0xFFFFDBE5),
    background = Color(0xFF151315),
    onBackground = Color(0xFFF5F5F3),
    surface = Color(0xFF151315),
    surfaceTint = Pleasure,
    onSurface = Color(0xFFF5F5F3),
    onSurfaceVariant = Color(0xFFC3B9BC),
    surfaceVariant = Color(0xFF292326),
    surfaceContainer = Color(0xFF282125),
    surfaceContainerLow = Color(0xFF201B1E),
    surfaceContainerLowest = Color(0xFF100E10),
    surfaceContainerHigh = Color(0xFF30282D),
    surfaceContainerHighest = Color(0xFF3B3036),
    outline = Color(0xFF999999),
    outlineVariant = Color(0xFF2A2A2A),
    tertiary = Pleasure,
    onTertiary = Color.White,
)

@Composable
fun SprichTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
