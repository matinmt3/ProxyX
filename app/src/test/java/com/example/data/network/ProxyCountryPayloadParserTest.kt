package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProxyCountryPayloadParserTest {
    @Test
    fun parsesValidatedCountryDataAndSkipsUnknownLocations() {
        val payload = """
            {
              "results": {
                "78.55.53.58": {
                  "location": {
                    "country": "Germany",
                    "country_code": "DE"
                  }
                },
                "8.8.8.8": {
                  "location": {
                    "country": null,
                    "country_code": "US"
                  }
                },
                "1.1.1.1": {
                  "location": {
                    "country": null,
                    "country_code": null
                  }
                }
              }
            }
        """.trimIndent()

        val countries = ProxyCountryPayloadParser.parseIpApiIo(payload)

        assertEquals(ResolvedProxyCountry("Germany", "DE"), countries["78.55.53.58"])
        assertEquals(ResolvedProxyCountry("United States", "US"), countries["8.8.8.8"])
        assertFalse(countries.containsKey("1.1.1.1"))
    }

    @Test
    fun malformedPayloadFailsClosed() {
        assertEquals(
            emptyMap<String, ResolvedProxyCountry>(),
            ProxyCountryPayloadParser.parseIpApiIo("{")
        )
        assertEquals(
            emptyMap<String, ResolvedProxyCountry>(),
            ProxyCountryPayloadParser.parseHackMyIp("{")
        )
    }

    @Test
    fun parsesHackMyIpBulkResponse() {
        val payload = """
            {
              "success": true,
              "data": {
                "total": 2,
                "results": [
                  {"ip": "78.55.53.58", "country": "DE"},
                  {"ip": "8.8.8.8", "country": "US"}
                ]
              }
            }
        """.trimIndent()

        val countries = ProxyCountryPayloadParser.parseHackMyIp(payload)

        assertEquals(ResolvedProxyCountry("Germany", "DE"), countries["78.55.53.58"])
        assertEquals(ResolvedProxyCountry("United States", "US"), countries["8.8.8.8"])
    }
}
