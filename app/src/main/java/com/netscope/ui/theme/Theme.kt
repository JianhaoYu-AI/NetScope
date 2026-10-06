package com.netscope.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B60), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F2EB), onPrimaryContainer = Color(0xFF004D44),
    secondary = Color(0xFF294462), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EDF5), onSecondaryContainer = Color(0xFF162D47),
    tertiary = Color(0xFF805710), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFECCD), onTertiaryContainer = Color(0xFF5D3E08),
    background = Color(0xFFF4F6F9), onBackground = Color(0xFF14273E),
    surface = Color.White, onSurface = Color(0xFF14273E),
    surfaceVariant = Color(0xFFEDF1F6), onSurfaceVariant = Color(0xFF526174),
    outline = Color(0xFF748295), outlineVariant = Color(0xFFDEE5ED),
    error = Color(0xFFAE333D), onError = Color.White,
    errorContainer = Color(0xFFFFE8E9), onErrorContainer = Color(0xFF7F2028),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81D6C4), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF174E46), onPrimaryContainer = Color(0xFFBFF4E8),
    secondary = Color(0xFFB7CBE6), onSecondary = Color(0xFF19314D),
    secondaryContainer = Color(0xFF283E59), onSecondaryContainer = Color(0xFFDCE8FA),
    tertiary = Color(0xFFEBC583), onTertiary = Color(0xFF402B0A),
    tertiaryContainer = Color(0xFF513D1F), onTertiaryContainer = Color(0xFFFFE0A8),
    background = Color(0xFF0E1826), onBackground = Color(0xFFE6EDF6),
    surface = Color(0xFF162334), onSurface = Color(0xFFE6EDF6),
    surfaceVariant = Color(0xFF233247), onSurfaceVariant = Color(0xFFB6C4D5),
    outline = Color(0xFF899AAD), outlineVariant = Color(0xFF34465C),
    error = Color(0xFFFFB1B7), onError = Color(0xFF650D1A),
    errorContainer = Color(0xFF552830), onErrorContainer = Color(0xFFFFDADD),
)

/** Stable presentation palette, with native system fonts and light/dark support. */
@Composable
fun NetScopeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
