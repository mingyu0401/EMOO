package com.example.emoo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.emoo.model.ThemeColor
import com.example.emoo.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/** 每个主题色在浅色/深色方案下的 primary/secondary/tertiary（Material3 色调） */
private data class AccentScheme(
    val lightPrimary: Color,
    val lightSecondary: Color,
    val lightTertiary: Color,
    val darkPrimary: Color,
    val darkSecondary: Color,
    val darkTertiary: Color
)

private val AccentSchemes = mapOf(
    ThemeColor.PURPLE to AccentScheme(
        Purple40, PurpleGrey40, Pink40,
        Purple80, PurpleGrey80, Pink80
    ),
    ThemeColor.BLUE to AccentScheme(
        Color(0xFF00639B), Color(0xFF51606F), Color(0xFF6A5779),
        Color(0xFF9FCAFF), Color(0xFFB4C9DA), Color(0xFFD7BDE3)
    ),
    ThemeColor.GREEN to AccentScheme(
        Color(0xFF3F6A3B), Color(0xFF54634E), Color(0xFF3A665B),
        Color(0xFFA7D1A0), Color(0xFFB8CCB0), Color(0xFF9FD3C4)
    ),
    ThemeColor.ORANGE to AccentScheme(
        Color(0xFF8A5100), Color(0xFF735839), Color(0xFF6B5257),
        Color(0xFFFFB967), Color(0xFFE1C3A6), Color(0xFFDDBBC3)
    ),
    ThemeColor.PINK to AccentScheme(
        Color(0xFF9C405F), Color(0xFF74555E), Color(0xFF8E4F52),
        Color(0xFFF5B2C7), Color(0xFFE2BCC7), Color(0xFFE6B5B5)
    )
)

private fun accentSchemeOf(color: ThemeColor): AccentScheme =
    AccentSchemes[color] ?: AccentSchemes.getValue(ThemeColor.PURPLE)

/** 主题色的代表色（设置页圆圈按钮/色板用），按深浅色取对应 primary */
fun themeColorSwatch(color: ThemeColor, dark: Boolean): Color {
    val scheme = accentSchemeOf(color)
    return if (dark) scheme.darkPrimary else scheme.lightPrimary
}

/**
 * 支持“浅色 / 深色 / 跟随系统”三档切换 + 五种主题色（改变按钮/开关/选中项等
 * 交互选项的颜色），切换即时生效（无需重启）。
 * 两档设置由 [ThemeMode]/[ThemeColor] 持久化在 MetaPreferences 中，SettingsViewModel 驱动。
 */
@Composable
fun EMOOTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    themeColor: ThemeColor = ThemeColor.PURPLE,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = accentSchemeOf(themeColor)
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = scheme.darkPrimary,
            secondary = scheme.darkSecondary,
            tertiary = scheme.darkTertiary
        )
    } else {
        lightColorScheme(
            primary = scheme.lightPrimary,
            secondary = scheme.lightSecondary,
            tertiary = scheme.lightTertiary
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
