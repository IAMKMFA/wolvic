package com.framatome.vr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = FramatomeBlue,
    onPrimary = Color.White,
    secondary = FramatomeOrange,
    onSecondary = Color.White,
    tertiary = FramatomeSteel,
    onTertiary = Color.Black,
    background = FramatomeBlueDark,
    onBackground = FramatomeMist,
    surface = FramatomeBlueMid,
    onSurface = FramatomeMist,
    surfaceVariant = FramatomeBlueDark.copy(alpha = 0.85f),
    onSurfaceVariant = FramatomeMist.copy(alpha = 0.9f),
    secondaryContainer = Color(0xFF0A2747),
    onSecondaryContainer = FramatomeMist,
    outline = FramatomeSteel.copy(alpha = 0.6f)
)

@Composable
fun FramatomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
