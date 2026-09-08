package com.fan.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FanWidget extends AppWidgetProvider {

    public static final String ACTION_OFF = "FAN_OFF";
    public static final String ACTION_SILENT = "FAN_SILENT";
    public static final String ACTION_FAST = "FAN_FAST";
    public static final String ACTION_ON = "FAN_ON";
    public static final String ACTION_FLOAT_TOGGLE = "FAN_FLOAT_TOGGLE";

    // PendingIntent request codes
    private static final int REQUEST_OFF = 5001;
    private static final int REQUEST_SILENT = 5002;
    private static final int REQUEST_FAST = 5003;
    private static final int REQUEST_ON = 5004;
    private static final int REQUEST_OPEN_APP = 5005; // 新增
    private static final int REQUEST_FLOAT = 5006;     // 悬浮窗开关

    // 单线程池处理点击任务，避免阻塞广播
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // 启动后台刷新服务（确保服务运行）
        Intent serviceIntent = new Intent(context, FanRefreshService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        // 初始化所有小组件
        for (int id : appWidgetIds) {
            initWidgetUI(context, appWidgetManager, id);
        }
    }

    private static void initWidgetUI(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        v.setTextViewText(R.id.tv_mode, "等待数据");
        v.setTextViewText(R.id.tv_rpm_num, "0");
        v.setTextViewText(R.id.tv_temp_num, "0°");
        bindAllClickIntent(context, v);
        manager.updateAppWidget(widgetId, v);
    }

    // 绑定所有按钮点击事件（供服务端更新UI时调用）
    public static void bindAllClickIntent(Context ctx, RemoteViews v) {
        // 关闭
        Intent offIntent = new Intent(ctx, FanWidget.class).setAction(ACTION_OFF);
        PendingIntent pOff = PendingIntent.getBroadcast(ctx, REQUEST_OFF, offIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.btn_off, pOff);

        // 静谧
        Intent silentIntent = new Intent(ctx, FanWidget.class).setAction(ACTION_SILENT);
        PendingIntent pSilent = PendingIntent.getBroadcast(ctx, REQUEST_SILENT, silentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.btn_silent, pSilent);

        // 高速
        Intent fastIntent = new Intent(ctx, FanWidget.class).setAction(ACTION_FAST);
        PendingIntent pFast = PendingIntent.getBroadcast(ctx, REQUEST_FAST, fastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.btn_fast, pFast);

        // 狂暴
        Intent onIntent = new Intent(ctx, FanWidget.class).setAction(ACTION_ON);
        PendingIntent pOn = PendingIntent.getBroadcast(ctx, REQUEST_ON, onIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.btn_on, pOn);

        // 点击小组件整体打开主界面（根布局点击）
        Intent openAppIntent = new Intent(ctx, MainActivity.class);
        openAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pOpenApp = PendingIntent.getActivity(ctx, REQUEST_OPEN_APP, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // 注意：需要在 widget_layout.xml 中为根布局添加 android:id="@+id/widget_root"
        v.setOnClickPendingIntent(R.id.widget_root, pOpenApp);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (action == null) return;

        // 忽略配置变化广播（系统会自动刷新）
        if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
            return;
        }

        // 处理悬浮窗开关（通知栏按钮）
        if (ACTION_FLOAT_TOGGLE.equals(action)) {
            handleFloatToggle(context);
            return;
        }

        // 处理风扇控制按钮
        if (ACTION_OFF.equals(action) || ACTION_SILENT.equals(action)
                || ACTION_FAST.equals(action) || ACTION_ON.equals(action)) {
            final PendingResult pendingResult = goAsync();
            EXECUTOR.execute(() -> {
                String toastMsg = "";
                try {
                    if (ACTION_OFF.equals(action)) {
                        FanUtil.fanStop();
                        toastMsg = "已关闭散热风扇";
                    } else if (ACTION_SILENT.equals(action)) {
                        FanUtil.fanSilent();
                        toastMsg = "已切换静谧模式";
                    } else if (ACTION_FAST.equals(action)) {
                        FanUtil.fanFast();
                        toastMsg = "已切换高速模式";
                    } else if (ACTION_ON.equals(action)) {
                        FanUtil.fanMax();
                        toastMsg = "已开启狂暴模式";
                    }
                } catch (Exception e) {
                    toastMsg = "操作失败，请检查权限";
                    LogRecorder.getInstance().error("FanWidget", "点击按钮异常: " + e.getMessage());
                } finally {
                    final String finalMsg = toastMsg;
                    MAIN_HANDLER.post(() -> {
                        Toast.makeText(context, finalMsg, Toast.LENGTH_SHORT).show();
                        pendingResult.finish();
                    });
                }
            });
        }
    }

    // 通知栏悬浮窗按钮：切换开关并与个性化设置同步
    private void handleFloatToggle(Context context) {
        final PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            String msg = "";
            try {
                boolean nowEnabled = !FloatWindowService.isEnabled(context);
                if (nowEnabled && !Settings.canDrawOverlays(context)) {
                    Intent pi = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.getPackageName()));
                    pi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(pi);
                    msg = "请先授予悬浮窗权限";
                } else {
                    FloatWindowService.setEnabled(context, nowEnabled);
                    if (nowEnabled) {
                        context.startService(new Intent(context, FloatWindowService.class));
                        msg = "信息悬浮窗已开启";
                    } else {
                        context.stopService(new Intent(context, FloatWindowService.class));
                        msg = "信息悬浮窗已关闭";
                    }
                }
            } catch (Exception e) {
                msg = "操作失败，请检查权限";
                LogRecorder.getInstance().error("FanWidget", "悬浮窗切换异常: " + e.getMessage());
            } finally {
                final String finalMsg = msg;
                MAIN_HANDLER.post(() -> {
                    Toast.makeText(context, finalMsg, Toast.LENGTH_SHORT).show();
                    pendingResult.finish();
                });
            }
        });
    }

    @Override
    public void onDisabled(Context context) {
        // 所有小组件被移除时停止后台服务
        context.stopService(new Intent(context, FanRefreshService.class));
        super.onDisabled(context);
    }
}