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
    secondary = Pleasure,
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF0F0EE),
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
    secondary = Pleasure,
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF5F5F3),
    surfaceVariant = Color(0xFF1E1E1E),
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
