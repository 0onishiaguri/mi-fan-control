package com.fan.widget;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import java.util.List;

public class FanRefreshService extends Service {
    private static final String CHANNEL_ID = "fan_widget_service";
    private static final int NOTIFY_ID = 999;
    private static final String WAKE_LOCK_TAG = "FanWidget:RefreshWakeLock";
    private static final long SCREEN_OFF_INTERVAL = 3000; // 息屏后3秒刷新
    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_RUN_TIME = "fan_total_run_time";
    private static final String KEY_NOTIFICATION_TITLE = "notification_title";
    private static final String KEY_SCREEN_OFF_STOP = "screen_off_stop_fan";
    private static final int WATCHDOG_ALARM_REQUEST = 1001;
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L; // 15分钟

    // 优化：缓存SharedPreferences实例
    private SharedPreferences mSharedPrefs;

    private HandlerThread mHandlerThread;
    private Handler mBackgroundHandler;
    private Runnable mRefreshTask;
    private PowerManager.WakeLock mWakeLock;
    private long lastNotifyUpdateTime = 0;
    private long lastLoopTime = 0;

    // 线程安全：添加volatile
    private volatile boolean mIsScreenOn = true;
    private volatile boolean mWasStoppedByScreenOff = false;

    private BroadcastReceiver mScreenReceiver;
    private int zeroSpeedCount = 0;
    private static final int STABLE_ZERO_THRESHOLD = 2; // 连续2次零速才视为关闭

    // 应用自定义模式
    private String mLastForegroundPkg = "";
    private boolean mIsInAppMode = false;
    private int mLastAppMode = 0;

    // 看门狗计数器
    private int mLoopCount = 0;
    private int mZeroRpmWatchCount = 0;

    // 缓存PendingIntent
    private PendingIntent mPiOff, mPiFast, mPiMax, mPiContent;

    // 前台应用查询节流
    private int mAppCheckCounter = 0;
    private static final int APP_CHECK_INTERVAL = 3; // 每3次循环查询一次

    // 异常日志熔断
    private int mUsageStatsFailCount = 0;
    private static final int USAGE_STATS_FAIL_THRESHOLD = 5;
    private long mUsageStatsFuseUntil = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        LogRecorder.getInstance().info("ServiceEvent", "FanRefreshService 服务创建");
        FanUtil.init(this);

        mSharedPrefs = getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE);

        mHandlerThread = new HandlerThread("FanRefreshThread");
        mHandlerThread.start();
        mBackgroundHandler = new Handler(mHandlerThread.getLooper());

        FanUtil.totalRunTimeMs = mSharedPrefs.getLong(KEY_RUN_TIME, 0);

        acquireWakeLock();
        createNotifyChannel();
        initPendingIntents(); // 缓存PendingIntent
        startForeground(NOTIFY_ID, buildNotification("初始化", 0, 0, "散热风扇正在运行"));

        registerScreenReceiver();
        lastLoopTime = System.currentTimeMillis();
        startAutoRefreshLoop();
        startWatchDogAlarm();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        mWakeLock.setReferenceCounted(false);
    }

    private void initPendingIntents() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent offIntent = new Intent(this, FanWidget.class).setAction(FanWidget.ACTION_OFF);
        mPiOff = PendingIntent.getBroadcast(this, 1001, offIntent, flags);
        Intent fastIntent = new Intent(this, FanWidget.class).setAction(FanWidget.ACTION_FAST);
        mPiFast = PendingIntent.getBroadcast(this, 1002, fastIntent, flags);
        Intent maxIntent = new Intent(this, FanWidget.class).setAction(FanWidget.ACTION_ON);
        mPiMax = PendingIntent.getBroadcast(this, 1003, maxIntent, flags);
        Intent openAppIntent = new Intent(this, MainActivity.class);
        mPiContent = PendingIntent.getActivity(this, 0, openAppIntent, flags);
    }

    private void registerScreenReceiver() {
        mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    mIsScreenOn = true;
                    LogRecorder.getInstance().info("ServiceEvent", "系统广播：屏幕点亮");
                    handleScreenOn();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    mIsScreenOn = false;
                    LogRecorder.getInstance().info("ServiceEvent", "系统广播：屏幕熄灭");
                    handleScreenOff();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, filter);
    }

    private void handleScreenOff() {
        boolean enableStop = mSharedPrefs.getBoolean(KEY_SCREEN_OFF_STOP, false);
        if (!enableStop) return;
        if (FanUtil.sIsCharging) {
            LogRecorder.getInstance().info("ScreenEvent", "息屏，但正在充电，跳过息屏停转");
            return;
        }
        if (mWasStoppedByScreenOff) return;
        FanUtil.handleScreenOff();
        mWasStoppedByScreenOff = true;
    }

    private void handleScreenOn() {
        if (!mWasStoppedByScreenOff) return;
        FanUtil.handleScreenOn();
        mWasStoppedByScreenOff = false;
    }

    // ===== 优化：拆分大Runnable =====
    private void startAutoRefreshLoop() {
        mRefreshTask = new Runnable() {
            @Override
            public void run() {
                acquireWakeLockIfNeeded();
                try {
                    performRefresh();
                } catch (Exception e) {
                    LogRecorder.getInstance().error("ServiceLoop", "后台循环异常:" + e.getMessage());
                } finally {
                    releaseWakeLockIfHeld();
                    scheduleNextRefresh();
                }
            }
        };
        mBackgroundHandler.post(mRefreshTask);
    }

    private void acquireWakeLockIfNeeded() {
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            // 覆盖单轮刷新任务时长，任务结束后在 finally 中释放
            mWakeLock.acquire(1000);
        }
    }

    private void releaseWakeLockIfHeld() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try {
                mWakeLock.release();
            } catch (Exception ignored) {}
        }
    }

    private void scheduleNextRefresh() {
        long interval = mIsScreenOn ? RefreshPrefs.getWidgetInterval(this) : SCREEN_OFF_INTERVAL;
        mBackgroundHandler.postDelayed(mRefreshTask, interval);
    }

    private void performRefresh() {
        long now = System.currentTimeMillis();
        long delta = now - lastLoopTime;
        lastLoopTime = now;

        // 充电狂暴
        boolean enableChargeBoost = mSharedPrefs.getBoolean("charge_auto_boost", true);
        if (enableChargeBoost) {
            FanUtil.checkChargingAutoBoost(this);
        }

        int temp = FanUtil.getCurrentTemp();
        int rpm = FanUtil.getRealSpeed();
        String currentMode = FanUtil.getCurrentModeName(rpm);

        // 每10轮执行控制权看门狗
        mLoopCount++;
        if (mLoopCount % 10 == 0) {
            FanUtil.checkAndRegainControl();
        }

        // 模式控制（息屏停转时跳过）
        if (!mWasStoppedByScreenOff) {
            handleControlModes(temp);
        }

        // 高温停转自愈
        checkSelfHeal(temp, rpm);

        // 运行时长累计
        if (rpm > 0) {
            FanUtil.totalRunTimeMs += delta;
        }

        // 更新全局缓存
        FanUtil.cacheRpm = rpm;
        FanUtil.cacheTemp = temp;

        // 电池状态更新（使用优化方法）
        updateChargingState();

        // 通知与小组件更新
        updateNotificationsAndWidgets(rpm, temp, currentMode, now);
    }

    private void handleControlModes(int temp) {
        switch (FanUtil.currentControlMode) {
            case FanUtil.MODE_LOW_LEVEL:
                FanUtil.setFanTargetLevel(FanUtil.currentTargetLevel);
                break;
            case FanUtil.MODE_SMART:
                int targetPwm = FanUtil.calcPwmByTemp(temp);
                FanUtil.applySmoothPwm(targetPwm);
                break;
            case FanUtil.MODE_APP_CUSTOM:
                // 降低前台应用查询频率
                if (++mAppCheckCounter % APP_CHECK_INTERVAL == 0) {
                    checkAppCustomControl(temp);
                }
                break;
            default:
                break;
        }
    }

    private void checkSelfHeal(int temp, int rpm) {
        if (temp > 40 && rpm == 0
                && FanUtil.currentControlMode != FanUtil.MODE_SYSTEM
                && !mWasStoppedByScreenOff) {
            mZeroRpmWatchCount++;
            if (mZeroRpmWatchCount >= 15) {
                LogRecorder.getInstance().error("FanWatchdog", "温度升高但风扇停转，执行强制复位");
                FanUtil.switchControlMode(FanUtil.currentControlMode);
                mZeroRpmWatchCount = 0;
            }
        } else {
            mZeroRpmWatchCount = 0;
        }
    }

    private void updateChargingState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
                    FanUtil.sIsCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL);
                    return;
                }
            } catch (Exception ignored) {}
        }
        // 降级：使用广播
        try {
            Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                FanUtil.sIsCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Exception ignored) {}
    }

    // 通知强制刷新下限：内容无变化时最长 60 秒才重建一次，避免高频重建通知
    private static final long NOTIFY_FORCE_REFRESH_MS = 60 * 1000L;
    private int mLastNotifyRpm = -1;
    private int mLastNotifyTemp = -1;
    private String mLastNotifyMode = "";

    private void updateNotificationsAndWidgets(int rpm, int temp, String currentMode, long now) {
        // 判断是否真正运行（考虑阈值防止抖动）
        boolean currentRunning;
        if (rpm > 0) {
            zeroSpeedCount = 0;
            currentRunning = true;
        } else {
            zeroSpeedCount++;
            currentRunning = zeroSpeedCount >= STABLE_ZERO_THRESHOLD;
        }

        String customNotifyTitle = mSharedPrefs.getString(KEY_NOTIFICATION_TITLE, "散热风扇正在运行");
        String notifyTitle = currentRunning ? customNotifyTitle : "散热风扇已关闭";

        // 更新小组件
        refreshAllWidget(rpm, temp);

        // 更新通知：内容变化时按用户间隔刷新；内容不变时最长 60 秒刷新一次
        boolean contentChanged = rpm != mLastNotifyRpm || temp != mLastNotifyTemp || !currentMode.equals(mLastNotifyMode);
        mLastNotifyRpm = rpm;
        mLastNotifyTemp = temp;
        mLastNotifyMode = currentMode;

        long notifyInterval = RefreshPrefs.getNotifyInterval(this);
        if (now - lastNotifyUpdateTime >= notifyInterval
                && (contentChanged || now - lastNotifyUpdateTime >= NOTIFY_FORCE_REFRESH_MS)) {
            lastNotifyUpdateTime = now;
            Notification notify = buildNotification(currentMode, rpm, temp, notifyTitle);
            startForeground(NOTIFY_ID, notify);
        }
    }

    // ===== 应用自定义模式（优化后） =====
    private void checkAppCustomControl(int currentTemp) {
        String foregroundPkg = getForegroundPackageNameSafe();
        if (foregroundPkg == null || foregroundPkg.isEmpty()) return;

        if (foregroundPkg.equals(mLastForegroundPkg)) {
            // 如果已经在应用模式，则保持（无需重复设置）
            return;
        }

        mLastForegroundPkg = foregroundPkg;
        int appMode = mSharedPrefs.getInt("mode_" + foregroundPkg, 0);

        if (appMode > 0) {
            // 切换到预设档位
            if (!mIsInAppMode || mLastAppMode != appMode) {
                switch (appMode) {
                    case 1: FanUtil.setGearSilent(); break;
                    case 2: FanUtil.setGearFast(); break;
                    case 4: FanUtil.setGearMax(); break;
                    default: break;
                }
                LogRecorder.getInstance().info("AppCustom",
                        "检测到前台应用:" + foregroundPkg + "，切换到应用预设档位:" + appMode);
                mIsInAppMode = true;
                mLastAppMode = appMode;
            }
        } else {
            // 退出应用自定义模式，恢复智能曲线
            if (mIsInAppMode) {
                int targetPwm = FanUtil.calcPwmByTemp(currentTemp);
                FanUtil.setFanPwmDuty(targetPwm);
                LogRecorder.getInstance().info("AppCustom",
                        "退出应用自定义模式，恢复温控曲线，包名:" + foregroundPkg);
                mIsInAppMode = false;
                mLastAppMode = 0;
            }
        }
    }

    // 带熔断保护的前台包名获取
    private String getForegroundPackageNameSafe() {
        long now = System.currentTimeMillis();
        if (mUsageStatsFailCount >= USAGE_STATS_FAIL_THRESHOLD) {
            if (now < mUsageStatsFuseUntil) {
                // 熔断中，直接返回null
                return null;
            } else {
                mUsageStatsFailCount = 0; // 重置
            }
        }

        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long queryTime = now - 1000 * 10;
            List<UsageStats> statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, queryTime, now);
            if (statsList == null || statsList.isEmpty()) {
                mUsageStatsFailCount++;
                if (mUsageStatsFailCount >= USAGE_STATS_FAIL_THRESHOLD) {
                    mUsageStatsFuseUntil = now + 60000; // 熔断1分钟
                }
                return null;
            }
            UsageStats recent = null;
            for (UsageStats stats : statsList) {
                if (recent == null || stats.getLastTimeUsed() > recent.getLastTimeUsed()) {
                    recent = stats;
                }
            }
            mUsageStatsFailCount = 0; // 成功，重置计数
            return recent != null ? recent.getPackageName() : null;
        } catch (Exception e) {
            mUsageStatsFailCount++;
            if (mUsageStatsFailCount >= USAGE_STATS_FAIL_THRESHOLD) {
                mUsageStatsFuseUntil = now + 60000;
            }
            LogRecorder.getInstance().warn("AppCustom", "获取前台包名异常:" + e.getMessage());
            return null;
        }
    }

    // ===== 小组件刷新 =====
    private void refreshAllWidget(int rpm, int temp) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            ComponentName widgetComp = new ComponentName(this, FanWidget.class);
            int[] allWidgetIds = manager.getAppWidgetIds(widgetComp);
            for (int wid : allWidgetIds) {
                updateSingleWidgetUI(manager, wid, rpm, temp);
            }
        } catch (Exception ignored) {}
    }

    private void updateSingleWidgetUI(AppWidgetManager manager, int widgetId, int rpm, int temp) {
        RemoteViews v = new RemoteViews(getPackageName(), R.layout.widget_layout);
        String mode = FanUtil.getCurrentModeName(rpm);
        v.setTextViewText(R.id.tv_mode, "风扇模式：" + mode);
        v.setTextViewText(R.id.tv_rpm_num, String.valueOf(rpm));
        v.setTextViewText(R.id.tv_temp_num, temp + "°");

        v.setTextColor(R.id.tv_rpm_num, FanUtil.getRpmColor(rpm));
        v.setTextColor(R.id.tv_temp_num, FanUtil.getTempColor(temp));

        FanWidget.bindAllClickIntent(this, v);
        manager.updateAppWidget(widgetId, v);
    }

    // ===== 通知栏 =====
    private void createNotifyChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "风扇监控后台服务",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String mode, int rpm, int temp, String title) {
        String contentText = "模式：" + mode + " | 转速：" + rpm + " | 设备温度：" + temp + "°";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(contentText)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mPiContent)
                .addAction(R.drawable.ic_fan_off, "关闭", mPiOff)
                .addAction(R.drawable.ic_fan_fast, "高速", mPiFast)
                .addAction(R.drawable.ic_fan_max, "狂暴", mPiMax)
                .build();
    }

    // ===== 看门狗 =====
    private void startWatchDogAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(this, FanRefreshService.class);
            PendingIntent pi = PendingIntent.getService(
                    this, WATCHDOG_ALARM_REQUEST, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            // 使用非精确周期闹钟：兼容 Android 14+ 精确闹钟限制，降低无谓唤醒
            am.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                    WATCHDOG_INTERVAL_MS,
                    pi
            );
        } catch (Exception e) {
            LogRecorder.getInstance().warn("ServiceWatchdog", "看门狗闹钟启动失败:" + e.getMessage());
        }
    }

    private void stopWatchDogAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent intent = new Intent(this, FanRefreshService.class);
            PendingIntent pi = PendingIntent.getService(
                    this, WATCHDOG_ALARM_REQUEST, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            am.cancel(pi);
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopWatchDogAlarm();
        LogRecorder.getInstance().info("ServiceEvent", "FanRefreshService 服务销毁");
        super.onDestroy();
        mSharedPrefs.edit().putLong(KEY_RUN_TIME, FanUtil.totalRunTimeMs).apply();
        try {
            if (mScreenReceiver != null) unregisterReceiver(mScreenReceiver);
        } catch (Exception ignored) {}
        if (mBackgroundHandler != null && mRefreshTask != null) {
            mBackgroundHandler.removeCallbacks(mRefreshTask);
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}