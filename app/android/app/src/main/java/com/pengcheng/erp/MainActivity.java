package com.pengcheng.erp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.community.barcodescanner.BarcodeScanner;

public class MainActivity extends BridgeActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全局异常捕获: 任何未处理的 RuntimeException 都能在这里看到 logcat
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e("UNCAUGHT", "Thread " + thread.getName() + " crashed", ex);
                // 交给系统默认处理器 (会弹"应用已停止运行")
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(thread, ex);
            }
        });

        // Register plugins before super.onCreate
        registerPlugin(BarcodeScanner.class);
        registerPlugin(NativeScannerPlugin.class);
        registerPlugin(NativeSharePlugin.class);
        super.onCreate(savedInstanceState);

        // Fix: HTTPS page + HTTP API → Android WebView blocks mixed content by default.
        // Allow cleartext traffic for the domain used by this ERP.
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            settings.setAllowUniversalAccessFromFileURLs(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
    }
}
