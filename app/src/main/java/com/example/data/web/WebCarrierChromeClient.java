package com.example.data.web;

import android.net.Uri;
import android.os.Message;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

final class WebCarrierChromeClient extends WebChromeClient {
    @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
    @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        callback.invoke(origin, false, false);
    }
    @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) { return false; }
    @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
        callback.onReceiveValue(null); return true;
    }
    @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
    @Override public boolean onJsConfirm(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
    @Override public boolean onJsPrompt(WebView v, String u, String m, String d, JsPromptResult r) { r.cancel(); return true; }
}
