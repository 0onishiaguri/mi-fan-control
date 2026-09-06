# MiFan Control ProGuard Rules
# Release 构建当前未启用混淆，此文件保留用于后续启用。

# 保持 FanUtil 等核心类不被混淆（Root 反射调用）
-keep class com.fan.widget.FanUtil { *; }
-keep class com.fan.widget.FanSpeedSimulator { *; }

# MIUIX Compose 组件
-keep class top.yukonga.miuix.kmp.** { *; }

# ===== MiFan Control R8 保留规则 =====
# WebView JS 接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 应用组件（AndroidManifest 注册，显式保留避免混淆）
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.appwidget.AppWidgetProvider

# 自定义 View（布局 inflate 依赖无参/双参构造）
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# BuildConfig 自定义字段
-keep class com.fan.widget.BuildConfig { *; }
