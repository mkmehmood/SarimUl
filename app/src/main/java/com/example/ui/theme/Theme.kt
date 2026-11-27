package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.example.data.model.AppThemeMode

private val SarimDarkColorScheme = darkColorScheme(
  primary = DarkGreen,
  onPrimary = Color(0xFF03210E),
  primaryContainer = Color(0xFF063D1F),
  onPrimaryContainer = DarkGreen,
  secondary = DarkCyan,
  onSecondary = Color(0xFF042229),
  tertiary = DarkPurple,
  background = DarkBackground,
  onBackground = DarkTextPrimary,
  surface = DarkSurface,
  onSurface = DarkTextPrimary,
  surfaceVariant = DarkSurfaceElevated,
  onSurfaceVariant = DarkTextMuted,
  outline = DarkBorder,
  error = DarkRed,
  onError = Color(0xFF330606),
)

private val SarimLightColorScheme = lightColorScheme(
  primary = LightGreen,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFD1FAE5),
  onPrimaryContainer = Color(0xFF065F46),
  secondary = LightCyan,
  onSecondary = Color.White,
  tertiary = LightPurple,
  background = LightBackground,
  onBackground = LightTextPrimary,
  surface = LightSurface,
  onSurface = LightTextPrimary,
  surfaceVariant = LightSurfaceElevated,
  onSurfaceVariant = LightTextMuted,
  outline = LightBorder,
  error = LightRed,
  onError = Color.White,
)

@Composable
fun SarimTheme(
  themeMode: AppThemeMode = AppThemeMode.DARK,
  content: @Composable () -> Unit,
) {
  val useDark = when (themeMode) {
    AppThemeMode.DARK -> true
    AppThemeMode.LIGHT -> false
    AppThemeMode.AUTO -> isSystemInDarkTheme()
  }
  val palette = if (useDark) DarkSarimPalette else LightSarimPalette
  val colorScheme = if (useDark) SarimDarkColorScheme else SarimLightColorScheme

  CompositionLocalProvider(LocalSarimPalette provides palette) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = SarimTypography,
      content = content,
    )
  }
}
