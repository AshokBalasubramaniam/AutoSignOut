package com.autoattendance.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.autoattendance.app.ui.LocalReduceMotion
import com.autoattendance.app.ui.reduceMotion

/** Design tokens of the lavender dashboard design. Read with `LocalPalette.current`. */
data class AppPalette(
    val background: Color,
    val card: Color,
    val cardBorder: Color,
    val primary: Color,
    val onPrimary: Color,
    /** Filled fields, selected tab, segmented selection. */
    val primarySoft: Color,
    /** Inner panels and soft rows. */
    val primarySofter: Color,
    val text: Color,
    val textMuted: Color,
    val outline: Color,
    val green: Color,
    val greenSoft: Color,
    val pink: Color,
    val pinkSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val disabledBg: Color,
    val disabledText: Color,
)

val LightPalette = AppPalette(
    background = Color(0xFFF6F4FC),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFEDE9F8),
    primary = Color(0xFF5B32E0),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFE6DEFF),
    primarySofter = Color(0xFFF3F0FD),
    text = Color(0xFF1B1830),
    textMuted = Color(0xFF6D6884),
    outline = Color(0xFFD9D3EE),
    green = Color(0xFF16956A),
    greenSoft = Color(0xFFDDF5EB),
    pink = Color(0xFFE0336B),
    pinkSoft = Color(0xFFFDE2EB),
    danger = Color(0xFFC62828),
    dangerSoft = Color(0xFFFDE7E7),
    amber = Color(0xFFB26A00),
    amberSoft = Color(0xFFFFF1D9),
    disabledBg = Color(0xFFECEBF1),
    disabledText = Color(0xFF9A97A8),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // Always the white theme, whatever the phone's dark-mode setting.
    val p = LightPalette
    val base = lightColorScheme()
    val scheme = base.copy(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primarySoft,
        onPrimaryContainer = p.text,
        secondaryContainer = p.primarySoft,
        onSecondaryContainer = p.text,
        background = p.background,
        onBackground = p.text,
        surface = p.card,
        onSurface = p.text,
        surfaceVariant = p.primarySofter,
        onSurfaceVariant = p.textMuted,
        surfaceContainerHigh = p.card,
        surfaceContainerHighest = p.primarySofter,
        outline = p.outline,
        outlineVariant = p.cardBorder,
        error = p.danger,
        tertiary = p.pink,
    )
    val context = LocalContext.current
    val reduce = remember(context) { reduceMotion(context) }
    CompositionLocalProvider(LocalPalette provides p, LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
