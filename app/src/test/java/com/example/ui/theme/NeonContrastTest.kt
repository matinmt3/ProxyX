package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class NeonContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    @Test fun darkBodyAndSecondaryTextMeetNormalTextContrast() {
        assertTrue(contrast(DarkOnSurface, DarkSurface) >= 4.5f)
        assertTrue(contrast(DarkOnSurfaceVariant, DarkSurface) >= 4.5f)
        assertTrue(contrast(DarkPrimary, DarkBackground) >= 4.5f)
        assertTrue(contrast(DarkSecondary, DarkSurface) >= 4.5f)
    }

    @Test fun lightBodyAndActionsMeetNormalTextContrast() {
        assertTrue(contrast(LightOnSurface, LightSurface) >= 4.5f)
        assertTrue(contrast(LightOnSurfaceVariant, LightSurface) >= 4.5f)
        assertTrue(contrast(Color.White, LightPrimary) >= 4.5f)
        assertTrue(contrast(Color.White, LightSecondary) >= 4.5f)
    }
}
