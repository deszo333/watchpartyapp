package com.watchparty.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cinematic dark palette — this app is dark-only by design (watch-party UI).
private val WatchPartyDarkScheme = darkColorScheme(
    primary = Color(0xFFE5484D),      // accent red, sparing use (play/live indicators)
    onPrimary = Color(0xFF1A1A1F),
    background = Color(0xFF0D0D12),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF16161C),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF22222B),
    onSurfaceVariant = Color(0xFFB4B4C2),
    outline = Color(0xFF3A3A46)
)

@Composable
fun WatchPartyTheme(content: @Composable () -> Unit) {
    // Intentionally ignore system light/dark — always cinematic dark per the
    // product vision. isSystemInDarkTheme() left here only as a hook if you
    // later want to support a light mode.
    MaterialTheme(
        colorScheme = WatchPartyDarkScheme,
        typography = WatchPartyTypography,
        content = content
    )
}
