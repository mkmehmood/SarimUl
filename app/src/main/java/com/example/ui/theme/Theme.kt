package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.AppThemeMode

/**
 * Material 3 Expressive color roles, seeded from the Sarim green. Every tonal role is defined so
 * stock M3 components (nav bar, dialogs, menus, pickers, switches) look correct without overrides.
 */
private val SarimDarkColorScheme = darkColorScheme(
  primary = DarkGreen,
  onPrimary = Color(0xFF003918),
  primaryContainer = Color(0xFF00522A),
  onPrimaryContainer = Color(0xFFA8F5B5),
  inversePrimary = Color(0xFF006D3D),
  secondary = Color(0xFFB4CCB9),
  onSecondary = Color(0xFF203526),
  secondaryContainer = Color(0xFF364B3C),
  onSecondaryContainer = Color(0xFFD0E8D5),
  tertiary = DarkCyan,
  onTertiary = Color(0xFF00363D),
  tertiaryContainer = Color(0xFF004F58),
  onTertiaryContainer = Color(0xFFA6EEFF),
  error = DarkRed,
  onError = Color(0xFF690005),
  errorContainer = Color(0xFF93000A),
  onErrorContainer = Color(0xFFFFDAD6),
  background = DarkBackground,
  onBackground = DarkTextPrimary,
  surface = DarkBackground,
  onSurface = DarkTextPrimary,
  surfaceVariant = Color(0xFF3F4943),
  onSurfaceVariant = DarkTextMuted,
  surfaceTint = DarkGreen,
  inverseSurface = DarkTextPrimary,
  inverseOnSurface = Color(0xFF2D322E),
  outline = Color(0xFF89938B),
  outlineVariant = DarkBorder,
  scrim = Color(0xFF000000),
  surfaceBright = Color(0xFF343B36),
  surfaceDim = DarkBackground,
  surfaceContainerLowest = Color(0xFF090F0B),
  surfaceContainerLow = Color(0xFF121A15),
  surfaceContainer = DarkSurface,
  surfaceContainerHigh = DarkSurfaceElevated,
  surfaceContainerHighest = Color(0xFF2A342E),
)

private val SarimLightColorScheme = lightColorScheme(
  primary = Color(0xFF006D3D),
  onPrimary = Color.White,
  primaryContainer = Color(0xFFA8F5B5),
  onPrimaryContainer = Color(0xFF00210E),
  inversePrimary = DarkGreen,
  secondary = Color(0xFF4F6354),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFD1E8D6),
  onSecondaryContainer = Color(0xFF0C1F13),
  tertiary = Color(0xFF006874),
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFA6EEFF),
  onTertiaryContainer = Color(0xFF001F24),
  error = LightRed,
  onError = Color.White,
  errorContainer = Color(0xFFFFDAD6),
  onErrorContainer = Color(0xFF410002),
  background = LightBackground,
  onBackground = LightTextPrimary,
  surface = LightBackground,
  onSurface = LightTextPrimary,
  surfaceVariant = Color(0xFFDCE5DB),
  onSurfaceVariant = LightTextMuted,
  surfaceTint = Color(0xFF006D3D),
  inverseSurface = Color(0xFF2E312E),
  inverseOnSurface = Color(0xFFF0F1EC),
  outline = Color(0xFF707972),
  outlineVariant = LightBorder,
  scrim = Color(0xFF000000),
  surfaceBright = LightBackground,
  surfaceDim = Color(0xFFD9DED8),
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color(0xFFF4F9F2),
  surfaceContainer = LightSurface,
  surfaceContainerHigh = LightSurfaceElevated,
  surfaceContainerHighest = Color(0xFFDDE4DC),
)

/** Expressive shape scale: rounder, more playful corners across every component. */
val SarimShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(24.dp),
  largeIncreased = RoundedCornerShape(28.dp),
  extraLarge = RoundedCornerShape(32.dp),
  extraLargeIncreased = RoundedCornerShape(40.dp),
  extraExtraLarge = RoundedCornerShape(48.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    MaterialExpressiveTheme(
      colorScheme = colorScheme,
      motionScheme = MotionScheme.expressive(),
      shapes = SarimShapes,
      typography = SarimTypography,
      content = content,
    )
  }
}
