package com.fan.widget;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AppCustomConfigActivity extends BaseActivity {

    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_SHOW_SYSTEM = "show_system_apps";
    private static final String MODE_PREFIX = "mode_";

    private SharedPreferences mSharedPrefs;
    private ExecutorService mExecutor;
    private Future<?> mLoadTask;

    private EditText mSearchEt;
    private RecyclerView mRecyclerView;
    private SwitchMaterial mShowSystemSwitch;
    private AppConfigAdapter mAdapter;

    private final List<AppInfo> mAllApps = new ArrayList<>();
    private final List<AppInfo> mShowApps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_custom_config);

        // 状态栏由 BaseActivity 统一处理

        mSharedPrefs = getSharedPreferences(SP_CONFIG, MODE_PRIVATE);
        mExecutor = Executors.newSingleThreadExecutor();

        initViews();
        setupListeners();
        loadAllAppsAsync();
    }

    private void initViews() {
        mSearchEt = findViewById(R.id.et_search);
        mRecyclerView = findViewById(R.id.rv_app_list);
        mShowSystemSwitch = findViewById(R.id.switch_show_system);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new AppConfigAdapter(this, this::saveAppMode);
        mRecyclerView.setAdapter(mAdapter);

        boolean showSystem = mSharedPrefs.getBoolean(KEY_SHOW_SYSTEM, false);
        mShowSystemSwitch.setChecked(showSystem);
    }

    private void setupListeners() {
        mSearchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                filterApps();
            }
        });

        mShowSystemSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mSharedPrefs.edit().putBoolean(KEY_SHOW_SYSTEM, isChecked).apply();
            filterApps();
        });
    }

    // ========== 异步加载应用列表 ==========
    private void loadAllAppsAsync() {
        mLoadTask = mExecutor.submit(() -> {
            List<AppInfo> loadedApps = loadAppsInternal();
            runOnUiThread(() -> {
                mAllApps.clear();
                mAllApps.addAll(loadedApps);
                filterApps();
            });
            return null;
        });
    }

    private List<AppInfo> loadAppsInternal() {
        List<AppInfo> result = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Set<String> addedPackages = new HashSet<>();

        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        int flag = PackageManager.GET_META_DATA;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flag |= PackageManager.MATCH_ALL;
        }

        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, flag);
        for (ResolveInfo ri : resolveInfos) {
            String pkg = ri.activityInfo.packageName;
            if (!addedPackages.add(pkg)) continue;

            AppInfo info = new AppInfo();
            info.appName = ri.loadLabel(pm).toString();
            info.packageName = pkg;
            info.icon = null; // 图标改为列表项可见时惰性加载，降低内存占用
            info.isSystemApp = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            info.fanMode = mSharedPrefs.getInt(MODE_PREFIX + pkg, 0);
            result.add(info);
        }

        result.sort((a, b) -> a.appName.compareToIgnoreCase(b.appName));
        return result;
    }

    // ========== 过滤与显示 ==========
    private void filterApps() {
        mShowApps.clear();
        String keyword = mSearchEt.getText().toString().trim().toLowerCase();
        boolean showSystem = mShowSystemSwitch.isChecked();

        for (AppInfo info : mAllApps) {
            if (!showSystem && info.isSystemApp) continue;
            if (!keyword.isEmpty() &&
                    !info.appName.toLowerCase().contains(keyword) &&
                    !info.packageName.toLowerCase().contains(keyword)) {
                continue;
            }
            mShowApps.add(info);
        }
        mAdapter.setData(mShowApps);
    }

    // ========== 保存配置 ==========
    private void saveAppMode(String packageName, int mode) {
        mSharedPrefs.edit().putInt(MODE_PREFIX + packageName, mode).apply();
    }

    // ========== 生命周期 ==========
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mLoadTask != null && !mLoadTask.isDone()) {
            mLoadTask.cancel(true);
        }
        if (mExecutor != null) {
            mExecutor.shutdownNow();
        }
    }
}