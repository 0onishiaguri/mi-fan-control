package com.fan.widget;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {
    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_CONTROL_MODE = "control_mode";
    private static final String KEY_TARGET_PWM = "target_pwm";

    // 转速区间常量
    private static final int RPM_LOW_THRESHOLD = 13000;
    private static final int RPM_MID_THRESHOLD = 18000;
    private static final int MAX_RPM_BASE = 26000;
    private static final int RPM_HYSTERESIS = 1000;

    private static final int ZONE_STOP = 0;
    private static final int ZONE_LOW = 1;
    private static final int ZONE_MID = 2;
    private static final int ZONE_HIGH = 3;

    private static final float BASE_ROTATE_STEP = 4f;
    private static final long ANIMATION_INTERVAL_MS = 80;

    private FrameLayout flFanBlade;
    private ImageView ivFanBladeLight;
    private ImageView ivFanLight;
    private ValueAnimator fanValueAnimator;
    private ObjectAnimator fanResetAnimator;

    private int lastRpmZone = -1;
    private int mFilteredRpm = 0;
    private float currentRotateSpeedFactor = 0f;

    private TextView tvMainMode, tvMainRpm, tvMainTemp, tvSpeedRatio;
    private SimpleLineChartView mChartView;
    private TextView tvBatteryStatus, tvBatteryTemp;
    private RadioGroup mRgControlMode;
    private LinearLayout mCardPwmSeek, mCardSmartEdit, mCardAppCustom;
    private SeekBar mSeekPwm;
    private TextView tvPwmValue, tvEditCurve, tvAddAppConfig;
    private LinearLayout mCardTempNode;
    private TextView tvTempNodeName, tvTempNodeHint;
    private QuickLevelPicker mQuickPicker;
    private TextView tvServiceStatus, tvRunTime;
    private LinearLayout itemServiceStatus;
    private ImageView ivModeHelp;

    private Handler mMainHandler;
    private Runnable mRefreshRunnable;
    private final Runnable mFanAnimStateRunnable = new Runnable() {
        @Override
        public void run() {
            updateFanAnimation();
        }
    };
    private SharedPreferences mSp;
    private ExecutorService mExecutor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mSp = requireActivity().getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE);
        mMainHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();

        initViews(view);
        initFanValueAnimator();
        setupListeners();

        mRefreshRunnable = () -> {
            refreshUiFromCache();
            long interval = RefreshPrefs.getWidgetInterval(requireContext());
            mMainHandler.postDelayed(mRefreshRunnable, interval);
        };
    }

    private void initViews(View view) {
        flFanBlade = view.findViewById(R.id.fl_fan_blade);
        ivFanBladeLight = view.findViewById(R.id.iv_fan_blade_light);
        ivFanLight = view.findViewById(R.id.iv_fan_light);
        tvMainMode = view.findViewById(R.id.tv_main_mode);
        tvMainRpm = view.findViewById(R.id.tv_main_rpm);
        tvMainTemp = view.findViewById(R.id.tv_main_temp);
        tvSpeedRatio = view.findViewById(R.id.tv_speed_ratio);
        mChartView = view.findViewById(R.id.chart_realtime);
        tvBatteryStatus = view.findViewById(R.id.tv_battery_status);
        tvBatteryTemp = view.findViewById(R.id.tv_battery_temp);
        mRgControlMode = view.findViewById(R.id.rg_control_mode);
        mCardPwmSeek = view.findViewById(R.id.card_pwm_seek);
        mCardSmartEdit = view.findViewById(R.id.card_smart_edit);
        mCardAppCustom = view.findViewById(R.id.card_app_custom);
        mSeekPwm = view.findViewById(R.id.seek_pwm);
        tvPwmValue = view.findViewById(R.id.tv_pwm_value);
        tvEditCurve = view.findViewById(R.id.tv_edit_curve);
        tvAddAppConfig = view.findViewById(R.id.tv_add_app_config);
        mCardTempNode = view.findViewById(R.id.card_temp_node);
        tvTempNodeName = view.findViewById(R.id.tv_temp_node_name);
        tvTempNodeHint = view.findViewById(R.id.tv_temp_node_hint);
        mQuickPicker = view.findViewById(R.id.quick_level_picker);
        tvServiceStatus = view.findViewById(R.id.tv_service_status);
        tvRunTime = view.findViewById(R.id.tv_run_time);
        itemServiceStatus = view.findViewById(R.id.item_service_status);
        ivModeHelp = view.findViewById(R.id.iv_mode_help);
    }

    private void setupListeners() {
        ivModeHelp.setOnClickListener(v -> showModeHelpDialog());
        mCardTempNode.setOnClickListener(v -> showTempNodeSelector());

        int savedMode = mSp.getInt(KEY_CONTROL_MODE, FanUtil.MODE_SYSTEM);
        FanUtil.currentControlMode = savedMode;
        initModeRadio(savedMode);
        updateModeCardVisibility(savedMode);

        mRgControlMode.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = getModeFromCheckedId(checkedId);
            LogRecorder.getInstance().info("UserAction", "切换控制模式 → " + FanUtil.getModeNameById(mode));
            mExecutor.execute(() -> FanUtil.switchControlMode(mode));
            updateModeCardVisibility(mode);
            lastRpmZone = -1;
            mFilteredRpm = 0;
        });

        tvEditCurve.setOnClickListener(v -> startActivity(new Intent(requireContext(), SmartCurveEditActivity.class)));
        tvAddAppConfig.setOnClickListener(v -> startActivity(new Intent(requireContext(), AppCustomConfigActivity.class)));

        int savedPwm = mSp.getInt(KEY_TARGET_PWM, 0);
        mSeekPwm.setProgress(savedPwm);
        tvPwmValue.setText(savedPwm + "%");
        mSeekPwm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvPwmValue.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int pwm = seekBar.getProgress();
                mSp.edit().putInt(KEY_TARGET_PWM, pwm).apply();
                FanUtil.targetPwmDuty = pwm;
                LogRecorder.getInstance().info("UserAction", "手动设置PWM = " + pwm + "%");
                mExecutor.execute(() -> FanUtil.setFanPwmDuty(pwm));
            }
        });

        mQuickPicker.setOnLevelSelectedListener(level -> {
            String name = level == 0 ? "关闭" : level == 1 ? "静谧" : level == 2 ? "高速" : "狂暴";
            LogRecorder.getInstance().info("UserAction", "快捷档位选择 → " + name);
            mExecutor.execute(() -> {
                if (level == 0) FanUtil.fanStop();
                else if (level == 1) FanUtil.fanSilent();
                else if (level == 2) FanUtil.fanFast();
                else FanUtil.fanMax();
            });
        });

        itemServiceStatus.setOnClickListener(v -> restartService());
    }

    private int getModeFromCheckedId(int checkedId) {
        if (checkedId == R.id.rb_mode_low) return FanUtil.MODE_LOW_LEVEL;
        if (checkedId == R.id.rb_mode_smart) return FanUtil.MODE_SMART;
        if (checkedId == R.id.rb_mode_app) return FanUtil.MODE_APP_CUSTOM;
        return FanUtil.MODE_SYSTEM;
    }

    // ========== 风扇动画核心 ==========

    private void initFanValueAnimator() {
        fanValueAnimator = ValueAnimator.ofFloat(0f, 1f);
        fanValueAnimator.setRepeatCount(ValueAnimator.INFINITE);
        fanValueAnimator.setRepeatMode(ValueAnimator.RESTART);
        fanValueAnimator.setDuration(16);
        fanValueAnimator.addUpdateListener(animation -> {
            if (currentRotateSpeedFactor <= 0) return;
            float newRot = (flFanBlade.getRotation() + BASE_ROTATE_STEP * currentRotateSpeedFactor) % 360f;
            flFanBlade.setRotation(newRot);
        });
        fanValueAnimator.start();
    }

    private void updateFanAnimation() {
        int rawRpm = FanUtil.cacheRpm;
        if (rawRpm > 0 && fanResetAnimator != null) {
            fanResetAnimator.cancel();
            fanResetAnimator = null;
        }

        int stableRpm = applyHysteresis(rawRpm);
        // 旋转速度按 PWM 占空比判定（0-40 → 30%，41-70 → 60%，71-100 → 100%），读缓存不阻塞主线程
        int zone = getZone(stableRpm, FanUtil.getCachedPwmDuty());

        if (zone != lastRpmZone) {
            lastRpmZone = zone;
            if (zone == ZONE_STOP) {
                stopFanAnimation();
                ivFanLight.setAlpha(0f);
            } else {
                applyZoneResources(zone);
                updateLightAlpha(stableRpm);
                resumeFanAnimation();
            }
        } else {
            updateLightAlpha(stableRpm);
        }

        mMainHandler.postDelayed(mFanAnimStateRunnable, ANIMATION_INTERVAL_MS);
    }

    private int applyHysteresis(int rawRpm) {
        if (rawRpm <= RPM_HYSTERESIS) {
            mFilteredRpm = rawRpm;
            return rawRpm;
        }
        if (Math.abs(rawRpm - mFilteredRpm) < RPM_HYSTERESIS) {
            return mFilteredRpm;
        }
        mFilteredRpm = rawRpm;
        return rawRpm;
    }

    private int getZone(int rpm, int pwm) {
        if (rpm <= 0) return ZONE_STOP;
        if (pwm <= 40) return ZONE_LOW;
        if (pwm <= 70) return ZONE_MID;
        return ZONE_HIGH;
    }

    private void applyZoneResources(int zone) {
        int bladeRes, lightRes;
        float speedFactor;
        switch (zone) {
            case ZONE_LOW:
                speedFactor = 0.30f;
                bladeRes = R.drawable.fan_blade_layer0;
                lightRes = R.drawable.fan_light_layer0;
                break;
            case ZONE_MID:
                speedFactor = 0.60f;
                bladeRes = R.drawable.fan_blade_layer1;
                lightRes = R.drawable.fan_light_layer1;
                break;
            default: // HIGH
                speedFactor = 1.00f;
                bladeRes = R.drawable.fan_blade_layer2;
                lightRes = R.drawable.fan_light_layer2;
                break;
        }
        ivFanBladeLight.setImageResource(bladeRes);
        ivFanLight.setImageResource(lightRes);
        currentRotateSpeedFactor = speedFactor;
    }

    private void updateLightAlpha(int rpm) {
        float alpha = Math.min(1f, (float) rpm / MAX_RPM_BASE);
        ivFanLight.setAlpha(alpha);
    }

    private void stopFanAnimation() {
        if (fanResetAnimator != null) {
            fanResetAnimator.cancel();
            fanResetAnimator = null;
        }
        // 风扇停转时暂停计时动画，消除 60fps 空转
        if (fanValueAnimator != null && fanValueAnimator.isRunning()) {
            fanValueAnimator.pause();
        }
        currentRotateSpeedFactor = 0f;
        float currentRotation = flFanBlade.getRotation();
        if (Math.abs(currentRotation) < 1f) {
            flFanBlade.setRotation(0f);
            return;
        }
        fanResetAnimator = ObjectAnimator.ofFloat(flFanBlade, View.ROTATION, currentRotation, 0f);
        fanResetAnimator.setDuration(500);
        fanResetAnimator.setInterpolator(new DecelerateInterpolator());
        fanResetAnimator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                fanResetAnimator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                fanResetAnimator = null;
            }

            @Override
            public void onAnimationRepeat(Animator animation) {}
        });
        fanResetAnimator.start();
    }

    private void resumeFanAnimation() {
        if (fanValueAnimator == null) return;
        if (fanValueAnimator.isPaused()) {
            fanValueAnimator.resume();
        } else if (!fanValueAnimator.isRunning()) {
            fanValueAnimator.start();
        }
    }

    // ========== UI 模式管理 ==========

    private void initModeRadio(int mode) {
        int id;
        switch (mode) {
            case FanUtil.MODE_LOW_LEVEL:
                id = R.id.rb_mode_low;
                break;
            case FanUtil.MODE_SMART:
                id = R.id.rb_mode_smart;
                break;
            case FanUtil.MODE_APP_CUSTOM:
                id = R.id.rb_mode_app;
                break;
            default:
                id = R.id.rb_mode_system;
                break;
        }
        mRgControlMode.check(id);
    }

    private void updateModeCardVisibility(int mode) {
        mCardPwmSeek.setVisibility(mode == FanUtil.MODE_LOW_LEVEL ? View.VISIBLE : View.GONE);
        mCardSmartEdit.setVisibility(mode == FanUtil.MODE_SMART ? View.VISIBLE : View.GONE);
        mCardAppCustom.setVisibility(mode == FanUtil.MODE_APP_CUSTOM ? View.VISIBLE : View.GONE);
    }

    // ========== 生命周期 ==========

    @Override
    public void onResume() {
        super.onResume();
        mMainHandler.post(mRefreshRunnable);
        mMainHandler.removeCallbacks(mFanAnimStateRunnable);
        mMainHandler.post(mFanAnimStateRunnable);
        refreshServiceStatus();
        resumeFanAnimation();
    }

    @Override
    public void onPause() {
        super.onPause();
        mMainHandler.removeCallbacks(mRefreshRunnable);
        mMainHandler.removeCallbacks(mFanAnimStateRunnable);
        // 页面不可见时暂停动画，避免后台空转耗电（恢复时由 resumeFanAnimation 续跑）
        if (fanValueAnimator != null && fanValueAnimator.isRunning()) {
            fanValueAnimator.pause();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mMainHandler.removeCallbacks(mRefreshRunnable);
        mMainHandler.removeCallbacks(mFanAnimStateRunnable);
        if (fanValueAnimator != null) fanValueAnimator.cancel();
        if (fanResetAnimator != null) {
            fanResetAnimator.cancel();
            fanResetAnimator = null;
        }
        if (mExecutor != null) mExecutor.shutdownNow();
    }

    // ========== UI 刷新 ==========

    private void refreshUiFromCache() {
        int rpm = FanUtil.cacheRpm;
        int temp = FanUtil.cacheTemp;

        tvMainMode.setText(FanUtil.getCurrentModeName(rpm));
        tvMainRpm.setText(String.valueOf(rpm));
        tvMainTemp.setText(temp + "°");
        tvMainRpm.setTextColor(FanUtil.getRpmColor(rpm));

        // 左下：实时 PWM 占空比（%）（读缓存，主线程不执行 su）
        tvSpeedRatio.setText(FanUtil.getCachedPwmDuty() + "%");
        tvSpeedRatio.setTextColor(FanUtil.getRpmColor(rpm));

        tvMainTemp.setTextColor(FanUtil.getTempColor(temp));
        tvTempNodeName.setText(FanUtil.getCurrentTempNodeName());

        mChartView.addDataPoint(rpm, temp);

        tvBatteryStatus.setText(FanUtil.sIsCharging ? "充电中" : "放电中");
        tvBatteryTemp.setText(getBatteryTemperature() + "°");

        tvRunTime.setText(formatRunTime(FanUtil.totalRunTimeMs));

        // 快捷档位选择器状态同步（用户拖动中不干扰；系统模式读缓存避免主线程 su）
        if (mQuickPicker != null && !mQuickPicker.isTracking()) {
            mQuickPicker.setCurrentLevel(getQuickLevelFromState(rpm), false);
        }
    }

    // 当前状态 → 快捷档位 index（关闭/静谧/高速/狂暴 = 0/1/2/4）
    private int getQuickLevelFromState(int rpm) {
        if (rpm == 0) return 0;
        switch (FanUtil.currentControlMode) {
            case FanUtil.MODE_LOW_LEVEL:
            case FanUtil.MODE_APP_CUSTOM:
                return FanUtil.currentTargetLevel;
            case FanUtil.MODE_SMART: {
                int pwm = FanUtil.targetPwmDuty;
                if (pwm <= 0) return 0;
                if (pwm <= 40) return 1;
                if (pwm <= 70) return 2;
                return 4;
            }
            default: // MODE_SYSTEM：不做 su 读取，映射为高速档
                return 2;
        }
    }

    private int getBatteryTemperature() {
        try {
            Intent batteryIntent = requireContext().registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                return batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String formatRunTime(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        return hours + "小时" + minutes + "分";
    }

    // ========== 服务管理 ==========

    private void refreshServiceStatus() {
        boolean isRunning = isServiceRunning(FanRefreshService.class.getName());
        tvServiceStatus.setText(isRunning ? "运行中" : "未运行");
        tvServiceStatus.setTextColor(isRunning ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
    }

    private boolean isServiceRunning(String serviceName) {
        ActivityManager am = (ActivityManager) requireContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : am.getRunningServices(Integer.MAX_VALUE)) {
            if (service.service.getClassName().equals(serviceName)) return true;
        }
        return false;
    }

    private void restartService() {
        LogRecorder.getInstance().info("ServiceEvent", "用户触发重启服务");
        Intent serviceIntent = new Intent(requireContext(), FanRefreshService.class);
        requireContext().stopService(serviceIntent);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(serviceIntent);
            } else {
                requireContext().startService(serviceIntent);
            }
            refreshServiceStatus();
            LogRecorder.getInstance().info("ServiceEvent", "服务已重启");
        }, 500);
    }

    // ========== 弹窗（自定义 Dialog：宽85%，高度自适应，背景80%透明度） ==========

    private Dialog createRoundDialog(int layoutRes) {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutRes);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().getDecorView().setAlpha(1f);
            dialog.getWindow().setDimAmount(0.2f);

            // 获取屏幕尺寸，只固定宽度
            Point size = new Point();
            requireActivity().getWindowManager().getDefaultDisplay().getRealSize(size);

            WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = (int) (size.x * 0.85);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT; // 高度自适应内容，完整显示
            dialog.getWindow().setAttributes(params);
        }
        return dialog;
    }

    private void showTempNodeSelector() {
        String[] items = FanUtil.getAllTempNodeNames();
        int checked = FanUtil.getCurrentTempNodeIndex();

        Dialog dialog = createRoundDialog(R.layout.dialog_temp_node);

        ListView listView = dialog.findViewById(R.id.list_temp_nodes);
        TextView btnCancel = dialog.findViewById(R.id.btn_temp_cancel);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_single_choice, items);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checked, true);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            FanUtil.setTempNode(position);
            tvTempNodeName.setText(items[position]);
            mExecutor.execute(() -> {
                FanUtil.getCurrentTemp();
                if (FanUtil.currentControlMode == FanUtil.MODE_SMART) {
                    int target = FanUtil.calcPwmByTemp(FanUtil.cacheTemp);
                    FanUtil.applySmoothPwm(target);
                }
                requireActivity().runOnUiThread(this::refreshUiFromCache);
            });
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showModeHelpDialog() {
        Dialog dialog = createRoundDialog(R.layout.dialog_mode_help);

        TextView tvContent = dialog.findViewById(R.id.tv_mode_help_content);
        TextView btnOk = dialog.findViewById(R.id.btn_mode_help_ok);

        tvContent.setText("1. 控制中心联调：同步控制中心状态，需配合相应的LSP模块才可开启狂暴模式\n\n" +
                "2. 底层覆盖控制：不需要配合模块，通过底层覆盖指令写入的方式，完全独立控制风扇，但不能与控制中心同时开启，否则会被控制中心强制覆盖\n\n" +
                "3. 智能调频模式：软件独立的智能模式，与控制中心不同，软件内可自定义温度‑转速的温控曲线，开启时默认关闭控制中心磁贴控制\n\n" +
                "4. 应用自定义：配置打开指定应用时的风扇档位，默认为智能模式且温控曲线为自定义的温控曲线\n\n" +
                "四种模式相互独立控制，互不干扰，但切换时有档位继承，且息屏停转开关在狂暴模式下不生效");

        btnOk.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
}