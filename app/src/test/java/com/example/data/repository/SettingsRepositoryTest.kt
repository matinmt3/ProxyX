package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsRepositoryTest {
    @Test
    fun newNeonThemeDefaultsToDarkButExplicitChoicesPersist() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)
        assertEquals("dark", repository.theme.first())
        repository.setTheme("light")
        assertEquals("light", SettingsRepository(context).theme.first())
        repository.setTheme("system")
        assertEquals("system", repository.theme.first())
    }

    @Test
    fun autoRefreshCanBeDisabledAndPersistsInDataStore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        repository.setAutoRefreshEnabled(false)
        assertFalse(repository.autoRefreshEnabled.first())

        repository.setAutoRefreshEnabled(true)
        assertTrue(repository.autoRefreshEnabled.first())
    }

    @Test
    fun adaptiveAndUnmeteredPreferencesPersist() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        repository.setAdaptiveWorkersEnabled(false)
        repository.setUnmeteredOnly(true)

        assertFalse(repository.adaptiveWorkersEnabled.first())
        assertTrue(repository.unmeteredOnly.first())
    }

    @Test
    fun telegramFinalistTransferIsOptInAndPersists() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        repository.setTelegramFinalistTransferEnabled(false)
        assertFalse(repository.telegramFinalistTransferEnabled.first())

        repository.setTelegramFinalistTransferEnabled(true)
        assertTrue(repository.telegramFinalistTransferEnabled.first())
    }

    @Test
    fun telegramTransferBudgetPersistsOnlySupportedCaps() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        repository.setTelegramTransferDataBudgetMb(256)
        assertEquals(256, repository.telegramTransferDataBudgetMb.first())

        repository.setTelegramTransferDataBudgetMb(75)
        assertEquals(0, repository.telegramTransferDataBudgetMb.first())
    }
}
