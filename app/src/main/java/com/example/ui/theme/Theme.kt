package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.compose.ui.graphics.Color

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    accentIndex: Int = 0,
    customAccentColor: Long = 0xFFF9B9BCL,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        accentIndex == 99 -> {
            createCustomColorScheme(Color(customAccentColor), darkTheme)
        }
        else -> {
            when (accentIndex) {
                1 -> if (darkTheme) EmeraldDarkColorScheme else EmeraldLightColorScheme
                2 -> if (darkTheme) OceanDarkColorScheme else OceanLightColorScheme
                3 -> if (darkTheme) AmberDarkColorScheme else AmberLightColorScheme
                4 -> if (darkTheme) RoseDarkColorScheme else RoseLightColorScheme
                5 -> if (darkTheme) NeonDarkColorScheme else NeonLightColorScheme
                6 -> if (darkTheme) VioletDarkColorScheme else VioletLightColorScheme
                else -> if (darkTheme) ExpressivePeachDarkColorScheme else ExpressivePeachLightColorScheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ExpressiveShapes,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
