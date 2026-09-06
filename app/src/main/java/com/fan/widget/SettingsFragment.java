package com.fan.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {

    private static final String SP_CONFIG = "fan_widget_config";
    private static final String TEXT_DETECTING = "检测中";

    private SharedPreferences mSp;
    private TextView tvDeviceModelStatus;
    private LinearLayout itemDeviceCheck, itemAddWidget, itemViewLog;
    private ExecutorService mExecutor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mSp = requireActivity().getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE);
        mExecutor = Executors.newSingleThreadExecutor();

        setupDeviceModel(view);
        setupAppVersion(view);
        setupClickListeners(view);
        refreshModelUi();

        // 个性化设置入口（跳转到 AdvancedSettingsActivity）
        view.findViewById(R.id.item_advanced_settings).setOnClickListener(v ->
                startActivity(new Intent(getContext(), AdvancedSettingsActivity.class))

        );

        // 关于软件入口
        view.findViewById(R.id.item_about).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AboutActivity.class))
        );
    }

    // ========== 初始化模块 ==========

    private void setupDeviceModel(View view) {
        tvDeviceModelStatus = view.findViewById(R.id.tv_device_model_status);
        itemDeviceCheck = view.findViewById(R.id.item_device_check);
        itemDeviceCheck.setOnClickListener(v -> reCheckDeviceModel());
    }

    private void setupAppVersion(View view) {
        TextView tvVersion = view.findViewById(R.id.tv_app_version);
        TextView tvBuildTime = view.findViewById(R.id.tv_build_time);
        try {
            String packageName = requireContext().getPackageName();
            String versionName = requireContext().getPackageManager()
                    .getPackageInfo(packageName, 0).versionName;
            tvVersion.setText(String.format("版本：%s", versionName));
            tvBuildTime.setText("构建于 " + BuildConfig.BUILD_TIME);
        } catch (PackageManager.NameNotFoundException e) {
            tvVersion.setText("未知版本");
            tvBuildTime.setText("");
        }
    }

    private void setupClickListeners(View view) {
        itemAddWidget = view.findViewById(R.id.item_add_widget);
        itemAddWidget.setOnClickListener(v -> openAddWidgetPage());

        view.findViewById(R.id.item_permission_enter).setOnClickListener(v ->
        startActivity(new Intent(getContext(), PermissionDetailActivity.class))
        );

        itemViewLog = view.findViewById(R.id.item_view_log);
        itemViewLog.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), LogViewerActivity.class))
        );
    }

    // ========== 设备型号相关 ==========

    private void refreshModelUi() {
        String model = FanApp.globalDeviceModel;
        tvDeviceModelStatus.setText(model.isEmpty() ? TEXT_DETECTING : model);
    }

    private void reCheckDeviceModel() {
        tvDeviceModelStatus.setText(TEXT_DETECTING);
        mExecutor.execute(() -> {
            String raw;
            try {
                raw = FanUtil.getDeviceModelName();
            } catch (Exception e) {
                raw = "读取失败";
            }
            FanApp.globalDeviceModel = raw;
            requireActivity().runOnUiThread(this::refreshModelUi);
        });
    }

    // ========== 添加小组件 ==========

    private void openAddWidgetPage() {
        Context ctx = requireContext();
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        ComponentName widgetComp = new ComponentName(ctx, FanWidget.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && awm.isRequestPinAppWidgetSupported()) {
            awm.requestPinAppWidget(widgetComp, null, null);
            return;
        }

        try {
            Intent pickIntent = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
            pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, widgetComp);
            pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            startActivity(pickIntent);
        } catch (Exception e) {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
        }
    }

    // ========== 生命周期 ==========

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mExecutor != null) {
            mExecutor.shutdownNow();
        }
    }
}