package com.example.data.web;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// Telegram Android authors, GPL-2.0; see third_party/telegram-web/GPL-2.0.txt.
// Adapted on 2026-10-05 from c84801762fd5f936c8296ecf09a14a48ebfc4fe4:
// bounded executors/queues, hardened WebView, one-shot tests without background reconnect.
public final class WebProxyTransport {
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.content.Context context;
    private static final int FRAME_OPEN = 0x01;
    private static final int FRAME_DATA = 0x02;
    private static final int FRAME_CLOSE = 0x03;
    private static final int FRAME_WINDOW = 0x04;
    private static final int FRAME_PING = 0x05;
    private static final int FRAME_PONG = 0x06;
    private static final int FRAME_HELLO = 0x10;
    private static final int FRAME_WELCOME = 0x11;
    private static final int FRAME_BYE = 0x1f;
    private static final int FRAME_HEADER = 8;
    private static final int FRAME_MAX_PAYLOAD = 1024 * 1024;
    private static final int DATA_CHUNK = 64 * 1024;
    private static final long INITIAL_WINDOW = 4L * 1024 * 1024;
    private static final int MAX_STREAMS = 16;
    private static final int MAX_OUTBOUND_ITEMS = 128;
    private static final int MAX_OUTBOUND_BYTES = 8 * 1024 * 1024;
    private static final String BRIDGE_OBJECT = "TelegramWebProxy";

    private static final Object staticLock = new Object();
    private static WebProxyTransport connectionTestInstance;

    private final Object lock = new Object();
    private final String address;
    private final String host;
    private final String path;
    private final String bridgePath;
    private final String secret;
    private final String origin;
    private final String bridgeUrl;
    private final String androidNonce;
    private final ServerSocket serverSocket;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(MAX_STREAMS + 1);
    private final ExecutorService carrierExecutor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64));
    private final AtomicInteger nextStreamId = new AtomicInteger(1);
    private final Map<Integer, Stream> streams = new HashMap<>();
    private final ArrayDeque<byte[]> outbound = new ArrayDeque<>();

    private WebView webView;
    private JavaScriptReplyProxy replyProxy;
    private boolean carrierConnected;
    private boolean stopped;
    private boolean restartScheduled;
    private int outboundBytes;

    public static int startConnectionCheck(android.content.Context context, String address, String secret, ReadyCallback readyCallback) {
        Address normalized = normalizeAddress(address);
        byte[] secretBytes = decodeSecret(secret);
        if (normalized == null || secretBytes == null || !isSupported()) {
            return 0;
        }
        synchronized (staticLock) {
            if (connectionTestInstance != null) {
                connectionTestInstance.stopInternal();
                connectionTestInstance = null;
            }
            try {
                connectionTestInstance = new WebProxyTransport(context, normalized, secret, secretBytes);
                connectionTestInstance.readyCallback = readyCallback;
                connectionTestInstance.startInternal();
                return connectionTestInstance.serverSocket.getLocalPort();
            } catch (Exception e) {
                android.util.Log.w("WebProxyTransport", "WEB carrier operation failed.");
                if (connectionTestInstance != null) {
                    connectionTestInstance.stopInternal();
                    connectionTestInstance = null;
                }
                return 0;
            }
        }
    }

    public static void stopConnectionCheck() {
        synchronized (staticLock) {
            if (connectionTestInstance != null) {
                connectionTestInstance.stopInternal();
                connectionTestInstance = null;
            }
        }
    }

    private ReadyCallback readyCallback;

    public interface ReadyCallback {
        void onReady();
    }



    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        } catch (Throwable e) {
            android.util.Log.w("WebProxyTransport", "WEB carrier operation failed.");
            return false;
        }
    }

    public static boolean isValidSecret(String value) {
        return decodeSecret(value) != null;
    }

    public static String normalizeHost(String value) {
        if (value == null) {
            return "";
        }
        value = value.trim();
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
        if (value.length() > 253 || value.indexOf('.') <= 0 || value.contains(":") || value.matches("[0-9.]+")) {
            return "";
        }
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return "";
            }
        }
        return value;
    }

    private static Address normalizeAddress(String value) {
        if (value == null) {
            return null;
        }
        int slash = value.indexOf('/');
        String host = normalizeHost(slash >= 0 ? value.substring(0, slash) : value);
        String path = slash >= 0 ? value.substring(slash + 1) : "";
        if (TextUtils.isEmpty(host) || !isValidPath(path)) {
            return null;
        }
        return new Address(host, path);
    }

    private static boolean isValidPath(String value) {
        if (value.length() > 128) {
            return false;
        }
        if (value.isEmpty()) {
            return true;
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || !Character.isLetterOrDigit(segment.charAt(0))) {
                return false;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (!(c >= 'A' && c <= 'Z')
                        && !(c >= 'a' && c <= 'z')
                        && !(c >= '0' && c <= '9')
                        && c != '_'
                        && c != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private WebProxyTransport(android.content.Context context, Address address, String secret, byte[] secretBytes) throws Exception {
        this.context = context.getApplicationContext();
        this.address = address.value;
        this.host = address.host;
        this.path = address.path;
        bridgePath = path.isEmpty() ? "/" : "/" + path + "/";
        this.secret = secret;
        origin = "https://" + host;
        androidNonce = randomToken(32);
        String bridgeContext = path.isEmpty()
                ? "tdesktop-web-proxy-bridge-v1\n" + host
                : "tdesktop-web-proxy-bridge-v2\n" + host + "\n" + path;
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        String capability = Base64.encodeToString(
                hmac.doFinal(bridgeContext.getBytes(StandardCharsets.UTF_8)),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        bridgeUrl = origin + bridgePath + "?bridge=" + capability + "#android=" + androidNonce;
        serverSocket = new ServerSocket(0, MAX_STREAMS, InetAddress.getByName("127.0.0.1"));
    }

    private void startInternal() {
        ioExecutor.execute(this::acceptLoop);
        MAIN.post(this::createWebView);
    }

    private void stopInternal() {
        ArrayList<Stream> close;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            carrierConnected = false;
            restartScheduled = false;
            close = new ArrayList<>(streams.values());
            streams.clear();
            outbound.clear();
            outboundBytes = 0;
            lock.notifyAll();
        }
        try {
            serverSocket.close();
        } catch (Exception ignore) {
        }
        for (Stream stream : close) {
            closeSocket(stream.socket);
        }
        MAIN.post(() -> {
            if (replyProxy != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                try {
                    replyProxy.postMessage("{\"t\":\"close\"}");
                } catch (Exception ignore) {
                }
            }
            destroyWebView();
        });
        ioExecutor.shutdownNow();
        carrierExecutor.shutdownNow();
    }

    private void acceptLoop() {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Stream stream;
                synchronized (lock) {
                    if (stopped || streams.size() >= MAX_STREAMS) {
                        closeSocket(socket);
                        continue;
                    }
                    int streamId = allocateStreamId();
                    stream = new Stream(streamId, socket);
                    streams.put(streamId, stream);
                    if (carrierConnected) {
                        stream.opened = true;
                        sendFrame(FRAME_OPEN, streamId, null);
                    }
                }
                Stream value = stream;
                ioExecutor.execute(() -> readLoop(value));
            } catch (Exception e) {
                synchronized (lock) {
                    if (stopped) {
                        return;
                    }
                }
                android.util.Log.w("WebProxyTransport", "WEB carrier operation failed.");
                failCarrier();
                return;
            }
        }
    }

    private int allocateStreamId() {
        while (true) {
            int result = nextStreamId.getAndUpdate(value -> value >= 0x00ffffff ? 1 : value + 1);
            if (result != 0 && !streams.containsKey(result)) {
                return result;
            }
        }
    }

    private void readLoop(Stream stream) {
        byte[] buffer = new byte[DATA_CHUNK];
        try {
            InputStream input = stream.socket.getInputStream();
            while (true) {
                int allowance;
                synchronized (lock) {
                    while (!stopped && streams.get(stream.id) == stream
                            && (!carrierConnected || !stream.opened || stream.sendWindow == 0)) {
                        lock.wait();
                    }
                    if (stopped || streams.get(stream.id) != stream) {
                        return;
                    }
                    allowance = (int) Math.min(DATA_CHUNK, stream.sendWindow);
                }
                int count = input.read(buffer, 0, allowance);
                if (count < 0) {
                    closeStream(stream, true);
                    return;
                }
                if (count == 0) {
                    continue;
                }
                byte[] data = new byte[count];
                System.arraycopy(buffer, 0, data, 0, count);
                synchronized (lock) {
                    if (stopped || streams.get(stream.id) != stream || !carrierConnected) {
                        return;
                    }
                    stream.sendWindow -= count;
                    sendFrame(FRAME_DATA, stream.id, data);
                }
            }
        } catch (Exception e) {
            closeStream(stream, true);
        }
    }

    private void createWebView() {
        try {
            createWebViewInternal();
        } catch (Exception error) {
            android.util.Log.w("WebProxyTransport", "Unable to initialize the restricted WEB carrier.");
            failCarrier();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebViewInternal() {
        synchronized (lock) {
            if (stopped || webView != null) {
                return;
            }
            restartScheduled = false;
        }
        destroyWebView();
        WebView view;
        try {
            view = new WebView(context);
        } catch (Exception e) {
            android.util.Log.w("WebProxyTransport", "WEB carrier operation failed.");
            failCarrier();
            return;
        }
        webView = view;
        view.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false);
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        view.setDownloadListener((a,b,c,d,e) -> {});
        view.setWebChromeClient(new WebCarrierChromeClient());
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView current, WebResourceRequest request) {
                return !request.isForMainFrame() || !isBridgeNavigation(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView current, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                        && !isAllowedNetworkRequest(url)) {
                    return new WebResourceResponse("text/plain", "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onReceivedSslError(WebView current, SslErrorHandler handler, SslError error) {
                handler.cancel();
                failWebView(current);
            }

            @Override
            public void onReceivedError(WebView current, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    failWebView(current);
                }
            }

            @Override
            public void onReceivedHttpError(WebView current, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    failWebView(current);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView current, RenderProcessGoneDetail detail) {
                failWebView(current);
                return true;
            }
        });
        Set<String> rules = new HashSet<>();
        rules.add(origin);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, BRIDGE_OBJECT, rules, this::onWebMessage);
        } else {
            failCarrier(); return;
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, WebProxySecurity.script(origin), rules);
        } else {
            failCarrier(); return;
        }
        view.loadUrl(bridgeUrl);
    }

    private void failWebView(WebView source) {
        if (source == webView) {
            failCarrier();
        }
    }

    private boolean isBridgeNavigation(Uri value) {
        return value != null && bridgeUrl.equals(value.toString());
    }

    private boolean isAllowedNetworkRequest(Uri value) {
        String requestPath = value.getPath();
        return "https".equalsIgnoreCase(value.getScheme())
                && host.equalsIgnoreCase(value.getHost())
                && value.getUserInfo() == null
                && (value.getPort() == -1 || value.getPort() == 443)
                && requestPath != null
                && requestPath.startsWith(bridgePath);
    }

    private boolean isCurrentBridgeDocument(WebView source) {
        String currentUrl = source.getUrl();
        if (currentUrl == null) {
            return false;
        }
        Uri value = Uri.parse(currentUrl);
        return "https".equalsIgnoreCase(value.getScheme())
                && host.equalsIgnoreCase(value.getHost())
                && value.getUserInfo() == null
                && value.getPort() == -1
                && bridgePath.equals(value.getPath());
    }

    private void onWebMessage(
            WebView sourceView,
            WebMessageCompat message,
            Uri sourceOrigin,
            boolean isMainFrame,
            JavaScriptReplyProxy sourceReplyProxy) {
        if (sourceView != webView
                || !isMainFrame
                || sourceOrigin == null
                || !origin.equals(sourceOrigin.toString())
                || !isCurrentBridgeDocument(sourceView)) {
            return;
        }
        if (message.getType() == WebMessageCompat.TYPE_STRING) {
            String control = message.getData();
            if (control == null || control.length() > 1024) { failCarrier(); return; }
            handleControl(control, sourceReplyProxy);
        } else if (message.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            synchronized (lock) {
                if (stopped || replyProxy == null || replyProxy != sourceReplyProxy) {
                    return;
                }
            }
            byte[] data = message.getArrayBuffer();
            if (data == null || !WebProxySecurity.validFrame(data)) { failCarrier(); return; }
            try { carrierExecutor.execute(() -> processFrames(data)); }
            catch (java.util.concurrent.RejectedExecutionException e) { failCarrier(); }
        }
    }

    private void handleControl(String data, JavaScriptReplyProxy sourceReplyProxy) {
        try {
            JSONObject object = new JSONObject(data);
            String type = object.optString("t");
            if ("tproxy-android-init".equals(type)
                    && object.optInt("v") == 1
                    && androidNonce.equals(object.optString("nonce"))) {
                synchronized (lock) {
                    if (stopped || replyProxy != null) {
                        return;
                    }
                    replyProxy = sourceReplyProxy;
                }
                sendFrame(FRAME_HELLO, 0, new byte[]{1});
                return;
            }
            if ("close".equals(type) || "failed".equals(object.optString("state"))) {
                failCarrier();
            }
        } catch (Exception ignore) {
        }
    }

    private void processFrames(byte[] input) {
        int offset = 0;
        while (offset < input.length) {
            if (input.length - offset < FRAME_HEADER) {
                failCarrier();
                return;
            }
            int type = input[offset] & 0xff;
            int streamId = (input[offset + 1] & 0xff) << 16
                    | (input[offset + 2] & 0xff) << 8
                    | (input[offset + 3] & 0xff);
            long length = (input[offset + 4] & 0xffL) << 24
                    | (input[offset + 5] & 0xffL) << 16
                    | (input[offset + 6] & 0xffL) << 8
                    | (input[offset + 7] & 0xffL);
            long end = offset + FRAME_HEADER + length;
            if (length > FRAME_MAX_PAYLOAD || end > input.length) {
                failCarrier();
                return;
            }
            byte[] payload = new byte[(int) length];
            System.arraycopy(input, offset + FRAME_HEADER, payload, 0, payload.length);
            if (!processFrame(type, streamId, payload)) {
                failCarrier();
                return;
            }
            offset = (int) end;
        }
    }

    private boolean processFrame(int type, int streamId, byte[] payload) {
        if (streamId == 0) {
            if (type == FRAME_WELCOME && payload.length == 0) {
                ArrayList<Stream> open;
                ReadyCallback callback;
                synchronized (lock) {
                    if (stopped || carrierConnected) {
                        return false;
                    }
                    carrierConnected = true;
                    open = new ArrayList<>(streams.values());
                    for (Stream stream : open) {
                        stream.opened = true;
                        sendFrame(FRAME_OPEN, stream.id, null);
                    }
                    callback = readyCallback;
                    lock.notifyAll();
                }
                if (callback != null) {
                    MAIN.post(callback::onReady);
                }
                return true;
            } else if (type == FRAME_PING && payload.length <= 64) {
                sendFrame(FRAME_PONG, 0, payload);
                return true;
            } else if (type == FRAME_BYE && payload.length <= FRAME_MAX_PAYLOAD) {
                return false;
            }
            return false;
        }
        Stream stream;
        synchronized (lock) {
            stream = streams.get(streamId);
            if (stream == null) {
                return type == FRAME_DATA || type == FRAME_WINDOW || type == FRAME_CLOSE;
            }
        }
        if (type == FRAME_DATA) {
            if (payload.length == 0 || payload.length > DATA_CHUNK) {
                return false;
            }
            synchronized (lock) {
                if (stream.receiveWindow < payload.length) {
                    return false;
                }
                stream.receiveWindow -= payload.length;
            }
            try {
                OutputStream output = stream.socket.getOutputStream();
                output.write(payload);
                synchronized (lock) {
                    if (streams.get(stream.id) == stream) {
                        stream.receiveWindow += payload.length;
                        sendFrame(FRAME_WINDOW, stream.id, uint32(payload.length));
                    }
                }
            } catch (Exception e) {
                closeStream(stream, true);
            }
            return true;
        } else if (type == FRAME_WINDOW && payload.length == 4) {
            long amount = ByteBuffer.wrap(payload).getInt() & 0xffffffffL;
            if (amount == 0) {
                return false;
            }
            synchronized (lock) {
                if (stream.sendWindow > 0xffffffffL - amount) {
                    return false;
                }
                stream.sendWindow += amount;
                lock.notifyAll();
            }
            return true;
        } else if (type == FRAME_CLOSE && payload.length == 0) {
            closeStream(stream, false);
            return true;
        }
        return false;
    }

    private void closeStream(Stream stream, boolean notifyRelay) {
        boolean sendClose = false;
        synchronized (lock) {
            if (streams.get(stream.id) != stream) {
                return;
            }
            streams.remove(stream.id);
            sendClose = notifyRelay && carrierConnected && stream.opened;
            lock.notifyAll();
        }
        closeSocket(stream.socket);
        if (sendClose) {
            sendFrame(FRAME_CLOSE, stream.id, null);
        }
    }

    private void sendFrame(int type, int streamId, byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }
        byte[] frame = ByteBuffer.allocate(FRAME_HEADER + payload.length)
                .put((byte) type)
                .put((byte) (streamId >> 16))
                .put((byte) (streamId >> 8))
                .put((byte) streamId)
                .putInt(payload.length)
                .put(payload)
                .array();
        synchronized (lock) {
            if (stopped || outbound.size() >= MAX_OUTBOUND_ITEMS
                    || outboundBytes > MAX_OUTBOUND_BYTES - frame.length) {
                failCarrier();
                return;
            }
            outbound.add(frame);
            outboundBytes += frame.length;
        }
        MAIN.post(this::drainOutbound);
    }

    private void drainOutbound() {
        int remaining = 16;
        while (remaining-- > 0) {
            byte[] frame;
            JavaScriptReplyProxy target;
            synchronized (lock) {
                target = replyProxy;
                if (stopped || target == null || outbound.isEmpty()) {
                    return;
                }
                frame = outbound.removeFirst();
                outboundBytes -= frame.length;
            }
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) target.postMessage(frame);
                else { failCarrier(); return; }
            } catch (Exception e) {
                android.util.Log.w("WebProxyTransport", "WEB carrier operation failed.");
                failCarrier();
                return;
            }
        }
        MAIN.post(this::drainOutbound);
    }

    private void failCarrier() {
        ArrayList<Stream> close;
        synchronized (lock) {
            if (stopped || restartScheduled) {
                return;
            }
            restartScheduled = true;
            carrierConnected = false;
            replyProxy = null;
            outbound.clear();
            outboundBytes = 0;
            close = new ArrayList<>(streams.values());
            streams.clear();
            lock.notifyAll();
        }
        for (Stream stream : close) {
            closeSocket(stream.socket);
        }
        MAIN.post(() -> {
            destroyWebView();
            synchronized (lock) {
                if (stopped) {
                    return;
                }
            }
            stopInternal();
        });
    }

    private void destroyWebView() {
        WebView value = webView;
        webView = null;
        replyProxy = null;
        if (value != null) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.removeWebMessageListener(value, BRIDGE_OBJECT);
                }
                value.stopLoading();
                value.loadUrl("about:blank");
                value.removeAllViews();
                value.destroy();
            } catch (Exception e) {
                android.util.Log.w("WebProxyTransport", "WEB carrier operation failed.");
            }
        }
    }

    private static byte[] decodeSecret(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        byte[] result;
        if ((value.length() == 32 || value.length() == 34) && value.matches("[0-9a-fA-F]+")) {
            result = new byte[value.length() / 2];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
        } else {
            try {
                result = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }
        if (result.length == 16 || result.length == 17 && (result[0] & 0xff) == 0xdd) {
            return result;
        }
        return null;
    }

    private static String randomToken(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static byte[] uint32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignore) {
        }
    }

    private static final class Stream {
        private final int id;
        private final Socket socket;
        private long sendWindow = INITIAL_WINDOW;
        private long receiveWindow = INITIAL_WINDOW;
        private boolean opened;

        private Stream(int id, Socket socket) {
            this.id = id;
            this.socket = socket;
        }
    }

    private static final class Address {
        private final String host;
        private final String path;
        private final String value;

        private Address(String host, String path) {
            this.host = host;
            this.path = path;
            value = path.isEmpty() ? host : host + "/" + path;
        }
    }
}

