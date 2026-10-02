package com.chinut.bawantv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

private val BawanColors = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Ink.Deep,
    primaryContainer = Ink.AccentSoft,
    onPrimaryContainer = Ink.AccentBright,
    secondary = Ink.AccentBright,
    onSecondary = Ink.Deep,
    tertiary = Ink.Pink,
    background = Ink.Base,
    onBackground = Ink.TextPrimary,
    surface = Ink.Soft,
    onSurface = Ink.TextPrimary,
    surfaceVariant = Ink.Card,
    onSurfaceVariant = Ink.TextSecondary,
    error = Ink.Red,
)

/** 全局主题：TV 端固定深色（客厅环境光暗，浅色刺眼）。 */
@Composable
fun BawanTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = BawanColors, content = content)
}
