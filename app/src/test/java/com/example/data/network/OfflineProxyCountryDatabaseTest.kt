package com.example.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OfflineProxyCountryDatabaseTest {
    @Test
    fun resolvesPublicIpv4CountriesFromBundledDatabase() = runTest {
        val database = OfflineProxyCountryDatabase(RuntimeEnvironment.getApplication())

        val countries = database.resolve(
            listOf("78.55.53.58", "8.8.8.8", "not-an-ip", "127.0.0.1")
        )

        assertEquals("DE", countries["78.55.53.58"]?.code)
        assertEquals("US", countries["8.8.8.8"]?.code)
        assertFalse(countries.containsKey("not-an-ip"))
        assertFalse(countries.containsKey("127.0.0.1"))
    }
}
