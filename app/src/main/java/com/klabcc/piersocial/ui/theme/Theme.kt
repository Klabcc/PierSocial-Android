package com.klabcc.piersocial.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ObsidianBg = Color(0xFF090B10)
val SurfaceDark = Color(0xFF11151F)
val SurfaceCard = Color(0xFF171D2B)
val SurfaceElevated = Color(0xFF1F2738)
val MeshCyan = Color(0xFF00E5FF)
val MeshViolet = Color(0xFF8C5EFF)
val SignalGreen = Color(0xFF00E676)
val SignalAmber = Color(0xFFFFB300)
val SignalRed = Color(0xFFFF5252)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)
val BorderSubtle = Color(0xFF283247)

private val PierDarkColorScheme = darkColorScheme(
    primary = MeshCyan,
    onPrimary = Color(0xFF001F24),
    primaryContainer = Color(0xFF00363E),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = MeshViolet,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF2A1B54),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = SignalGreen,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceElevated,
    outline = BorderSubtle,
    error = SignalRed
)

private val PierTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp
    )
)

@Composable
fun PierSocialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PierDarkColorScheme,
        typography = PierTypography,
        content = content
    )
}
