package com.example.data.web

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class WebProxySecurityTest {
    private fun frame(type: Int, stream: Int = 0, body: ByteArray = byteArrayOf()): ByteArray = ByteBuffer.allocate(8 + body.size)
        .put(type.toByte()).put((stream shr 16).toByte()).put((stream shr 8).toByte()).put(stream.toByte()).putInt(body.size).put(body).array()
    @Test fun emptyWelcomeIsAccepted() { assertTrue(WebProxySecurity.validFrame(frame(0x11))) }
    @Test fun welcomeWithPayloadIsRejected() { assertFalse(WebProxySecurity.validFrame(frame(0x11, body = byteArrayOf(1)))) }
    @Test fun pingIsBoundedTo64Bytes() {
        assertTrue(WebProxySecurity.validFrame(frame(5, body = ByteArray(64))))
        assertFalse(WebProxySecurity.validFrame(frame(5, body = ByteArray(65))))
    }
    @Test fun dataMustBelongToAStream() { assertFalse(WebProxySecurity.validFrame(frame(2, body = byteArrayOf(1)))) }
    @Test fun dataMustNotBeEmpty() { assertFalse(WebProxySecurity.validFrame(frame(2, 1))) }
    @Test fun dataCannotExceed64KiB() {
        assertTrue(WebProxySecurity.validFrame(frame(2, 1, ByteArray(65536))))
        assertFalse(WebProxySecurity.validFrame(frame(2, 1, ByteArray(65537))))
    }
    @Test fun closeMustBeEmpty() {
        assertTrue(WebProxySecurity.validFrame(frame(3, 1)))
        assertFalse(WebProxySecurity.validFrame(frame(3, 1, byteArrayOf(1))))
    }
    @Test fun windowMustHaveBoundedPositiveGrant() {
        for (amount in listOf(1, 4194304)) assertTrue(WebProxySecurity.validFrame(frame(4, 1, ByteBuffer.allocate(4).putInt(amount).array())))
        for (amount in listOf(0, -1, 4194305)) assertFalse(WebProxySecurity.validFrame(frame(4, 1, ByteBuffer.allocate(4).putInt(amount).array())))
    }
    @Test fun truncatedAndExtraPayloadsAreRejected() {
        val data = frame(2, 1, byteArrayOf(1, 2))
        assertFalse(WebProxySecurity.validFrame(data.copyOf(9)))
        assertFalse(WebProxySecurity.validFrame(data + byteArrayOf(3)))
        assertFalse(WebProxySecurity.validFrame(ByteArray(7)))
    }
    @Test fun batchesAndUnknownFrameTypesAreRejected() {
        assertFalse(WebProxySecurity.validFrame(frame(0x11) + frame(0x11)))
        assertFalse(WebProxySecurity.validFrame(frame(0xff)))
        assertFalse(WebProxySecurity.validFrame(frame(1, 1)))
    }
    @Test fun unsignedLengthOverflowIsRejected() {
        val data = frame(0x11)
        ByteBuffer.wrap(data).putInt(4, -1)
        assertFalse(WebProxySecurity.validFrame(data))
    }
    @Test fun securityScriptRestrictsStorageDevicesWorkersAndOrigins() {
        val script = WebProxySecurity.script("https://proxy.example.com")
        listOf("Content-Security-Policy", "wss://proxy.example.com", "worker-src 'none'", "indexedDB", "clipboard", "RTCPeerConnection").forEach {
            assertTrue(it, script.contains(it))
        }
        assertFalse(script.contains("unsafe-eval"))
    }
    @Test(expected = IllegalArgumentException::class) fun injectedOriginIsRejected() { WebProxySecurity.script("https://evil.example';alert(1)") }
    @Test fun documentStartPolicyAlwaysHasAHeadElement() {
        val script = WebProxySecurity.script("https://proxy.example.com")
        assertTrue(script.contains("parent = document.createElement('head')"))
        assertTrue(script.contains("document.documentElement.insertBefore(parent"))
        assertFalse(script.contains("document.head || document.documentElement"))
        assertTrue(script.indexOf("parent = document.createElement('head')") < script.indexOf("parent.insertBefore(csp"))
    }
    @Test fun legacyWireDataIsFragmentedBeforeNativeIpc() {
        val script = WebProxySecurity.script("https://proxy.example.com")
        assertTrue(script.contains("offset += 65536"))
        assertTrue(script.contains("new View(frame.buffer).setUint32(4, size, false)"))
        assertTrue(script.contains("frame.set(data.subarray(8 + offset, 8 + offset + size), 8)"))
    }
    @Test fun oversizedAndNonDataBridgeMessagesFailClosed() {
        val script = WebProxySecurity.script("https://proxy.example.com")
        assertTrue(script.contains("value.byteLength > 1048584"))
        assertTrue(script.contains("header.getUint8(0) !== 2 || id === 0"))
        assertTrue(script.contains("length !== value.byteLength - 8"))
        assertTrue(script.contains("writable:false,configurable:false"))
    }
}
