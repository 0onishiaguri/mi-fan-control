package com.fan.widget;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PermissionDetailActivity extends BaseActivity {

    private static final String TAG = "PermissionDetail";

    private TextView tvRootStatus, tvAutostartStatus, tvBatteryStatus,
            tvNotificationStatus, tvAccessibilityStatus, tvUsageStatsStatus,
            tvOverlayStatus;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Future<?> mCheckTask;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private static int sOpRunInBackground = -1;
    private static Method sCheckOpNoThrowMethod;
    private static final int[] AUTO_START_OP_CODES = {10008, 10009, 10010, 10011};

    static {
        try {
            Field opRunBgField = AppOpsManager.class.getDeclaredField("OP_RUN_IN_BACKGROUND");
            opRunBgField.setAccessible(true);
            sOpRunInBackground = opRunBgField.getInt(null);
        } catch (Exception ignored) {}

        try {
            sCheckOpNoThrowMethod = AppOpsManager.class.getDeclaredMethod("checkOpNoThrow", int.class, int.class, String.class);
            sCheckOpNoThrowMethod.setAccessible(true);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_permission_detail);

        tvRootStatus = findViewById(R.id.tv_root_status);
        tvAutostartStatus = findViewById(R.id.tv_autostart_status);
        tvBatteryStatus = findViewById(R.id.tv_battery_status);
        tvNotificationStatus = findViewById(R.id.tv_notification_status);
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status);
        tvUsageStatsStatus = findViewById(R.id.tv_usage_stats_status);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);

        findViewById(R.id.item_root).setOnClickListener(v -> openRootSettings());
        findViewById(R.id.item_autostart).setOnClickListener(v -> openAutostartSettings());
        findViewById(R.id.item_battery).setOnClickListener(v -> openBatterySettings());
        findViewById(R.id.item_notification).setOnClickListener(v -> openNotificationSettings());
        findViewById(R.id.item_accessibility).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.item_usage_stats).setOnClickListener(v -> openUsageStatsSettings());
        findViewById(R.id.item_overlay).setOnClickListener(v -> openOverlaySettings());

        // 独立页面，不需要隐藏底部导航（MainActivity 的导航与本 Activity 无关）

        checkAllPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMainHandler.postDelayed(this::checkAllPermissions, 800);
    }

    @Override
    protected void onDestroy() {
        if (mCheckTask != null && !mCheckTask.isDone()) mCheckTask.cancel(true);
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void checkAllPermissions() {
        if (mCheckTask != null && !mCheckTask.isDone()) mCheckTask.cancel(true);
        mCheckTask = mExecutor.submit(() -> {
            final boolean root = checkRootPermission();
            final boolean autoStart = checkAutoStartPermission();
            final boolean battery = checkAppOpsPermission(sOpRunInBackground);
            final boolean notification = checkNotificationPermission();
            final boolean accessibility = checkAccessibilityPermission();
            final boolean usageStats = checkUsageStatsPermission();
            final boolean overlay = checkOverlayPermission();

            mMainHandler.post(() -> {
                updateStatusText(tvRootStatus, root);
                updateStatusText(tvAutostartStatus, autoStart);
                updateStatusText(tvBatteryStatus, battery);
                updateStatusText(tvNotificationStatus, notification);
                updateStatusText(tvAccessibilityStatus, accessibility);
                updateStatusText(tvUsageStatsStatus, usageStats);
                updateStatusText(tvOverlayStatus, overlay);
            });
        });
    }

    private boolean checkAppOpsPermission(int opCode) {
        if (opCode == -1) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int uid = getApplicationInfo().uid;
            String pkg = getPackageName();
            if (sCheckOpNoThrowMethod != null) {
                int mode = (int) sCheckOpNoThrowMethod.invoke(appOps, opCode, uid, pkg);
                return mode == AppOpsManager.MODE_ALLOWED;
            }
        } catch (Exception e) {
            LogRecorder.getInstance().warn(TAG, "AppOps 检查失败, op=" + opCode);
        }
        return false;
    }

    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
    private boolean checkAutoStartPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int uid = getApplicationInfo().uid;
        String pkg = getPackageName();
        for (int opCode : AUTO_START_OP_CODES) {
            try {
                if (sCheckOpNoThrowMethod == null) return false;
                int mode = (int) sCheckOpNoThrowMethod.invoke(appOps, opCode, uid, pkg);
                LogRecorder.getInstance().info(TAG, "自启动检测 opCode=" + opCode + ", mode=" + mode);
                if (mode == AppOpsManager.MODE_ALLOWED) return true;
            } catch (Exception e) {
                LogRecorder.getInstance().warn(TAG, "自启动检测 opCode=" + opCode + " 异常: " + e.getMessage());
            }
        }
        return false;
    }

    private boolean checkRootPermission() {
        // 复用 FanUtil 统一 SU 执行入口：输出非空即代表 root 可用
        String result = FanUtil.runSuCommand("echo root_check");
        return result != null && !result.isEmpty();
    }

    private boolean checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private boolean checkAccessibilityPermission() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled == 1) {
                String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (services != null) return services.toLowerCase().contains(getPackageName().toLowerCase());
            }
        } catch (Settings.SettingNotFoundException ignored) {}
        return false;
    }

    private boolean checkUsageStatsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return true;
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 1000 * 10, now);
            return stats != null && !stats.isEmpty();
        } catch (Exception e) { return false; }
    }

    private void updateStatusText(TextView tv, boolean granted) {
        if (granted) { tv.setText("已授予"); tv.setTextColor(Color.parseColor("#10B981")); }
        else { tv.setText("未授予"); tv.setTextColor(Color.parseColor("#F44336")); }
    }

    // ========== 设置页面跳转 ==========
    private void openRootSettings() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.topjohnwu.magisk");
            if (intent != null) { startActivity(intent); return; }
        } catch (Exception ignored) {}
        Toast.makeText(this, "请安装 Magisk 或手动授予 Root 权限", Toast.LENGTH_SHORT).show();
    }

    private void openAutostartSettings() {
        try {
            ComponentName component = new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity");
            Intent intent = new Intent();
            intent.setComponent(component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) { openAppDetailsSettings(); }
    }

    private void openBatterySettings() {
        try {
            Intent miPowerIntent = new Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST");
            miPowerIntent.putExtra("package_name", getPackageName());
            if (miPowerIntent.resolveActivity(getPackageManager()) != null) { startActivity(miPowerIntent); return; }
        } catch (Exception ignored) {}
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) { openAppDetailsSettings(); }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    private void openAccessibilitySettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (Exception e) { openAppDetailsSettings(); }
    }

    private void openUsageStatsSettings() {
        try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        catch (Exception e) { openAppDetailsSettings(); }
    }

    // ========== 悬浮窗权限 ==========
    private boolean checkOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            openAppDetailsSettings();
        }
    }

    private void openAppDetailsSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }
}