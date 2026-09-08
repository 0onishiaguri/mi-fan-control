package com.fan.widget;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;


/**
 * 信息悬浮窗服务：固定显示 档位/温度/转速/功耗/PWM
 * 支持拖动、透明度、缩放、刷新频率与横竖版样式切换，全部实时生效。
 */
public class FloatWindowService extends Service {

    public static final String ACTION_UPDATE = "com.fan.widget.FLOAT_UPDATE";
    public static final String ACTION_TOGGLE = "FAN_FLOAT_TOGGLE";

    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_ENABLED = "float_window_enabled";
    private static final String KEY_ALPHA = "float_window_alpha";          // 20-100 (%)
    private static final String KEY_SCALE = "float_window_scale";          // 50-120 (%)
    private static final String KEY_FLOAT_FIXED = "float_fixed";            // 固定位置 + 点击穿透
    private static final String KEY_INTERVAL = "float_window_interval_ms"; // 500/1000/1500/2000
    private static final String KEY_STYLE = "float_window_style";          // 0横版 1竖版
    private static final String KEY_X = "float_window_x";
    private static final String KEY_Y = "float_window_y";

    private static final int DEFAULT_ALPHA = 90;
    private static final int DEFAULT_SCALE = 80;
    private static final long DEFAULT_INTERVAL = 1000L;
    private static final int DEFAULT_STYLE = 0;

    // 悬浮窗基准尺寸（dp）
    private static final int WIDTH_H = 300;
    private static final int HEIGHT_H = 55;
    private static final int WIDTH_V = 120;
    private static final int HEIGHT_V = 200;

    // 状态标志（同进程内跨组件读取）
    private static volatile boolean sRunning = false;

    private WindowManager mWm;
    private View mFloatView;
    private WindowManager.LayoutParams mParams;
    private SharedPreferences mSp;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mRefreshTask;

    private int mCurrentStyle = DEFAULT_STYLE;
    private long mCurrentInterval = DEFAULT_INTERVAL;

    // 拖动记录
    private float mDragStartX, mDragStartY;
    private int mDragBaseX, mDragBaseY;
    private long mDownTime = 0;
    // 横竖版切换动画进行中标志（防止重入）
    private boolean mSwitching = false;

    // 单击（延迟切换）/双击（关闭悬浮窗）记录
    private long mLastTapTime = 0;
    private float mLastTapX = -1, mLastTapY = -1;
    private final Runnable mSingleTapRunnable = new Runnable() {
        @Override
        public void run() {
            toggleStyle();
        }
    };

    // PWM 读取复用 FanUtil.readPwmDuty()（两条节点路径 + 1 秒节流，避免重复 su 调用）

    // ========== 静态配置读写（供设置页 / 通知按钮共用） ==========

    public static boolean isEnabled(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isRunning() {
        return sRunning;
    }

    // 供后台采集循环读取悬浮窗自定义刷新频率
    public static long getInterval(Context context) {
        return context.getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE)
                .getLong(KEY_INTERVAL, DEFAULT_INTERVAL);
    }

    // ========== 生命周期 ==========

    @Override
    public void onCreate() {
        super.onCreate();
        mWm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mSp = getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE);
        mCurrentStyle = mSp.getInt(KEY_STYLE, DEFAULT_STYLE);
        mCurrentInterval = mSp.getLong(KEY_INTERVAL, DEFAULT_INTERVAL);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            // 设置变化：样式/透明度/缩放/频率实时生效
            applySettings();
            return START_STICKY;
        }
        if (mFloatView == null) {
            buildView();
        }
        startRefresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRefresh();
        removeView();
        sRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ========== 视图构建 ==========

    private void buildView() {
        buildView(-1, -1);
    }

    // 当前实际显示的悬浮窗数据项数量（至少 1）
    private int countShownItems() {
        int n = 0;
        for (String k : FLOAT_SHOW_KEYS) {
            if (mSp.getBoolean(k, true)) n++;
        }
        return Math.max(n, 1);
    }

    // centerX/centerY >= 0 时以该屏幕坐标为中心放置窗口（横竖版切换保持中心点不变）
    private void buildView(int centerX, int centerY) {
        int style = mSp.getInt(KEY_STYLE, DEFAULT_STYLE);
        mCurrentStyle = style;
        int layoutRes = style == 0 ? R.layout.float_window_horizontal : R.layout.float_window_vertical;
        mFloatView = LayoutInflater.from(this).inflate(layoutRes, null);

        mParams = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            mParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        mParams.format = PixelFormat.TRANSLUCENT;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        // 固定位置 + 点击穿透：窗口不接收任何触摸（不可拖动/点击，触摸穿透到下层应用）
        if (mSp.getBoolean(KEY_FLOAT_FIXED, false)) {
            mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.alpha = Math.max(0.1f, mSp.getInt(KEY_ALPHA, DEFAULT_ALPHA) / 100f);

        int screenW = mWm.getDefaultDisplay().getWidth();
        // 窗口尺寸随显示项数量自适应：竖版按行数缩放高度，横版按项数缩放宽度
        int shown = countShownItems();
        int baseW = dp2px(style == 0 ? WIDTH_H * shown / 5 : WIDTH_V);
        int baseH = dp2px(style == 0 ? HEIGHT_H : HEIGHT_V * shown / 5);
        int scalePct = Math.max(50, Math.min(120, mSp.getInt(KEY_SCALE, DEFAULT_SCALE)));
        mParams.width = (int) (baseW * scalePct / 100f);
        mParams.height = (int) (baseH * scalePct / 100f);

        mParams.x = mSp.getInt(KEY_X, screenW - baseW - dp2px(12));
        mParams.y = mSp.getInt(KEY_Y, dp2px(160));
        if (centerX >= 0) {
            mParams.x = centerX - mParams.width / 2;
            mParams.y = centerY - mParams.height / 2;
            mSp.edit().putInt(KEY_X, mParams.x).putInt(KEY_Y, mParams.y).apply();
        }

        mFloatView.setOnTouchListener(mDragTouchListener);
        mBaseLabelSize = style == 0 ? 13f : 14f;
        mBaseValueSize = 14f;
        applyTextScale();

        try {
            mWm.addView(mFloatView, mParams);
        } catch (Exception e) {
            LogRecorder.getInstance().error("FloatWindow", "悬浮窗创建失败:" + e.getMessage());
            mFloatView = null;
            return;
        }
        sRunning = true;
        applyShowConfig();
        refreshData();
        LogRecorder.getInstance().info("FloatWindow", "信息悬浮窗已显示，样式:" + (style == 0 ? "横版" : "竖版"));
    }

    private void removeView() {
        if (mFloatView != null) {
            try {
                mWm.removeView(mFloatView);
            } catch (Exception ignored) {}
            mFloatView = null;
        }
    }

    // 设置变化统一入口：样式变化走动画过渡，其余直接改参数
    private void applySettings() {
        if (mFloatView == null) {
            buildView();
            startRefresh();
            return;
        }
        // 刷新频率检查与样式无关，放在最前
        long interval = mSp.getLong(KEY_INTERVAL, DEFAULT_INTERVAL);
        if (interval != mCurrentInterval) {
            mCurrentInterval = interval;
            stopRefresh();
            startRefresh();
        }

        int style = mSp.getInt(KEY_STYLE, DEFAULT_STYLE);
        if (style != mCurrentStyle) {
            // 横竖版切换：旧窗口淡出缩小 → 中心对齐创建新窗口 → 淡入放大
            if (!mSwitching) {
                int cx = mParams.x + mParams.width / 2;
                int cy = mParams.y + mParams.height / 2;
                animateSwitch(style, cx, cy);
            }
            return; // 新视图已按 SP 配置（透明度/缩放/显示项）构建
        }

        int alphaPct = Math.max(20, Math.min(100, mSp.getInt(KEY_ALPHA, DEFAULT_ALPHA)));
        mParams.alpha = alphaPct / 100f;

        // 固定位置 + 点击穿透：开关变化时实时增删 FLAG_NOT_TOUCHABLE
        boolean fixed = mSp.getBoolean(KEY_FLOAT_FIXED, false);
        boolean nowFixed = (mParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
        if (fixed != nowFixed) {
            if (fixed) {
                mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            if (mFloatView != null) {
                mWm.updateViewLayout(mFloatView, mParams);
            }
        }

        int scalePct = Math.max(50, Math.min(120, mSp.getInt(KEY_SCALE, DEFAULT_SCALE)));
        int baseW = dp2px(style == 0 ? WIDTH_H : WIDTH_V);
        int baseH = dp2px(style == 0 ? HEIGHT_H : HEIGHT_V);
        mParams.width = (int) (baseW * scalePct / 100f);
        mParams.height = (int) (baseH * scalePct / 100f);
        if (mFloatView != null) {
            mWm.updateViewLayout(mFloatView, mParams);
        }
        applyTextScale();
        applyShowConfig();
        // 显示项数量变化时窗口尺寸自适应（左上角固定，右下角收缩/扩张）
        int shownNow = countShownItems();
        int sc = Math.max(50, Math.min(120, mSp.getInt(KEY_SCALE, DEFAULT_SCALE)));
        int bw = dp2px(style == 0 ? WIDTH_H * shownNow / 5 : WIDTH_V);
        int bh = dp2px(style == 0 ? HEIGHT_H : HEIGHT_V * shownNow / 5);
        mParams.width = bw * sc / 100;
        mParams.height = bh * sc / 100;
        if (mFloatView != null) {
            mWm.updateViewLayout(mFloatView, mParams);
        }
    }

    // ========== 刷新循环 ==========

    private void startRefresh() {
        stopRefresh();
        mRefreshTask = new Runnable() {
            @Override
            public void run() {
                refreshData();
                mHandler.postDelayed(this, mCurrentInterval);
            }
        };
        mHandler.post(mRefreshTask);
    }

    private void stopRefresh() {
        if (mRefreshTask != null) {
            mHandler.removeCallbacks(mRefreshTask);
            mRefreshTask = null;
        }
    }

    private void refreshData() {
        if (mFloatView == null) return;
        int rpm = FanUtil.cacheRpm;
        int temp = FanUtil.cacheTemp;
        setText(R.id.float_mode, FanUtil.getCurrentModeName(rpm));
        setText(R.id.float_temp, temp + "°");
        setText(R.id.float_rpm, String.valueOf(rpm));
        setText(R.id.float_power, FanUtil.readPowerText(this));
        // PWM 读后台维护的缓存，主线程循环不再执行 su
        setText(R.id.float_pwm, FanUtil.getCachedPwmDuty() + "%");
    }

    private void setText(int id, String text) {
        if (mFloatView == null) return;
        View v = mFloatView.findViewById(id);
        if (v instanceof TextView) {
            ((TextView) v).setText(text);
        }
    }

    // ========== 数据源 ==========

    // 功耗读取统一走 FanUtil.readPowerText(this)（BatteryManager 优先 + dumpsys 兜底 + 平滑 + 充电正负）

    // ========== 缩放文字 ==========
    // 基准字号（sp）：横版 label 10 / 竖版 label 12 / value 14，随缩放比例同步放大缩小
    private float mBaseLabelSize = 10f;
    private float mBaseValueSize = 14f;

    private static final int[] LABEL_IDS = {
            R.id.float_lb_mode, R.id.float_lb_temp, R.id.float_lb_rpm,
            R.id.float_lb_power, R.id.float_lb_pwm
    };
    private static final int[] VALUE_IDS = {
            R.id.float_mode, R.id.float_temp, R.id.float_rpm,
            R.id.float_power, R.id.float_pwm
    };

    // 悬浮窗显示项配置（与 LABEL/VALUE 顺序一致：0档位 1温度 2转速 3功耗 4PWM）
    private static final String[] FLOAT_SHOW_KEYS = {
            "float_show_mode", "float_show_temp", "float_show_rpm", "float_show_power", "float_show_pwm"
    };
    private static final int[] FLOAT_ROW_IDS = {
            R.id.row_mode, R.id.row_temp, R.id.row_rpm, R.id.row_power, R.id.row_pwm
    };

    // 按用户配置显隐数据行；至少保留一项，全关时恢复默认全显
    private void applyShowConfig() {
        if (mFloatView == null) return;
        int shown = 0;
        for (int i = 0; i < FLOAT_SHOW_KEYS.length; i++) {
            boolean show = mSp.getBoolean(FLOAT_SHOW_KEYS[i], true);
            if (show) shown++;
            View row = mFloatView.findViewById(FLOAT_ROW_IDS[i]);
            if (row != null) row.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (shown == 0) {
            for (int id : FLOAT_ROW_IDS) {
                View v = mFloatView.findViewById(id);
                if (v != null) v.setVisibility(View.VISIBLE);
            }
            mSp.edit().putBoolean(FLOAT_SHOW_KEYS[0], true).putBoolean(FLOAT_SHOW_KEYS[1], true)
                    .putBoolean(FLOAT_SHOW_KEYS[2], true).putBoolean(FLOAT_SHOW_KEYS[3], true)
                    .putBoolean(FLOAT_SHOW_KEYS[4], true).apply();
        }
    }

    private void applyTextScale() {
        if (mFloatView == null) return;
        float k = Math.max(50, Math.min(120, mSp.getInt(KEY_SCALE, DEFAULT_SCALE))) / 100f;
        for (int id : LABEL_IDS) {
            TextView tv = mFloatView.findViewById(id);
            if (tv != null) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mBaseLabelSize * k);
        }
        for (int id : VALUE_IDS) {
            TextView tv = mFloatView.findViewById(id);
            if (tv != null) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, mBaseValueSize * k);
        }
        // 竖版：数值左间距随缩放同步收缩，最小缩放时数值仍能完整显示
        if (mCurrentStyle == 1) {
            int margin = dp2px(20 * k);
            for (int id : VALUE_IDS) {
                TextView tv = mFloatView.findViewById(id);
                if (tv == null) continue;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
                if (lp != null && lp.leftMargin != margin) {
                    lp.leftMargin = margin;
                    tv.setLayoutParams(lp);
                }
            }
        }
    }

    // ========== 横竖版切换（点击 + 动画过渡） ==========

    // 点击悬浮窗：横版 ↔ 竖版 平滑切换，保持窗口中心位置不变
    private void toggleStyle() {
        if (mSwitching || mFloatView == null) return;
        int newStyle = mCurrentStyle == 0 ? 1 : 0;
        mSp.edit().putInt(KEY_STYLE, newStyle).apply();
        int centerX = mParams.x + mParams.width / 2;
        int centerY = mParams.y + mParams.height / 2;
        animateSwitch(newStyle, centerX, centerY);
    }

    // 过渡动画：旧窗口 150ms 淡出缩小 → 中心对齐建新窗口 → 150ms 淡入放大
    private void animateSwitch(final int newStyle, final int centerX, final int centerY) {
        final View oldView = mFloatView;
        if (oldView == null) {
            mCurrentStyle = newStyle;
            buildView(centerX, centerY);
            return;
        }
        mSwitching = true;
        oldView.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        removeView();
                        mCurrentStyle = newStyle;
                        buildView(centerX, centerY);
                        mSwitching = false;
                        if (mFloatView != null) {
                            mFloatView.setAlpha(0f);
                            mFloatView.setScaleX(0.75f);
                            mFloatView.setScaleY(0.75f);
                            mFloatView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
                        }
                    }
                }).start();
    }

    // 双击关闭信息悬浮窗
    private void closeFloatWindow() {
        mHandler.removeCallbacks(mSingleTapRunnable);
        mHandler.removeCallbacks(mRefreshTask);
        mRefreshTask = null;
        removeView();
        sRunning = false;
        FloatWindowService.setEnabled(this, false);
        LogRecorder.getInstance().info("FloatWindow", "双击关闭信息悬浮窗");
        try {
            stopSelf();
        } catch (Exception ignored) {}
    }

    // ========== 拖动 ==========

    private final View.OnTouchListener mDragTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (mSwitching) return true; // 切换动画期间不响应
                    mDragStartX = event.getRawX();
                    mDragStartY = event.getRawY();
                    mDragBaseX = mParams.x;
                    mDragBaseY = mParams.y;
                    mDownTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mSwitching) return true;
                    mParams.x = mDragBaseX + (int) (event.getRawX() - mDragStartX);
                    mParams.y = mDragBaseY + (int) (event.getRawY() - mDragStartY);
                    try {
                        mWm.updateViewLayout(mFloatView, mParams);
                    } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mSwitching) return true;
                    // 位移小且时间短 = 点击；否则视为拖动结束，保存位置
                    boolean isTap = Math.abs(event.getRawX() - mDragStartX) < dp2px(8)
                            && Math.abs(event.getRawY() - mDragStartY) < dp2px(8)
                            && (System.currentTimeMillis() - mDownTime) < 400;
                    if (isTap) {
                        // 双击（300ms 内再次点击同一位置）→ 关闭悬浮窗；单击 → 延迟 300ms 切换横竖版
                        long now = System.currentTimeMillis();
                        boolean doubleTap = now - mLastTapTime < 300
                                && Math.abs(event.getRawX() - mLastTapX) < dp2px(24)
                                && Math.abs(event.getRawY() - mLastTapY) < dp2px(24);
                        if (doubleTap) {
                            mHandler.removeCallbacks(mSingleTapRunnable);
                            closeFloatWindow();
                        } else {
                            mLastTapTime = now;
                            mLastTapX = event.getRawX();
                            mLastTapY = event.getRawY();
                            mHandler.removeCallbacks(mSingleTapRunnable);
                            mHandler.postDelayed(mSingleTapRunnable, 300);
                        }
                    } else {
                        mHandler.removeCallbacks(mSingleTapRunnable);
                        mSp.edit().putInt(KEY_X, mParams.x).putInt(KEY_Y, mParams.y).apply();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mHandler.removeCallbacks(mSingleTapRunnable);
                    return true;
                default:
                    return false;
            }
        }
    };

    private int dp2px(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
