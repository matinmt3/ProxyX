package com.aistudio.proxyx.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.dismissOptionalPrompts()
    }

    @Test
    fun criticalUserJourneys() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.dismissOptionalPrompts()

        device.tapText("Benchmark")
        check(device.wait(Until.hasObject(By.text("Strict Telegram route verification")), UI_TIMEOUT_MS)) {
            "Benchmark screen did not become visible"
        }

        device.tapText("Settings")
        check(device.wait(Until.hasObject(By.text("Preferences")), UI_TIMEOUT_MS)) {
            "Settings screen did not become visible"
        }

        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 4,
            12
        )
        device.tapText("Home")
        check(device.wait(Until.hasObject(By.textContains("PROXY LIST")), UI_TIMEOUT_MS)) {
            "Home screen did not become visible"
        }
    }

    private fun UiDevice.dismissOptionalPrompts() {
        repeat(MAX_OPTIONAL_PROMPTS) {
            val later = wait(Until.findObject(By.text("Later")), OPTIONAL_PROMPT_TIMEOUT_MS)
                ?: findObject(By.text("\u0628\u0639\u062f\u0627\u064b"))
                ?: return
            later.visibleCenter.let { click(it.x, it.y) }
            waitForIdle()
        }
    }

    private fun UiDevice.tapText(text: String) {
        dismissOptionalPrompts()
        val target = wait(Until.findObject(By.text(text)), UI_TIMEOUT_MS)
            ?: error("Could not find navigation label: $text")
        target.visibleCenter.let { click(it.x, it.y) }
        waitForIdle()
        dismissOptionalPrompts()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.aistudio.proxyx.vbnmkw"
        const val OPTIONAL_PROMPT_TIMEOUT_MS = 1_500L
        const val MAX_OPTIONAL_PROMPTS = 2
        const val UI_TIMEOUT_MS = 5_000L
    }
}
