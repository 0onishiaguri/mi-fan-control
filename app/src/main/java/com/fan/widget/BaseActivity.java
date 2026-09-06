package com.fan.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;

public class BaseActivity extends AppCompatActivity {
    private static final String SP_NAME = "fan_widget_config";
    private static final String KEY_BG_PATH = "custom_bg_path";
    private static final int MAX_BG_SIZE = 2048;

    // 设定默认背景图的资源 ID
    private static final int DEFAULT_BG_RES_ID = R.drawable.background_image;

    // 自定义背景解码结果缓存（进程级，避免每次进入页面重复解码）
    private static final java.util.Map<String, Drawable> sBgCache = new java.util.HashMap<>();

    private FrameLayout mContentLayout;
    private ImageView mBgImageView;
    private String mCurrentBgPath = null;
    private boolean mIsBgReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setImmersiveStatusBar();

        new Handler().post(() -> {
            setupBackgroundView();
            applyCustomBackground();
        });
    }

    private void setupBackgroundView() {
        mContentLayout = findViewById(android.R.id.content);
        if (mContentLayout == null) {
            return;
        }

        if (mBgImageView == null) {
            mBgImageView = new ImageView(this);
            mBgImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mBgImageView.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            mBgImageView.setVisibility(View.GONE);
            mContentLayout.addView(mBgImageView, 0);
        }
        mIsBgReady = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mIsBgReady) {
            applyCustomBackground();
        }
    }

    private void setImmersiveStatusBar() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        applyStatusBarTextColor();
    }

    private void applyStatusBarTextColor() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                boolean isLightTheme = nightMode == Configuration.UI_MODE_NIGHT_NO;
                controller.setAppearanceLightStatusBars(isLightTheme);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean isLightTheme = nightMode == Configuration.UI_MODE_NIGHT_NO;
            int flags = window.getDecorView().getSystemUiVisibility();
            if (isLightTheme) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    protected void applyCustomBackground() {
        if (!mIsBgReady || mContentLayout == null || mBgImageView == null) {
            return;
        }

        SharedPreferences sp = getSharedPreferences(SP_NAME, MODE_PRIVATE);
        String bgPath = sp.getString(KEY_BG_PATH, "");
        int defaultColor = ContextCompat.getColor(this, R.color.bg_primary);

        // 【关键修改】如果路径为空（用户点击了“恢复默认”）
        if (bgPath.isEmpty()) {
            // 如果当前不是默认背景，或者默认背景还未加载过
            if (!"DEFAULT".equals(mCurrentBgPath)) {
                Drawable defaultDrawable = null;
                try {
                    // 尝试获取 res/drawable 下的默认背景图
                    defaultDrawable = ContextCompat.getDrawable(this, DEFAULT_BG_RES_ID);
                } catch (Exception e) {
                    defaultDrawable = null;
                }

                if (defaultDrawable != null) {
                    // 加载内置默认图片为背景
                    mBgImageView.setImageDrawable(defaultDrawable);
                    mBgImageView.setVisibility(View.VISIBLE);
                    mContentLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    mCurrentBgPath = "DEFAULT";
                } else {
                    // 图片未找到，降级为纯色背景
                    mBgImageView.setVisibility(View.GONE);
                    mContentLayout.setBackgroundColor(defaultColor);
                    mCurrentBgPath = "";
                }
            }
            return;
        }

        // 如果路径和当前路径相同（且不是默认背景标记），跳过
        if (bgPath.equals(mCurrentBgPath)) {
            return;
        }

        // 加载用户自定义的新背景（后台解码，避免主线程卡顿）
        final String targetPath = bgPath;
        Drawable cached = sBgCache.get(targetPath);
        if (cached != null) {
            applyBackgroundDrawable(cached, targetPath);
            return;
        }
        new Thread(() -> {
            Drawable drawable = loadBackgroundDrawable(targetPath);
            runOnUiThread(() -> {
                // 路径可能已再次变化，仅当仍是目标路径时应用
                if (!targetPath.equals(sp.getString(KEY_BG_PATH, ""))) return;
                if (drawable != null) {
                    sBgCache.put(targetPath, drawable);
                    applyBackgroundDrawable(drawable, targetPath);
                } else {
                    // 加载失败，降级为默认纯色
                    mBgImageView.setVisibility(View.GONE);
                    mContentLayout.setBackgroundColor(defaultColor);
                    sp.edit().remove(KEY_BG_PATH).apply();
                    mCurrentBgPath = "";
                }
            });
        }).start();
    }

    private void applyBackgroundDrawable(Drawable drawable, String path) {
        mBgImageView.setImageDrawable(drawable);
        mBgImageView.setVisibility(View.VISIBLE);
        mContentLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        mCurrentBgPath = path;
    }

    private Drawable loadBackgroundDrawable(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int sampleSize = 1;
            while (options.outWidth / sampleSize > MAX_BG_SIZE || options.outHeight / sampleSize > MAX_BG_SIZE) {
                sampleSize *= 2;
            }
            options.inSampleSize = sampleSize;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) {
                return null;
            }
            return new BitmapDrawable(getResources(), bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    public void refreshBackground() {
        applyCustomBackground();
    }

    public static void saveBackgroundPath(Context context, String path) {
        SharedPreferences sp = context.getSharedPreferences(SP_NAME, MODE_PRIVATE);
        sp.edit().putString(KEY_BG_PATH, path).apply();
    }
}