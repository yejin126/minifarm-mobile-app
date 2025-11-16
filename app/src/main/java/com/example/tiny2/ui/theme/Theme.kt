package com.example.tiny2.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.accompanist.systemuicontroller.rememberSystemUiController

private val LightColors = lightColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
)

@Composable
fun TInyTheme(content: @Composable () -> Unit) {
    val colorScheme = LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        val systemUiController = rememberSystemUiController()
        val primaryColor = colorScheme.primary

        SideEffect {
            systemUiController.setSystemBarsColor(
                color = primaryColor,
                darkIcons = true
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}