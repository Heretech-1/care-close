package com.careclose.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.careclose.core.model.AppLanguage
import com.careclose.core.model.AppThemeMode

val LocalIsHighContrast = staticCompositionLocalOf { false }
val LocalAppThemeMode = staticCompositionLocalOf { AppThemeMode.SYSTEM }
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.SYSTEM }

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0A2E0D),
    secondary = Color(0xFF0D47A1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBBDEFB),
    onSecondaryContainer = Color(0xFF06214D),
    error = Color(0xFFB71C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFFCDD2),
    onErrorContainer = Color(0xFF490003),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF111418),
    surface = Color.White,
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF00390E),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFA5D6A7),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF0D2D5E),
    secondaryContainer = Color(0xFF1565C0),
    onSecondaryContainer = Color(0xFFD1E4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF282B30),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF8E918F),
)

private val HighContrastColorScheme = darkColorScheme(
    primary = Color(0xFFFFEA00), // Pure Bright Yellow
    onPrimary = Color(0xFF000000), // Pure Black text on bright yellow
    primaryContainer = Color(0xFF00E676), // Bright neon lime
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF00E5FF), // Vivid Electric Cyan
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF002838),
    onSecondaryContainer = Color(0xFF80D8FF),
    error = Color(0xFFFF1744), // Vivid Coral Red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF6B0000),
    onErrorContainer = Color(0xFFFFD5D5),
    background = Color(0xFF000000), // Pure Black
    onBackground = Color(0xFFFFFFFF), // Pure White
    surface = Color(0xFF000000), // Black card background
    onSurface = Color(0xFFFFFFFF), // Pure White
    surfaceVariant = Color(0xFF141414), // Dark charcoal for contrast
    onSurfaceVariant = Color(0xFFFFFFFF),
    outline = Color(0xFFFFFFFF), // Crisp 100% white borders
    outlineVariant = Color(0xFFFFEA00), // Bright yellow accent borders
)

private val CareCloseTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
)

@Composable
fun CareCloseTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isHighContrast = themeMode == AppThemeMode.HIGH_CONTRAST
    val colors = when (themeMode) {
        AppThemeMode.SYSTEM -> if (isSystemDark) DarkColorScheme else LightColorScheme
        AppThemeMode.LIGHT -> LightColorScheme
        AppThemeMode.DARK -> DarkColorScheme
        AppThemeMode.HIGH_CONTRAST -> HighContrastColorScheme
    }

    CompositionLocalProvider(
        LocalIsHighContrast provides isHighContrast,
        LocalAppThemeMode provides themeMode,
        LocalAppLanguage provides appLanguage,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = CareCloseTypography,
            content = content,
        )
    }
}

