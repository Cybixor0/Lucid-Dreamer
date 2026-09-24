// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lucid Dreamer contributors
package com.lucidreamer.ui.theme

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

/**
 * Theme choice, independent of the system setting.
 *
 * [NIGHT] is not "dark mode". It is a deliberately dim, red-shifted palette for
 * looking at the phone at 4am without flooding your eyes with blue light and
 * wrecking the rest of the night. It is applied automatically on the active
 * session screen and can be forced app-wide.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, NIGHT }

// ---------------------------------------------------------------------------
// Palette: calm deep indigo, low chroma. Nothing in this app should glow.
// ---------------------------------------------------------------------------

private val Indigo80 = Color(0xFFB4C2FF)
private val Indigo60 = Color(0xFF8A9CE8)
private val Indigo40 = Color(0xFF4A5CA8)
private val Indigo20 = Color(0xFF243063)

private val Slate90 = Color(0xFFE3E6F2)
private val Slate30 = Color(0xFF424758)
private val Slate10 = Color(0xFF141B2E)

private val NightBg = Color(0xFF0B0F1A)
private val NightSurface = Color(0xFF141B2E)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo40,
    onPrimaryContainer = Slate90,
    secondary = Indigo60,
    background = NightBg,
    onBackground = Slate90,
    surface = NightBg,
    onSurface = Slate90,
    surfaceVariant = NightSurface,
    onSurfaceVariant = Color(0xFFB9C0D4),
)

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Indigo20,
    secondary = Indigo40,
    background = Color(0xFFFBFAFF),
    onBackground = Slate10,
    surface = Color(0xFFFBFAFF),
    onSurface = Slate10,
    surfaceVariant = Color(0xFFE2E4F0),
    onSurfaceVariant = Slate30,
)

/**
 * Near-black with warm red accents and no blue. Used on the session screen so a
 * glance at the phone mid-night is as close to harmless as a screen can be.
 */
private val NightColors = darkColorScheme(
    primary = Color(0xFFD08A6A),
    onPrimary = Color(0xFF1A0B06),
    primaryContainer = Color(0xFF3A1C10),
    onPrimaryContainer = Color(0xFFE8B79E),
    secondary = Color(0xFFA9705A),
    background = Color(0xFF050302),
    onBackground = Color(0xFFB98A72),
    surface = Color(0xFF050302),
    onSurface = Color(0xFFB98A72),
    surfaceVariant = Color(0xFF1A0E08),
    onSurfaceVariant = Color(0xFF9A7058),
    outline = Color(0xFF4A2C1E),
)

@Composable
fun LucidDreamerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You. Off by default: it can pull the palette somewhere less calm. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.NIGHT -> true
    }
    val context = LocalContext.current

    val colors = when {
        themeMode == ThemeMode.NIGHT -> NightColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = LucidTypography,
        content = content,
    )
}
