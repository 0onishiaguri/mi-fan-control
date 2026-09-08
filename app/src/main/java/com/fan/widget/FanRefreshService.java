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
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import java.util.List;

public class FanRefreshService extends Service {
    // 通知优先级三渠道（低/默认/高），切换渠道 id 即切换重要性，互不影响
    private static final String CHANNEL_ID_LOW = "fan_widget_low";
    private static final String CHANNEL_ID_DEFAULT = "fan_widget_default";
    private static final String CHANNEL_ID_HIGH = "fan_widget_high";
    public static final String ACTION_REFRESH_NOW = "com.fan.widget.ACTION_REFRESH_NOW";
    private static final String KEY_NOTIFY_PRIORITY = "notify_priority"; // 0低 1默认 2高
    private static final String KEY_NOTIFY_CUSTOM = "notify_custom_data"; // 通知栏数据自定义开关
    // 通知排序时间戳策略：服务启动时锁定一次，之后每 2 小时刷新一次。
    // 效果：通知刷新时 when 不变 → 排序位置不随刷新跳动（不与其他常驻通知抢位）；
    // 且 when 始终是近期时间 → 不会被系统当作"旧通知"收纳隐藏（固定 2023 年时间戳会触发收纳导致通知消失）。
    private static final long NOTIFY_WHEN_REFRESH_MS = 2 * 3600 * 1000L;
    private long mNotifyWhen = 0L;
    private long mNotifyWhenLockedAt = 0L;
    private static final String KEY_NOTIFY_POS_LEFT = "notify_pos_left";
    private static final String KEY_NOTIFY_POS_MID = "notify_pos_mid";
    private static final String KEY_NOTIFY_POS_RIGHT = "notify_pos_right";
    // 通知重建阈值（B 方案：数据变化达到阈值才重建，降低刷新频率减少与常驻通知抢位）
    private static final int NOTIFY_RPM_THRESHOLD = 200;
    private static final int NOTIFY_TEMP_THRESHOLD = 1;
    private static final int NOTIFY_PWM_THRESHOLD = 5;
    private static final float NOTIFY_POWER_THRESHOLD = 0.5f;
    private static final int NOTIFY_ID = 999;
    private static final String WAKE_LOCK_TAG = "FanWidget:RefreshWakeLock";
    private static final long SCREEN_OFF_INTERVAL = 3000; // 息屏后3秒刷新
    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_RUN_TIME = "fan_total_run_time";
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

    // 应用自定义模式
    private String mLastForegroundPkg = "";
    private boolean mIsInAppMode = false;
    private int mLastAppMode = 0;

    // 看门狗计数器
    private int mLoopCount = 0;
    private int mZeroRpmWatchCount = 0;

    // 缓存PendingIntent
    private PendingIntent mPiOff, mPiFast, mPiMax, mPiFloat, mPiContent;

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
        // 锁定排序时间戳初始值（近期时间）
        getNotifyWhen();
        // 防御：通知构建异常不能导致前台服务崩溃（否则风扇控制也会一起停）
        try {
            startForeground(NOTIFY_ID, buildNotification("初始化", 0, 0, "--", 0));
        } catch (Exception e) {
            LogRecorder.getInstance().error("ServiceEvent", "初始化通知失败:" + e.getMessage());
        }

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
        Intent floatIntent = new Intent(this, FanWidget.class).setAction(FanWidget.ACTION_FLOAT_TOGGLE);
        mPiFloat = PendingIntent.getBroadcast(this, 1004, floatIntent, flags);
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
        long interval = mIsScreenOn ? getCollectInterval() : SCREEN_OFF_INTERVAL;
        mBackgroundHandler.postDelayed(mRefreshTask, interval);
    }

    // 采集循环频率 = 通知/小组件/悬浮窗自定义频率中的最小值，保证任意 UI 都能拿到新鲜数据；
    // 各 UI 自身按各自频率独立刷新，互不影响。
    private long getCollectInterval() {
        long widget = RefreshPrefs.getWidgetInterval(this);
        long notify = RefreshPrefs.getNotifyInterval(this);
        long interval = Math.min(widget, notify);
        if (FloatWindowService.isEnabled(this)) {
            interval = Math.min(interval, FloatWindowService.getInterval(this));
        }
        return Math.max(interval, 500L); // 下限 0.5s，避免 su 频率过高
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

        // 后台线程预热 PWM 缓存（1 秒节流），供主线程/悬浮窗无阻塞读取
        FanUtil.readPwmDuty();
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

    // 通知强制刷新下限：内容无变化时最长 120 秒才重建一次，避免高频重建通知
    private static final long NOTIFY_FORCE_REFRESH_MS = 120 * 1000L;
    private int mLastNotifyRpm = -1;
    private int mLastNotifyTemp = -1;
    private String mLastNotifyMode = "";
    private int mLastNotifyPwm = -1;
    private float mLastNotifyPower = Float.NaN;

    private long lastWidgetUpdateTime = 0;

    private void updateNotificationsAndWidgets(int rpm, int temp, String currentMode, long now) {
        // 小组件：按自定义刷新频率独立节流，与通知/悬浮窗互不影响
        long widgetInterval = RefreshPrefs.getWidgetInterval(this);
        if (now - lastWidgetUpdateTime >= widgetInterval) {
            lastWidgetUpdateTime = now;
            refreshAllWidget(rpm, temp);
        }

        // 通知：按自定义刷新频率独立节流；内容达到阈值变化（模式变/转速±200/温度±1/PWM±5/功耗±0.5W）才重建，
        // 无变化时最长 120 秒兜底刷新一次，显著减少重建次数，避免与常驻通知抢位置
        String powerText = FanUtil.readPowerText(this);
        int pwm = FanUtil.getCachedPwmDuty();
        float powerW = FanUtil.readPowerWatts(this);
        boolean contentChanged = !currentMode.equals(mLastNotifyMode)
                || Math.abs(rpm - mLastNotifyRpm) >= NOTIFY_RPM_THRESHOLD
                || Math.abs(temp - mLastNotifyTemp) >= NOTIFY_TEMP_THRESHOLD
                || Math.abs(pwm - mLastNotifyPwm) >= NOTIFY_PWM_THRESHOLD
                || Math.abs(powerW - mLastNotifyPower) >= NOTIFY_POWER_THRESHOLD;
        mLastNotifyRpm = rpm;
        mLastNotifyTemp = temp;
        mLastNotifyMode = currentMode;
        mLastNotifyPwm = pwm;
        mLastNotifyPower = powerW;

        long notifyInterval = RefreshPrefs.getNotifyInterval(this);
        if (now - lastNotifyUpdateTime >= notifyInterval
                && (contentChanged || now - lastNotifyUpdateTime >= NOTIFY_FORCE_REFRESH_MS)) {
            lastNotifyUpdateTime = now;
            Notification notify = buildNotification(currentMode, rpm, temp, powerText, pwm);
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
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel low = new NotificationChannel(
                    CHANNEL_ID_LOW, "风扇监控（低优先级）", NotificationManager.IMPORTANCE_LOW);
            low.setShowBadge(false);
            nm.createNotificationChannel(low);
            NotificationChannel def = new NotificationChannel(
                    CHANNEL_ID_DEFAULT, "风扇监控（默认）", NotificationManager.IMPORTANCE_DEFAULT);
            def.setShowBadge(false);
            nm.createNotificationChannel(def);
            NotificationChannel high = new NotificationChannel(
                    CHANNEL_ID_HIGH, "风扇监控（高优先级）", NotificationManager.IMPORTANCE_HIGH);
            high.setShowBadge(false);
            nm.createNotificationChannel(high);
        }
    }

    private String getNotifyChannelId() {
        int p = mSharedPrefs.getInt(KEY_NOTIFY_PRIORITY, 1);
        return p <= 0 ? CHANNEL_ID_LOW : (p >= 2 ? CHANNEL_ID_HIGH : CHANNEL_ID_DEFAULT);
    }

    // 通知栏左/中/右三位置的数据槽位 id（折叠/展开布局共用同一套 id）
    private static final int[][] NOTIFY_SLOT_LABELS = {
            {R.id.lt_lb_mode, R.id.lt_lb_speed, R.id.lt_lb_temp, R.id.lt_lb_power, R.id.lt_lb_pwm},
            {R.id.mt_lb_mode, R.id.mt_lb_speed, R.id.mt_lb_temp, R.id.mt_lb_power, R.id.mt_lb_pwm},
            {R.id.rt_lb_mode, R.id.rt_lb_speed, R.id.rt_lb_temp, R.id.rt_lb_power, R.id.rt_lb_pwm},
    };
    private static final int[][] NOTIFY_SLOT_VALUES = {
            {R.id.lt_v_mode, R.id.lt_v_speed, R.id.lt_v_temp, R.id.lt_v_power, R.id.lt_v_pwm},
            {R.id.mt_v_mode, R.id.mt_v_speed, R.id.mt_v_temp, R.id.mt_v_power, R.id.mt_v_pwm},
            {R.id.rt_v_mode, R.id.rt_v_speed, R.id.rt_v_temp, R.id.rt_v_power, R.id.rt_v_pwm},
    };

    // 数据池顺序：0档位 1转速 2温度 3功耗 4PWM
    private void fillNotifySlots(RemoteViews views, String[] values, int[] selection) {
        for (int slot = 0; slot < 3; slot++) {
            int sel = selection[slot];
            for (int i = 0; i < 5; i++) {
                boolean show = (i == sel);
                views.setViewVisibility(NOTIFY_SLOT_LABELS[slot][i], show ? View.VISIBLE : View.GONE);
                views.setViewVisibility(NOTIFY_SLOT_VALUES[slot][i], show ? View.VISIBLE : View.GONE);
                if (show) {
                    views.setTextViewText(NOTIFY_SLOT_VALUES[slot][i], values[i]);
                }
            }
        }
    }

    private Notification buildNotification(String mode, int rpm, int temp, String powerText, int pwm) {
        // 数据池：0档位 1转速 2温度 3功耗 4PWM
        String[] values = {
                mode,
                String.valueOf(rpm),
                temp + "°",
                powerText,
                pwm + "%"
        };
        boolean customData = mSharedPrefs.getBoolean(KEY_NOTIFY_CUSTOM, false);
        // 默认档位+转速+温度；开启自定义后才读取用户配置
        int left = customData ? clampPos(mSharedPrefs.getInt(KEY_NOTIFY_POS_LEFT, 0)) : 0;
        int mid = customData ? clampPos(mSharedPrefs.getInt(KEY_NOTIFY_POS_MID, 1)) : 1;
        int right = customData ? clampPos(mSharedPrefs.getInt(KEY_NOTIFY_POS_RIGHT, 2)) : 2;
        int[] selection = {left, mid, right};

        // 折叠视图：三位置数据（布局内候选组自带名称，运行时只显示所选组）
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_fan_compact);
        fillNotifySlots(views, values, selection);
        // 展开视图：三位置数据 + 四个功能按钮
        RemoteViews expanded = new RemoteViews(getPackageName(), R.layout.notification_fan_expanded);
        fillNotifySlots(expanded, values, selection);
        expanded.setOnClickPendingIntent(R.id.btn_close, mPiOff);
        expanded.setOnClickPendingIntent(R.id.btn_high, mPiFast);
        expanded.setOnClickPendingIntent(R.id.btn_boost, mPiMax);
        expanded.setOnClickPendingIntent(R.id.btn_float, mPiFloat);

        String channelId = getNotifyChannelId();
        int priority = NotificationCompat.PRIORITY_DEFAULT;
        int p = mSharedPrefs.getInt(KEY_NOTIFY_PRIORITY, 1);
        if (p <= 0) priority = NotificationCompat.PRIORITY_LOW;
        else if (p >= 2) priority = NotificationCompat.PRIORITY_HIGH;

        // 诊断：渠道被系统关闭时记录日志（应用通知权限正常但渠道级被关，通知不会显示）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = getSystemService(NotificationManager.class)
                        .getNotificationChannel(channelId);
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    LogRecorder.getInstance().warn("ServiceEvent",
                            "通知渠道[" + channelId + "]被系统关闭，通知不显示。请在系统设置中开启该渠道");
                }
            }
        } catch (Exception ignored) {}

        return new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setCustomContentView(views)
                .setCustomBigContentView(expanded)
                .setOngoing(true)
                .setShowWhen(false)
                .setWhen(getNotifyWhen())
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(priority)
                .setContentIntent(mPiContent)
                .build();
    }

    // 锁定排序时间戳：启动锁定，每 2 小时刷新一次，始终为近期时间
    private long getNotifyWhen() {
        long now = System.currentTimeMillis();
        if (mNotifyWhen == 0L || now - mNotifyWhenLockedAt >= NOTIFY_WHEN_REFRESH_MS) {
            mNotifyWhen = now;
            mNotifyWhenLockedAt = now;
        }
        return mNotifyWhen;
    }

    private int clampPos(int v) {
        return Math.max(0, Math.min(4, v));
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
        // 设置页修改通知数据/优先级后，立即触发一轮刷新让通知生效
        if (intent != null && ACTION_REFRESH_NOW.equals(intent.getAction())) {
            if (mBackgroundHandler != null && mRefreshTask != null) {
                mBackgroundHandler.removeCallbacks(mRefreshTask);
                mBackgroundHandler.post(mRefreshTask);
            }
        }
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