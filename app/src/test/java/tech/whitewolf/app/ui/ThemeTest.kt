package tech.whitewolf.app.ui

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun `dark mode yields a dark background`() {
        assertTrue(wwtColorScheme(dark = true).background.luminance() < 0.5f)
    }

    @Test
    fun `light mode yields a light background`() {
        assertTrue(wwtColorScheme(dark = false).background.luminance() > 0.5f)
    }

    @Test
    fun `dark background is darker than the light one`() {
        assertTrue(
            wwtColorScheme(dark = true).background.luminance() <
                wwtColorScheme(dark = false).background.luminance(),
        )
    }
}
