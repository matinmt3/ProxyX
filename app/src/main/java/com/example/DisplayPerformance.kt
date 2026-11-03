package com.example

import android.app.Activity
import android.os.Build
import kotlin.math.abs

internal fun Activity.requestHighestSupportedRefreshRate() {
    @Suppress("DEPRECATION")
    val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display ?: windowManager.defaultDisplay
    } else {
        windowManager.defaultDisplay
    }

    val currentMode = activeDisplay.mode
    val highestCompatibleMode = activeDisplay.supportedModes
        .asSequence()
        .filter { mode ->
            mode.physicalWidth == currentMode.physicalWidth &&
                mode.physicalHeight == currentMode.physicalHeight
        }
        .maxByOrNull { it.refreshRate }

    val highestCompatibleRate = highestCompatibleMode?.refreshRate
        ?: activeDisplay.refreshRate
    if (!highestCompatibleRate.isFinite() || highestCompatibleRate <= 0f) return

    val attributes = window.attributes
    val needsUpdate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        attributes.preferredDisplayModeId != 0 ||
            abs(attributes.preferredRefreshRate - highestCompatibleRate) >= 0.1f
    } else {
        highestCompatibleMode != null &&
            attributes.preferredDisplayModeId != highestCompatibleMode.modeId
    }
    if (!needsUpdate) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        attributes.preferredDisplayModeId = 0
        attributes.preferredRefreshRate = highestCompatibleRate
    } else if (highestCompatibleMode != null) {
        attributes.preferredDisplayModeId = highestCompatibleMode.modeId
        attributes.preferredRefreshRate = 0f
    }
    window.attributes = attributes
}
