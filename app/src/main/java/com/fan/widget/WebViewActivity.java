package com.fan.widget;

import android.content.res.Configuration;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class WebViewActivity extends BaseActivity { // 改为继承 BaseActivity
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 调用父类 onCreate，自动处理沉浸式状态栏和自定义背景
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        // 移除原来的状态栏代码，利用 BaseActivity 的设置

        webView = findViewById(R.id.webview);
        // 设置为透明，这样可以透出 BaseActivity 加载的自定义背景图
        webView.setBackgroundColor(0x00000000);
        // 沉浸式：网页延伸到状态栏后方，顶部由 HTML 自身 padding 避让（感谢名单/用户协议已适配）

        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完成后，主动设置一次当前主题
                WebViewThemeUtil.updateTheme(webView, WebViewActivity.this);
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url != null) {
            webView.loadUrl(url);
        } else {
            webView.loadUrl("file:///android_asset/感谢名单.html");
        }
    }

    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 深浅色模式切换时，动态更新主题，不重载页面
        if (webView != null) {
            WebViewThemeUtil.updateTheme(webView, this);
        }
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