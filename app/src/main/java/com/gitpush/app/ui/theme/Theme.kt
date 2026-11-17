package com.gitpush.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gitpush.app.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B4CCA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF0E1A66),
    secondary = Color(0xFF5A5F7A),
    secondaryContainer = Color(0xFFE1E3F4),
    onSecondaryContainer = Color(0xFF171A2E),
    tertiary = Color(0xFF8A5A00),
    tertiaryContainer = Color(0xFFFFDDA8),
    onTertiaryContainer = Color(0xFF2B1A00),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF181A22),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF181A22),
    surfaceVariant = Color(0xFFE3E5F0),
    onSurfaceVariant = Color(0xFF454859),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F1F8),
    surfaceContainerHigh = Color(0xFFE9EAF3),
    outline = Color(0xFF767A8C),
    outlineVariant = Color(0xFFC6C9D8),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF0B1470),
    primaryContainer = Color(0xFF2433A8),
    onPrimaryContainer = Color(0xFFDEE1FF),
    secondary = Color(0xFFC3C6DC),
    secondaryContainer = Color(0xFF2E3144),
    onSecondaryContainer = Color(0xFFDFE1F6),
    tertiary = Color(0xFFFFC857),
    tertiaryContainer = Color(0xFF5A3C00),
    onTertiaryContainer = Color(0xFFFFDDA8),
    background = Color(0xFF0E1118),
    onBackground = Color(0xFFE3E4EE),
    surface = Color(0xFF0E1118),
    onSurface = Color(0xFFE3E4EE),
    surfaceVariant = Color(0xFF2A2D3A),
    onSurfaceVariant = Color(0xFFC5C7D6),
    surfaceContainerLowest = Color(0xFF0A0C12),
    surfaceContainerLow = Color(0xFF141720),
    surfaceContainer = Color(0xFF181B25),
    surfaceContainerHigh = Color(0xFF1F2330),
    outline = Color(0xFF8F93A6),
    outlineVariant = Color(0xFF3A3E4F),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

/** Paths, hashes and codes read better in a fixed-width face. */
val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

enum class DiffKind { ADDED, MODIFIED, DELETED }

@Composable
fun diffColor(kind: DiffKind): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (kind) {
        DiffKind.ADDED -> if (dark) Color(0xFF6FD39B) else Color(0xFF1F8A4C)
        DiffKind.MODIFIED -> if (dark) Color(0xFFFFC857) else Color(0xFF9A6200)
        DiffKind.DELETED -> if (dark) Color(0xFFFF8A98) else Color(0xFFC6384A)
    }
}

@Composable
fun GitPushTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
