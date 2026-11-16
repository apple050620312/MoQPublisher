package com.sangege.moqpublisher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF002F60),
    primaryContainer = Color(0xFF163A60),
    onPrimaryContainer = Color(0xFFD5E7FF),
    secondary = SlateLight,
    secondaryContainer = Color(0xFF29394C),
    onSecondaryContainer = Color(0xFFD8E6FA),
    tertiary = TealLight,
    tertiaryContainer = Color(0xFF164B43),
    onTertiaryContainer = Color(0xFFA0F2E1),
    background = Color(0xFF0D1118),
    onBackground = Color(0xFFE1E7F0),
    surface = Color(0xFF141B25),
    onSurface = Color(0xFFE1E7F0),
    surfaceVariant = Color(0xFF283343),
    onSurfaceVariant = Color(0xFFBDC8D8),
    outline = Color(0xFF8390A4)
)

private val LightColorScheme = lightColorScheme(
    primary = BlueDark,
    primaryContainer = Color(0xFFD5E7FF),
    onPrimaryContainer = Color(0xFF12395F),
    secondary = SlateDark,
    secondaryContainer = Color(0xFFDCE6F4),
    onSecondaryContainer = Color(0xFF22364C),
    tertiary = TealDark,
    tertiaryContainer = Color(0xFFB7F2E4),
    onTertiaryContainer = Color(0xFF004F43),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF17202C),
    surface = Color(0xFFF9FAFE),
    onSurface = Color(0xFF17202C),
    surfaceVariant = Color(0xFFE0E7F0),
    onSurfaceVariant = Color(0xFF465568),
    outline = Color(0xFF737F90)
)

@Composable
fun MoQPublisherTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
