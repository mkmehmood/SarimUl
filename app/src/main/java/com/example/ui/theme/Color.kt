package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand & Semantic Palette (from Sarim App UI Spec Section 3.1)
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF0A0A0A)
val DarkSurfaceElevated = Color(0xFF141414)
val DarkTextPrimary = Color(0xFFFFFFFF)
val DarkTextMuted = Color(0xFF9CA3AF)
val DarkBorder = Color(0x24FFFFFF) // white at 14%
val DarkGreen = Color(0xFF4ADE80)
val DarkEmerald = Color(0xFF34D399)
val DarkAmber = Color(0xFFFBBF24)
val DarkRed = Color(0xFFF87171)
val DarkPurple = Color(0xFFC084FC)
val DarkCyan = Color(0xFF67E8F9)

val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF5F5F5)
val LightSurfaceElevated = Color(0xFFECEFF1)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextMuted = Color(0xFF475569)
val LightBorder = Color(0xFFCBD5E1)
val LightGreen = Color(0xFF10B981)
val LightEmerald = Color(0xFF059669)
val LightAmber = Color(0xFFD97706)
val LightRed = Color(0xFFDC2626)
val LightPurple = Color(0xFF7C3AED)
val LightCyan = Color(0xFF0891B2)

// Store fixed colours
val StoreZubairDark = DarkGreen
val StoreMahmoodDark = DarkPurple
val StoreAsaanDark = DarkCyan
val StoreZubairLight = LightGreen
val StoreMahmoodLight = LightPurple
val StoreAsaanLight = LightCyan

@Immutable
data class SarimPalette(
  val isDark: Boolean,
  val background: Color,
  val surface: Color,
  val surfaceElevated: Color,
  val textPrimary: Color,
  val textMuted: Color,
  val border: Color,
  val green: Color,
  val emerald: Color,
  val amber: Color,
  val red: Color,
  val purple: Color,
  val cyan: Color,
  val gradientStart: Color,
  val gradientEnd: Color,
  val cardGlow: Color,
)

val DarkSarimPalette = SarimPalette(
  isDark = true,
  background = DarkBackground,
  surface = DarkSurface,
  surfaceElevated = DarkSurfaceElevated,
  textPrimary = DarkTextPrimary,
  textMuted = DarkTextMuted,
  border = DarkBorder,
  green = DarkGreen,
  emerald = DarkEmerald,
  amber = DarkAmber,
  red = DarkRed,
  purple = DarkPurple,
  cyan = DarkCyan,
  gradientStart = Color(0xFF67E8F9),
  gradientEnd = Color(0xFF4ADE80),
  cardGlow = Color(0x144ADE80),
)

val LightSarimPalette = SarimPalette(
  isDark = false,
  background = LightBackground,
  surface = LightSurface,
  surfaceElevated = LightSurfaceElevated,
  textPrimary = LightTextPrimary,
  textMuted = LightTextMuted,
  border = LightBorder,
  green = LightGreen,
  emerald = LightEmerald,
  amber = LightAmber,
  red = LightRed,
  purple = LightPurple,
  cyan = LightCyan,
  gradientStart = Color(0xFF06B6D4),
  gradientEnd = Color(0xFF10B981),
  cardGlow = Color(0x1410B981),
)

val LocalSarimPalette = staticCompositionLocalOf { DarkSarimPalette }

fun storeColorForKey(storeKey: String, isDark: Boolean): Color {
  return when (storeKey.lowercase()) {
    "zubair" -> if (isDark) StoreZubairDark else StoreZubairLight
    "mahmood" -> if (isDark) StoreMahmoodDark else StoreMahmoodLight
    "asaan" -> if (isDark) StoreAsaanDark else StoreAsaanLight
    else -> {
      val palette = if (isDark) listOf(DarkCyan, DarkPurple, DarkAmber, DarkGreen) else listOf(LightCyan, LightPurple, LightAmber, LightGreen)
      palette[(storeKey.hashCode() and 0x7fffffff) % palette.size]
    }
  }
}

fun avatarColorForName(name: String): Color {
  val colors = listOf(
    Color(0xFF10B981),
    Color(0xFF06B6D4),
    Color(0xFF8B5CF6),
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFF3B82F6),
    Color(0xFF14B8A6),
    Color(0xFFEF4444),
  )
  val hash = name.trim().lowercase().hashCode()
  return colors[(hash and 0x7fffffff) % colors.size]
}
