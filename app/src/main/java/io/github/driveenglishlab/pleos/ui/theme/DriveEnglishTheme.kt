package io.github.driveenglishlab.pleos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.driveenglishlab.pleos.R
import io.github.driveenglishlab.pleos.ui.DeviceAppearance

/** Adapted visual assets and structure from UI1470722; no preview business state. */
object ProductMetrics { val touch = 76.dp; val play = 88.dp; val gap = 24.dp; val panelRadius = 16.dp }

@OptIn(ExperimentalTextApi::class)
@Composable
fun DriveEnglishTheme(appearance: DeviceAppearance, content: @Composable () -> Unit) {
    val family = remember { FontFamily(listOf(400, 500, 600).map { weight ->
        Font(R.font.asta_sans, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    }) }
    fun text(size: Int, line: Int, weight: Int = 400) = TextStyle(fontFamily = family,
        fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight))
    val typography = Typography(headlineLarge = text(40, 52, 600), headlineMedium = text(36, 46, 600),
        headlineSmall = text(32, 42, 600), titleLarge = text(32, 42, 600), titleMedium = text(28, 38, 500),
        titleSmall = text(24, 34, 500), bodyLarge = text(32, 50), bodyMedium = text(28, 42),
        bodySmall = text(24, 36), labelLarge = text(28, 36, 500), labelMedium = text(24, 34, 500), labelSmall = text(24, 34))
    val light = lightColorScheme(primary = Color(0xFF1B64DA), onPrimary = Color.White,
        tertiary = Color(0xFF3182F6), onTertiary = Color.White,
        primaryContainer = Color(0xFFE8F3FF), onPrimaryContainer = Color(0xFF1B64DA),
        surface = Color(0xFFF7F8FA), onSurface = Color(0xFF191F28), surfaceVariant = Color(0xFFF2F4F6),
        onSurfaceVariant = Color(0xFF4E5968), surfaceContainerLowest = Color.White,
        outlineVariant = Color(0xFFD9DFE7), error = Color(0xFFB42332))
    val dark = darkColorScheme(primary = Color(0xFF79ADFF), onPrimary = Color(0xFF10213B),
        tertiary = Color(0xFF79ADFF), onTertiary = Color(0xFF10213B),
        primaryContainer = Color(0xFF22395B), onPrimaryContainer = Color(0xFF9BC1FF),
        surface = Color(0xFF171B22), onSurface = Color(0xFFF4F6F9), surfaceVariant = Color(0xFF2A3341),
        onSurfaceVariant = Color(0xFFAEB8C5), surfaceContainerLowest = Color(0xFF202630),
        outlineVariant = Color(0xFF3A4451), error = Color(0xFFFFACB5))
    val useDark = when (appearance) { DeviceAppearance.SYSTEM -> isSystemInDarkTheme(); DeviceAppearance.DARK -> true; DeviceAppearance.LIGHT -> false }
    MaterialTheme(colorScheme = if (useDark) dark else light, typography = typography, content = content)
}
