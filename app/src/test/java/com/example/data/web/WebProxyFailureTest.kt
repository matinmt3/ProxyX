package com.example.data.web

import com.example.data.network.MeteredNetworkNotAllowedException
import com.example.data.network.UserNetworkChangedException
import com.example.data.network.UserNetworkUnavailableException
import java.net.UnknownHostException
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Test

class WebProxyFailureTest {
    @Test fun absentNetworkIsNotDeadProxyEvidence() {
        assertEquals(WebProxyFailure("NO_NETWORK", "INCONCLUSIVE"), classifyWebProxyFailure(UserNetworkUnavailableException()))
    }
    @Test fun changedNetworkInvalidatesMeasurement() {
        assertEquals(WebProxyFailure("NETWORK_CHANGED", "INCONCLUSIVE"), classifyWebProxyFailure(UserNetworkChangedException()))
    }
    @Test fun meteredPolicyIsNotProviderFailure() {
        assertEquals(WebProxyFailure("UNMETERED_REQUIRED", "INCONCLUSIVE"), classifyWebProxyFailure(MeteredNetworkNotAllowedException()))
    }
    @Test fun unresolvedDnsDoesNotProveOfflineEndpoint() {
        assertEquals(WebProxyFailure("DNS_FAILED", "INCONCLUSIVE"), classifyWebProxyFailure(UnknownHostException("sensitive-host")))
    }
    @Test fun boundedResolverPressureIsLocalIssue() {
        assertEquals(WebProxyFailure("DNS_BUSY", "INCONCLUSIVE"), classifyWebProxyFailure(RejectedExecutionException()))
    }
    @Test fun webviewCapabilityFailureIsClientIssue() {
        assertEquals(WebProxyFailure("WEBVIEW_UPDATE_REQUIRED", "CLIENT_UNAVAILABLE"), classifyWebProxyFailure(IllegalStateException("WEBVIEW_UPDATE_REQUIRED")))
    }
    @Test fun failedTelegramResponseCannotBePassed() {
        assertEquals(WebProxyFailure("TELEGRAM_ROUTE_FAILED", "FAILED"), classifyWebProxyFailure(IllegalStateException("TELEGRAM_ROUTE_FAILED")))
    }
    @Test fun arbitraryExceptionTextIsNotExposed() {
        assertEquals(WebProxyFailure("TEST_FAILED", "FAILED"), classifyWebProxyFailure(IllegalStateException("https://host/?bridge=private")))
    }
}
