package com.fan.widget;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

public class RefreshPrefs {
    private static final String SP_NAME = "fan_refresh_config";
    public static final String KEY_WIDGET_INTERVAL = "widget_refresh_ms";
    public static final String KEY_NOTIFY_INTERVAL = "notify_refresh_ms";

    public static final long DEFAULT_WIDGET = 2000;
    public static final long DEFAULT_NOTIFY = 2000;

    // 间隔保存回调（供 UI 层复用监听逻辑）
    public interface IntervalSaver {
        void save(Context ctx, long ms);
    }

    // 时间选项映射（保持插入顺序）
    private static final Map<String, Long> TIME_OPTIONS = new LinkedHashMap<>();
    static {
        TIME_OPTIONS.put("0.5秒", 500L);
        TIME_OPTIONS.put("1秒", 1000L);
        TIME_OPTIONS.put("1.5秒", 1500L);
        TIME_OPTIONS.put("2秒", 2000L);
    }

    private static SharedPreferences getSp(Context context) {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    // ========== 小组件间隔 ==========
    public static void setWidgetInterval(Context ctx, long ms) {
        getSp(ctx).edit().putLong(KEY_WIDGET_INTERVAL, ms).apply();
    }

    public static long getWidgetInterval(Context ctx) {
        return getSp(ctx).getLong(KEY_WIDGET_INTERVAL, DEFAULT_WIDGET);
    }

    // ========== 通知间隔 ==========
    public static void setNotifyInterval(Context ctx, long ms) {
        getSp(ctx).edit().putLong(KEY_NOTIFY_INTERVAL, ms).apply();
    }

    public static long getNotifyInterval(Context ctx) {
        return getSp(ctx).getLong(KEY_NOTIFY_INTERVAL, DEFAULT_NOTIFY);
    }

    // ========== 字符串 ↔ 毫秒转换（基于 Map） ==========
    public static long timeStrToMs(String timeStr) {
        Long ms = TIME_OPTIONS.get(timeStr);
        return ms != null ? ms : 1000; // 默认1秒
    }

    public static String msToTimeStr(long ms) {
        for (Map.Entry<String, Long> entry : TIME_OPTIONS.entrySet()) {
            if (entry.getValue() == ms) {
                return entry.getKey();
            }
        }
        return "1秒"; // 默认值
    }
}