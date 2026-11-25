package com.corosdjistudio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColors = darkColorScheme(
    primary = Color(0xFF75D69A),
    onPrimary = Color(0xFF062414),
    primaryContainer = Color(0xFF205B39),
    onPrimaryContainer = Color(0xFFE5F8EB),
    secondary = Color(0xFF9BC9A9),
    onSecondary = Color(0xFF10291A),
    secondaryContainer = Color(0xFF244B32),
    onSecondaryContainer = Color(0xFFE4F4E8),
    tertiary = Color(0xFFB5D9C0),
    background = Color(0xFF0B1F14),
    onBackground = Color.White,
    surface = Color(0xFF122B1D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1D3A29),
    onSurfaceVariant = Color(0xFFD0E3D5),
    outline = Color(0xFF62856D),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5C2424),
    onErrorContainer = Color(0xFFFFEDEA)
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColors,
        typography = Typography(),
        content = content
    )
}
