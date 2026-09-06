package com.fan.widget;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
    private static final String KEY_NOTIFICATION_TITLE = "notification_title";

    private static final String DEFAULT_NOTIFY_TITLE = "散热风扇正在运行";
    private static final int REQUEST_PICK_IMAGE = 1001;

    private SharedPreferences mSp;
    private String[] timeOptions;
    private Spinner spWidgetRefresh, spNotifyRefresh;
    private EditText etNotifyTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_advanced_settings);

        mSp = getSharedPreferences(SP_CONFIG, MODE_PRIVATE);

        setupCustomBackground();
        setupSwitches();
        setupRefreshSpinners();
        setupNotificationTitle();
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

    private void setupNotificationTitle() {
        etNotifyTitle = findViewById(R.id.et_notify_title);
        String savedTitle = mSp.getString(KEY_NOTIFICATION_TITLE, DEFAULT_NOTIFY_TITLE);
        etNotifyTitle.setText(savedTitle);
        etNotifyTitle.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String input = etNotifyTitle.getText().toString().trim();
                if (input.isEmpty()) {
                    input = DEFAULT_NOTIFY_TITLE;
                    etNotifyTitle.setText(input);
                }
                mSp.edit().putString(KEY_NOTIFICATION_TITLE, input).apply();
                LogRecorder.getInstance().info("UserAction", "通知标题：" + input);
            }
        });
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