package com.lxpure.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lxpure.music.domain.model.ThemeMode

@Immutable
data class LxPurePalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val glassSurface: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color
)

private val DarkPalette = LxPurePalette(
    isDark = true,
    background = Color(0xFF0A0B0F),
    surface = Color(0xFF13151D),
    surfaceElevated = Color(0xFF1B1E29),
    glassSurface = Color(0xCC171A24),
    hairline = Color(0x18FFFFFF),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textTertiary = Color(0xFF64748B),
    accent = Color(0xFF38BDF8),
    accentSoft = Color(0x2638BDF8),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFFB7185)
)

private val LightPalette = LxPurePalette(
    isDark = false,
    background = Color(0xFFF5F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    glassSurface = Color(0xE6FFFFFF),
    hairline = Color(0x140F172A),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textTertiary = Color(0xFF94A3B8),
    accent = Color(0xFF0284C7),
    accentSoft = Color(0x1A0284C7),
    success = Color(0xFF10B981),
    warning = Color(0xFFD97706),
    danger = Color(0xFFE11D48)
)

val LocalLxColors = staticCompositionLocalOf { DarkPalette }

private val LxTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.3.sp
    )
)

@Composable
fun LxPureTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val lxColors = if (isDark) DarkPalette else LightPalette
    val m3Scheme = if (isDark) {
        darkColorScheme(
            primary = lxColors.accent,
            onPrimary = Color(0xFF04141F),
            background = lxColors.background,
            onBackground = lxColors.textPrimary,
            surface = lxColors.surface,
            onSurface = lxColors.textPrimary,
            surfaceVariant = lxColors.surfaceElevated,
            onSurfaceVariant = lxColors.textSecondary,
            outline = lxColors.hairline,
            error = lxColors.danger
        )
    } else {
        lightColorScheme(
            primary = lxColors.accent,
            onPrimary = Color.White,
            background = lxColors.background,
            onBackground = lxColors.textPrimary,
            surface = lxColors.surface,
            onSurface = lxColors.textPrimary,
            surfaceVariant = Color(0xFFEEF2F6),
            onSurfaceVariant = lxColors.textSecondary,
            outline = lxColors.hairline,
            error = lxColors.danger
        )
    }

    CompositionLocalProvider(LocalLxColors provides lxColors) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = LxTypography,
            content = content
        )
    }
}
