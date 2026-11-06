package com.dynamitesun.kikitori;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private WebView web;

    /** Lets the page drive the background player. Methods run on a binder thread. */
    public class Bridge {
        @JavascriptInterface public void play(String json) {
            askNotificationPermission();
            Intent i = new Intent(MainActivity.this, PlayerService.class).setAction(PlayerService.A_PLAY).putExtra("json", json);
            start(i);
        }
        @JavascriptInterface public void pause() { send(PlayerService.A_PAUSE); }
        @JavascriptInterface public void resume() { send(PlayerService.A_RESUME); }
        @JavascriptInterface public void next() { send(PlayerService.A_NEXT); }
        @JavascriptInterface public void prev() { send(PlayerService.A_PREV); }
        @JavascriptInterface public void stop() { send(PlayerService.A_STOP); }
        @JavascriptInterface public String state() {
            PlayerService s = PlayerService.instance;
            return s == null ? "{}" : s.state();
        }
    }

    private void send(String action) {
        if (PlayerService.instance == null) return;
        start(new Intent(this, PlayerService.class).setAction(action));
    }

    private void start(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1));
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.addJavascriptInterface(new Bridge(), "KikitoriNative");
        setContentView(web);
        PlayerService.listener = json -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.nativeEvent&&nativeEvent(" + json + ")", null);
        });
        if (state != null) web.restoreState(state);
        else web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService s = PlayerService.instance;
        if (s != null && web != null) {
            String json = s.state();
            web.evaluateJavascript("window.nativeEvent&&nativeEvent(" + json + ")", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    protected void onPause() { super.onPause(); web.evaluateJavascript("try{TTS.stop()}catch(e){}", null); }

    @Override
    protected void onDestroy() {
        PlayerService.listener = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack?appBack():false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }
}
