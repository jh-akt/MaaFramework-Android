package com.maaframework.android.sample.bbb

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF904B2B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF8D7C5),
    onPrimaryContainer = Color(0xFF3B1B0D),
    secondary = Color(0xFF35646D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0EDF1),
    onSecondaryContainer = Color(0xFF10272C),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF1D1A16),
    surfaceVariant = Color(0xFFF1E6DD),
    onSurfaceVariant = Color(0xFF51463F),
    outline = Color(0xFF8A7C72),
    background = Color(0xFFF6F1EA),
    onBackground = Color(0xFF1D1A16),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1B693),
    onPrimary = Color(0xFF55240D),
    primaryContainer = Color(0xFF71361A),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFF9FD1DA),
    onSecondary = Color(0xFF00363E),
    secondaryContainer = Color(0xFF1D4D55),
    onSecondaryContainer = Color(0xFFD0EDF1),
    surface = Color(0xFF181311),
    onSurface = Color(0xFFEDE0D8),
    surfaceVariant = Color(0xFF51463F),
    onSurfaceVariant = Color(0xFFD6C2B7),
    outline = Color(0xFF9E8D82),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFEDE0D8),
    error = Color(0xFFF2B8B5),
)

@Composable
fun MaaBbbSampleTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
