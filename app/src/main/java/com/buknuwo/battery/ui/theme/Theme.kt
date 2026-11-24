package com.buknuwo.battery.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Background = Color(0xFF0B1116)
val Surface = Color(0xFF121C23)
val SurfaceVariant = Color(0xFF1A2831)
val Accent = Color(0xFF2DD4A7)
val AccentDim = Color(0xFF117A63)
val Warning = Color(0xFFFFB74D)
val Danger = Color(0xFFFF5C6C)
val TextPrimary = Color(0xFFE8F1F2)
val TextSecondary = Color(0xFF93A7AE)

private val ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04231C),
    primaryContainer = AccentDim,
    onPrimaryContainer = TextPrimary,
    secondary = Color(0xFF6FB7C7),
    onSecondary = Color(0xFF042027),
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Danger,
    onError = Color.White
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    bodyLarge = TextStyle(fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 13.sp, color = TextSecondary),
    labelSmall = TextStyle(fontSize = 11.sp, color = TextSecondary)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp)
)

@Composable
fun BuknuwoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
