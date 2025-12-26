package dev.yorkie.example.scheduler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

private val DarkColorPalette = darkColors(
    primary = Purple80,
    secondary = PurpleGrey80,
)

private val LightColorPalette = lightColors(
    primary = Purple40,
    secondary = PurpleGrey40,
)

@Composable
fun SchedulerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        darkTheme -> DarkColorPalette
        else -> LightColorPalette
    }

    MaterialTheme(
        colors = colors,
        typography = Typography,
        content = content,
    )
}
