package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.netscope.core.data.prefs.ThemeMode

private val NetScopeDarkColorScheme =
  darkColorScheme(
    primary = CyberPrimary,
    onPrimary = CyberOnPrimary,
    secondary = CyberSecondary,
    tertiary = CyberTertiary,
    error = CyberError,
    background = CyberBackground,
    surface = CyberSurface,
    surfaceVariant = CyberSurfaceVariant,
    onBackground = CyberOnSurface,
    onSurface = CyberOnSurface,
    onSurfaceVariant = CyberOnSurfaceVariant,
    outline = CyberOutline,
  )

private val NetScopeLightColorScheme =
  lightColorScheme(
    primary = LabPrimary,
    onPrimary = LabOnPrimary,
    secondary = LabSecondary,
    tertiary = LabTertiary,
    error = LabError,
    background = LabBackground,
    surface = LabSurface,
    surfaceVariant = LabSurfaceVariant,
    onBackground = LabOnSurface,
    onSurface = LabOnSurface,
    onSurfaceVariant = LabOnSurfaceVariant,
    outline = LabOutline,
  )

@Composable
fun NetScopeTheme(
  themeMode: ThemeMode = ThemeMode.DARK,
  content: @Composable () -> Unit,
) {
  val useDark =
    when (themeMode) {
      ThemeMode.DARK -> true
      ThemeMode.LIGHT -> false
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
  val colorScheme = if (useDark) NetScopeDarkColorScheme else NetScopeLightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = NetScopeTypography,
    content = content,
  )
}
