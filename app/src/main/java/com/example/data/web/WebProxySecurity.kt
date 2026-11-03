package com.example.data.web

import java.nio.ByteBuffer

object WebProxySecurity {
    @JvmStatic
    fun validFrame(frame: ByteArray): Boolean {
        if (frame.size < 8 || frame.size > 65544) return false
        val buffer = ByteBuffer.wrap(frame)
        val type = buffer.get().toInt() and 255
        val stream = (buffer.get().toInt() and 255) shl 16 or
            ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() and 255)
        val length = buffer.int.toLong() and 0xffffffffL
        if (length != frame.size - 8L) return false
        return if (stream == 0) when (type) {
            0x11 -> length == 0L
            0x05 -> length <= 64L
            0x1f -> length <= 65536L
            else -> false
        } else when (type) {
            0x02 -> length in 1..65536L
            0x03 -> length == 0L
            0x04 -> length == 4L && (buffer.int.toLong() and 0xffffffffL) in 1..4194304L
            else -> false
        }
    }

    @JvmStatic
    fun script(origin: String): String {
        require(origin.matches(Regex("https://[a-z0-9.-]+")))
        return """
            (() => {
              'use strict';
              const policy = "default-src 'none'; base-uri 'none'; child-src 'none'; connect-src $origin ${origin.replace("https:", "wss:")}; font-src 'none'; form-action 'none'; frame-src 'none'; img-src 'none'; manifest-src 'none'; media-src 'none'; object-src 'none'; script-src 'unsafe-inline'; style-src 'none'; worker-src 'none'";
              let installed = false;
              let adapted = false;
              function adaptBridge() {
                const bridge = window.TelegramWebProxy;
                if (adapted || !bridge || typeof bridge.postMessage !== 'function') return;
                const send = bridge.postMessage.bind(bridge);
                const Buffer = ArrayBuffer, Bytes = Uint8Array, View = DataView;
                const forward = value => {
                  if (!(value instanceof Buffer) || value.byteLength <= 65544) { send(value); return; }
                  if (value.byteLength > 1048584) { send('{"t":"close"}'); return; }
                  const header = new View(value);
                  const length = header.getUint32(4, false);
                  const id = (header.getUint8(1) << 16) | (header.getUint8(2) << 8) | header.getUint8(3);
                  if (header.getUint8(0) !== 2 || id === 0 || length !== value.byteLength - 8) {
                    send('{"t":"close"}'); return;
                  }
                  const data = new Bytes(value);
                  for (let offset = 0; offset < length; offset += 65536) {
                    const size = Math.min(65536, length - offset);
                    const frame = new Bytes(8 + size);
                    frame.set(data.subarray(0, 4));
                    new View(frame.buffer).setUint32(4, size, false);
                    frame.set(data.subarray(8 + offset, 8 + offset + size), 8);
                    send(frame.buffer);
                  }
                };
                try {
                  Object.defineProperty(bridge, 'postMessage', {value:forward,writable:false,configurable:false});
                  adapted = true;
                } catch (_) { send('{"t":"close"}'); }
              }
              function protect() {
                adaptBridge();
                if (installed || !document.documentElement) return;
                let parent = document.head;
                if (!parent) {
                  parent = document.createElement('head');
                  document.documentElement.insertBefore(parent, document.documentElement.firstChild);
                }
                const csp = document.createElement('meta'); csp.httpEquiv = 'Content-Security-Policy'; csp.content = policy;
                parent.insertBefore(csp, parent.firstChild);
                const dns = document.createElement('meta'); dns.httpEquiv = 'x-dns-prefetch-control'; dns.content = 'off'; parent.appendChild(dns);
                installed = true;
              }
              protect(); new MutationObserver(protect).observe(document, {childList:true,subtree:true});
              const deny = () => { throw new Error('Unavailable in proxy carrier'); };
              const lock = (o,k,v) => { try { Object.defineProperty(o,k,{value:v,writable:false,configurable:false}); } catch (_) {} };
              for (const k of ['localStorage','sessionStorage','indexedDB','caches','Worker','SharedWorker','BroadcastChannel','Audio','AudioContext','webkitAudioContext','RTCPeerConnection','webkitRTCPeerConnection']) lock(window,k,undefined);
              for (const k of ['open','print','alert','confirm','prompt']) lock(window,k,deny);
              for (const k of ['clipboard','mediaDevices','geolocation','serviceWorker','usb','serial','bluetooth','hid','credentials']) lock(navigator,k,undefined);
              try { Object.defineProperty(document,'cookie',{get:()=>'',set:()=>{},configurable:false}); } catch (_) {}
            })();
        """.trimIndent()
    }
}
