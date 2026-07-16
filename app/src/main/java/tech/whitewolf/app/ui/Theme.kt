package tech.whitewolf.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Default Material 3 schemes — the app has no bespoke brand palette, so the
// baseline light/dark schemes are enough to make the native chrome (login,
// banners, error screen) follow the system setting. Pure and non-@Composable
// so the light/dark choice is unit-testable.
fun wwtColorScheme(dark: Boolean): ColorScheme =
    if (dark) darkColorScheme() else lightColorScheme()

/**
 * App theme that follows the system light/dark setting. Wrapping the native UI
 * in this (instead of a bare `MaterialTheme {}`, which is always light) is what
 * makes the Compose chrome honor dark mode. The WebView's dark signal comes
 * separately from the DayNight activity theme (see AndroidManifest / values-night).
 */
@Composable
fun WwtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = wwtColorScheme(isSystemInDarkTheme()), content = content)
}
