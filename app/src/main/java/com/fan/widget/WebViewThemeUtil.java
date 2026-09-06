package com.fan.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.webkit.WebView;

/**
 * WebView 深浅色主题同步工具（SplashActivity / WebViewActivity 共用）
 */
public class WebViewThemeUtil {

    private WebViewThemeUtil() {}

    public static boolean isDarkTheme(Context context) {
        int nightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void updateTheme(WebView webView, Context context) {
        if (webView == null) return;
        boolean isDark = isDarkTheme(context);
        webView.evaluateJavascript("window.updateTheme(" + isDark + ");", null);
    }
}
