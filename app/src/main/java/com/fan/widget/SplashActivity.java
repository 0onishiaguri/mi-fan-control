package com.fan.widget;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class SplashActivity extends AppCompatActivity {
    private static final String SP_NAME = "splash_pref";
    private static final String KEY_FIRST_LAUNCH = "first_launch";

    private WebView webView;
    private boolean isFirstLaunch = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        applyStatusBarTextColor();

        SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
        isFirstLaunch = sp.getBoolean(KEY_FIRST_LAUNCH, true);

        if (!isFirstLaunch) {
            startMainActivity();
            finish();
            return;
        }

        webView = findViewById(R.id.webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // 启用深色模式支持（Android 10+），但我们现在完全由前端控制，此设置可保留或去掉，保留无影响
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_AUTO);
        }

        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new MiFanInterface(), "MiFanInterface");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("mifan://start")) {
                    onStartApp();
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("mifan://start")) {
                    onStartApp();
                    return true;
                }
                return false;
            }

            // 页面加载完成后，主动设置一次当前主题
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                WebViewThemeUtil.updateTheme(webView, SplashActivity.this);
            }
        });

        webView.loadUrl("file:///android_asset/启动欢迎页面.html");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 仅当首次启动且 WebView 存在时，动态更新主题（不重载页面）
        if (isFirstLaunch && webView != null) {
            WebViewThemeUtil.updateTheme(webView, this);
        }
    }

    private void onStartApp() {
        getSharedPreferences(SP_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FIRST_LAUNCH, false)
                .apply();
        startMainActivity();
        finish();
    }

    private class MiFanInterface {
        @JavascriptInterface
        public void startMainActivity() {
            onStartApp();
        }
    }

    private void applyStatusBarTextColor() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                controller.setAppearanceLightStatusBars(!WebViewThemeUtil.isDarkTheme(this));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = window.getDecorView().getSystemUiVisibility();
            if (!WebViewThemeUtil.isDarkTheme(this)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        // 释放 WebView，防止持有 Activity 引用造成内存泄漏
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}