package com.fan.widget;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class AdvancedSettingsActivity extends BaseActivity {

    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_CHARGE_BOOST = "charge_auto_boost";
    private static final String KEY_HIDE_RECENTS = "exclude_from_recents";
    private static final String KEY_SCREEN_OFF_STOP = "screen_off_stop_fan";
    // ========== 悬浮窗配置 ==========
    private static final String KEY_FLOAT_ENABLED = "float_window_enabled";
    private static final String KEY_FLOAT_ALPHA = "float_window_alpha";
    private static final String KEY_FLOAT_SCALE = "float_window_scale";
    private static final String KEY_FLOAT_INTERVAL = "float_window_interval_ms";
    private static final String KEY_FLOAT_STYLE = "float_window_style";
    private static final String KEY_FLOAT_FIXED = "float_fixed";
    private static final int REQUEST_PICK_IMAGE = 1001;

    private SharedPreferences mSp;
    private String[] timeOptions;
    private Spinner spWidgetRefresh, spNotifyRefresh;

    // 悬浮窗控件
    private androidx.appcompat.widget.SwitchCompat switchFloatWindow;
    private LinearLayout floatOptions;
    private SeekBar seekFloatAlpha, seekFloatScale;
    private TextView tvFloatAlphaValue, tvFloatScaleValue;
    private Spinner spFloatInterval, spFloatStyle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_advanced_settings);

        mSp = getSharedPreferences(SP_CONFIG, MODE_PRIVATE);

        setupCustomBackground();
        setupSwitches();
        setupRefreshSpinners();
        setupFloatWindow();
        setupFloatShowChecks();
        setupNotifyData();
        loadRefreshSpinnerSelection();
    }

    // ========== 自定义背景图 ==========
    private void setupCustomBackground() {
        LinearLayout itemBg = findViewById(R.id.item_custom_bg);
        itemBg.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        });

        LinearLayout itemResetBg = findViewById(R.id.item_reset_bg);
        itemResetBg.setOnClickListener(v -> {
            BaseActivity.saveBackgroundPath(this, "");
            refreshBackground(); // BaseActivity 自带方法
            Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                String path = saveImageToInternalStorage(imageUri);
                if (path != null) {
                    BaseActivity.saveBackgroundPath(this, path);
                    refreshBackground();
                    Toast.makeText(this, "背景已更新", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "图片保存失败，请重试", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private String saveImageToInternalStorage(Uri imageUri) {
        try {
            File destDir = getFilesDir();
            if (!destDir.exists()) destDir.mkdirs();
            String fileName = "custom_bg_" + System.currentTimeMillis() + ".jpg";
            File destFile = new File(destDir, fileName);
            try (InputStream in = getContentResolver().openInputStream(imageUri);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
            LogRecorder.getInstance().info("AdvancedSettings", "背景图片已保存: " + destFile.getAbsolutePath());
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            LogRecorder.getInstance().error("AdvancedSettings", "保存背景图片失败：" + e.getMessage());
            return null;
        }
    }

    // ========== 原有方法 ==========
    private void setupSwitches() {
        SwitchCompat switchChargeBoost = findViewById(R.id.switch_charge_boost);
        boolean isChargeBoost = mSp.getBoolean(KEY_CHARGE_BOOST, true);
        switchChargeBoost.setChecked(isChargeBoost);
        switchChargeBoost.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSp.edit().putBoolean(KEY_CHARGE_BOOST, isChecked).apply();
            LogRecorder.getInstance().info("UserAction", "充电自动狂暴开关：" + isChecked);
        });

        SwitchCompat switchHideRecents = findViewById(R.id.switch_hide_recents);
        boolean isHide = mSp.getBoolean(KEY_HIDE_RECENTS, false);
        switchHideRecents.setChecked(isHide);
        switchHideRecents.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSp.edit().putBoolean(KEY_HIDE_RECENTS, isChecked).apply();
            LogRecorder.getInstance().info("UserAction", "隐藏最近任务：" + isChecked);
        });

        SwitchCompat switchScreenOffStop = findViewById(R.id.switch_screen_off_stop);
        boolean screenOffStop = mSp.getBoolean(KEY_SCREEN_OFF_STOP, false);
        switchScreenOffStop.setChecked(screenOffStop);
        switchScreenOffStop.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSp.edit().putBoolean(KEY_SCREEN_OFF_STOP, isChecked).apply();
            LogRecorder.getInstance().info("UserAction", "息屏停转：" + isChecked);
        });
    }

    private void setupRefreshSpinners() {
        spWidgetRefresh = findViewById(R.id.sp_widget_refresh);
        spNotifyRefresh = findViewById(R.id.sp_notify_refresh);

        timeOptions = getResources().getStringArray(R.array.refresh_time_options);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, timeOptions);
        spWidgetRefresh.setAdapter(adapter);
        spNotifyRefresh.setAdapter(adapter);

        spWidgetRefresh.setOnItemSelectedListener(createIntervalListener(RefreshPrefs::setWidgetInterval));
        spNotifyRefresh.setOnItemSelectedListener(createIntervalListener(RefreshPrefs::setNotifyInterval));
    }

    // ========== 信息悬浮窗设置 ==========

    private void setupFloatWindow() {
        switchFloatWindow = findViewById(R.id.switch_float_window);
        floatOptions = findViewById(R.id.float_options);
        seekFloatAlpha = findViewById(R.id.seek_float_alpha);
        seekFloatScale = findViewById(R.id.seek_float_scale);
        tvFloatAlphaValue = findViewById(R.id.tv_float_alpha_value);
        tvFloatScaleValue = findViewById(R.id.tv_float_scale_value);
        spFloatInterval = findViewById(R.id.sp_float_interval);
        spFloatStyle = findViewById(R.id.sp_float_style);

        boolean enabled = FloatWindowService.isEnabled(this);
        switchFloatWindow.setChecked(enabled);
        floatOptions.setVisibility(enabled ? View.VISIBLE : View.GONE);

        switchFloatWindow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 与当前状态相同时跳过（避免 onResume 重入触发）
            if (isChecked == FloatWindowService.isEnabled(this)) return;
            if (isChecked && !Settings.canDrawOverlays(this)) {
                // 未授权：记住开启意图，跳转授权页
                FloatWindowService.setEnabled(this, true);
                floatOptions.setVisibility(View.VISIBLE);
                Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            FloatWindowService.setEnabled(this, isChecked);
            if (isChecked) {
                startService(new Intent(this, FloatWindowService.class));
            } else {
                stopService(new Intent(this, FloatWindowService.class));
            }
            floatOptions.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            LogRecorder.getInstance().info("UserAction", "信息悬浮窗开关：" + isChecked);
        });

        // 透明度 20-100%
        seekFloatAlpha.setMax(80);
        int alpha = Math.max(20, Math.min(100, mSp.getInt(KEY_FLOAT_ALPHA, 90)));
        seekFloatAlpha.setProgress(alpha - 20);
        tvFloatAlphaValue.setText(alpha + "%");
        seekFloatAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + 20;
                mSp.edit().putInt(KEY_FLOAT_ALPHA, v).apply();
                tvFloatAlphaValue.setText(v + "%");
                sendFloatUpdate();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 缩放 50-120%
        seekFloatScale.setMax(50);
        int scale = Math.max(50, Math.min(120, mSp.getInt(KEY_FLOAT_SCALE, 80)));
        seekFloatScale.setProgress(scale - 50);
        tvFloatScaleValue.setText(scale + "%");
        seekFloatScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + 50;
                mSp.edit().putInt(KEY_FLOAT_SCALE, v).apply();
                tvFloatScaleValue.setText(v + "%");
                sendFloatUpdate();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 固定位置 + 点击穿透开关
        androidx.appcompat.widget.SwitchCompat switchFloatFixed = findViewById(R.id.switch_float_fixed);
        boolean fixedSaved = mSp.getBoolean(KEY_FLOAT_FIXED, false);
        switchFloatFixed.setChecked(fixedSaved);
        switchFloatFixed.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSp.edit().putBoolean(KEY_FLOAT_FIXED, isChecked).apply();
            sendFloatUpdate();
        });

        // 刷新频率：0.5/1/1.5/2 秒
        final String[] intervalOptions = {"0.5秒", "1秒", "1.5秒", "2秒"};
        final long[] intervalValues = {500L, 1000L, 1500L, 2000L};
        spFloatInterval.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, intervalOptions));
        long savedInterval = mSp.getLong(KEY_FLOAT_INTERVAL, 1000L);
        for (int i = 0; i < intervalValues.length; i++) {
            if (intervalValues[i] == savedInterval) spFloatInterval.setSelection(i);
        }
        spFloatInterval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSp.edit().putLong(KEY_FLOAT_INTERVAL, intervalValues[position]).apply();
                sendFloatUpdate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 模式：横版 / 竖版
        final String[] styleOptions = {"横版", "竖版"};
        spFloatStyle.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, styleOptions));
        spFloatStyle.setSelection(Math.max(0, Math.min(1, mSp.getInt(KEY_FLOAT_STYLE, 0))));
        spFloatStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == mSp.getInt(KEY_FLOAT_STYLE, 0)) return; // 同步选中时跳过
                mSp.edit().putInt(KEY_FLOAT_STYLE, position).apply();
                sendFloatUpdate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ========== 悬浮窗显示数据项 ==========
    private void setupFloatShowChecks() {
        final CheckBox[] checks = {
                findViewById(R.id.check_float_mode),
                findViewById(R.id.check_float_temp),
                findViewById(R.id.check_float_rpm),
                findViewById(R.id.check_float_power),
                findViewById(R.id.check_float_pwm)
        };
        final String[] keys = {
                "float_show_mode", "float_show_temp", "float_show_rpm",
                "float_show_power", "float_show_pwm"
        };
        for (int i = 0; i < checks.length; i++) {
            final int idx = i;
            checks[i].setChecked(mSp.getBoolean(keys[i], true));
            checks[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                int shown = 0;
                for (CheckBox cb : checks) if (cb.isChecked()) shown++;
                if (shown == 0) {
                    // 至少保留一项
                    checks[idx].setChecked(true);
                    Toast.makeText(this, "悬浮窗至少保留一项数据", Toast.LENGTH_SHORT).show();
                    return;
                }
                mSp.edit().putBoolean(keys[idx], isChecked).apply();
                sendFloatUpdate();
            });
        }
    }

    // ========== 通知栏数据位置 ==========
    private void setupNotifyData() {
        // 自定义开关：关闭时隐藏位置选项，默认 档位+转速+温度
        final androidx.appcompat.widget.SwitchCompat switchNotifyCustom = findViewById(R.id.switch_notify_custom);
        final LinearLayout notifyOptions = findViewById(R.id.notify_data_options);
        boolean custom = mSp.getBoolean("notify_custom_data", false);
        switchNotifyCustom.setChecked(custom);
        notifyOptions.setVisibility(custom ? View.VISIBLE : View.GONE);
        switchNotifyCustom.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSp.edit().putBoolean("notify_custom_data", isChecked).apply();
            notifyOptions.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            sendNotifyRefresh();
        });

        final String[] dataOptions = {"档位", "转速", "温度", "功耗", "PWM"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, dataOptions);

        Spinner spLeft = findViewById(R.id.sp_notify_left);
        Spinner spMid = findViewById(R.id.sp_notify_mid);
        Spinner spRight = findViewById(R.id.sp_notify_right);
        spLeft.setAdapter(adapter);
        spMid.setAdapter(adapter);
        spRight.setAdapter(adapter);
        spLeft.setSelection(Math.max(0, Math.min(4, mSp.getInt("notify_pos_left", 0))));
        spMid.setSelection(Math.max(0, Math.min(4, mSp.getInt("notify_pos_mid", 1))));
        spRight.setSelection(Math.max(0, Math.min(4, mSp.getInt("notify_pos_right", 2))));

        AdapterView.OnItemSelectedListener posListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String key = "notify_pos_left";
                if (parent == spMid) key = "notify_pos_mid";
                else if (parent == spRight) key = "notify_pos_right";
                mSp.edit().putInt(key, position).apply();
                sendNotifyRefresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        spLeft.setOnItemSelectedListener(posListener);
        spMid.setOnItemSelectedListener(posListener);
        spRight.setOnItemSelectedListener(posListener);

    }

    // 通知数据/优先级变化：触发 FanRefreshService 立即重建通知
    private void sendNotifyRefresh() {
        try {
            startService(new Intent(this, FanRefreshService.class)
                    .setAction(FanRefreshService.ACTION_REFRESH_NOW));
        } catch (Exception ignored) {}
    }

    private void sendFloatUpdate() {
        try {
            startService(new Intent(this, FloatWindowService.class)
                    .setAction(FloatWindowService.ACTION_UPDATE));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSp == null || switchFloatWindow == null) return;
        boolean enabled = FloatWindowService.isEnabled(this);
        if (enabled && !Settings.canDrawOverlays(this)) {
            // 授权被拒绝，回滚开关
            FloatWindowService.setEnabled(this, false);
            enabled = false;
            Toast.makeText(this, "未授予悬浮窗权限，已关闭悬浮窗功能", Toast.LENGTH_SHORT).show();
        }
        switchFloatWindow.setChecked(enabled);
        if (floatOptions != null) {
            floatOptions.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        // 授权返回后自动启动悬浮窗
        if (enabled && !FloatWindowService.isRunning()) {
            startService(new Intent(this, FloatWindowService.class));
        }
        // 同步悬浮窗样式（点击悬浮窗切换横竖版后，设置页下拉显示最新状态）
        if (spFloatStyle != null) {
            int style = Math.max(0, Math.min(1, mSp.getInt(KEY_FLOAT_STYLE, 0)));
            if (spFloatStyle.getSelectedItemPosition() != style) {
                spFloatStyle.setSelection(style);
            }
        }
    }

    private AdapterView.OnItemSelectedListener createIntervalListener(RefreshPrefs.IntervalSaver saver) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                long ms = RefreshPrefs.timeStrToMs(timeOptions[position]);
                saver.save(AdvancedSettingsActivity.this, ms);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    private void loadRefreshSpinnerSelection() {
        long widgetMs = RefreshPrefs.getWidgetInterval(this);
        long notifyMs = RefreshPrefs.getNotifyInterval(this);
        String widgetSel = RefreshPrefs.msToTimeStr(widgetMs);
        String notifySel = RefreshPrefs.msToTimeStr(notifyMs);

        for (int i = 0; i < timeOptions.length; i++) {
            if (timeOptions[i].equals(widgetSel)) spWidgetRefresh.setSelection(i);
            if (timeOptions[i].equals(notifySel)) spNotifyRefresh.setSelection(i);
        }
    }
}