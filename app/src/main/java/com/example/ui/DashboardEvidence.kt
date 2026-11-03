package com.example.ui

import com.example.model.MtProtoProxy

internal fun hasHistoricalDashboardEvidence(proxy: MtProtoProxy?, currentNetwork: String): Boolean {
    if (proxy == null) return false
    val measured = proxy.lastChecked > 0 || proxy.verificationStatus != MtProtoProxy.VERIFICATION_UNTESTED
    return measured && (currentNetwork.isBlank() || proxy.networkFingerprint != currentNetwork)
}
