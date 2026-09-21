package com.sailens.shell.design.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GuidanceTealLight,
    onPrimary = Color(0xFF003731),
    primaryContainer = GuidanceTealContainerDark,
    onPrimaryContainer = Color(0xFF9DF2DF),
    secondary = CautionAmberLight,
    onSecondary = Color(0xFF3F3100),
    tertiary = FocusBlueLight,
    onTertiary = Color(0xFF003258),
    background = SailensBackgroundDark,
    onBackground = SailensOnSurfaceDark,
    surface = SailensSurfaceDark,
    onSurface = SailensOnSurfaceDark,
    surfaceVariant = SailensSurfaceVariantDark,
    onSurfaceVariant = SailensOnSurfaceVariantDark,
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    primary = GuidanceTeal,
    onPrimary = Color.White,
    primaryContainer = GuidanceTealContainer,
    onPrimaryContainer = Color(0xFF00201B),
    secondary = CautionAmber,
    onSecondary = Color.White,
    tertiary = FocusBlue,
    onTertiary = Color.White,
    background = SailensBackgroundLight,
    onBackground = SailensOnSurfaceLight,
    surface = SailensSurfaceLight,
    onSurface = SailensOnSurfaceLight,
    surfaceVariant = SailensSurfaceVariantLight,
    onSurfaceVariant = SailensOnSurfaceVariantLight,
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun SailensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SailensShapes,
        content = content
    )
}
