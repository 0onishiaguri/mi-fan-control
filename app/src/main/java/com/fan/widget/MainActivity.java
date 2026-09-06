package com.fan.widget;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.lang.reflect.Method;
import java.util.List;

public class MainActivity extends BaseActivity {

    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_HIDE_RECENTS = "exclude_from_recents";
    private static final String KEY_WALLPAPER_BLUR = "wallpaper_blur_effect";

    private static final String TAG_BLUR_CARD = "blur_card";

    private static Method sSetBackgroundBlurRadiusMethod;

    static {
        try {
            sSetBackgroundBlurRadiusMethod = View.class.getMethod("setBackgroundBlurRadius", int.class);
        } catch (NoSuchMethodException ignored) {
            // 低版本不支持
        }
    }

    private HomeFragment homeFragment;
    private SettingsFragment settingsFragment;
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNav;
    private SharedPreferences mSp;
    private View rootLayout;

    private boolean mHideRecents;
    private boolean mEnableWallpaperBlur;

    private int currentNavItemId = R.id.nav_home;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FanUtil.init(this);
        mSp = getSharedPreferences(SP_CONFIG, MODE_PRIVATE);
        mHideRecents = mSp.getBoolean(KEY_HIDE_RECENTS, false);
        mEnableWallpaperBlur = mSp.getBoolean(KEY_WALLPAPER_BLUR, false);

        rootLayout = findViewById(android.R.id.content);
        viewPager = findViewById(R.id.container_main);
        bottomNav = findViewById(R.id.bottom_nav);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            bottomNav.setOnApplyWindowInsetsListener((v, insets) -> insets);
        }

        applyHideTaskFromRecents(mHideRecents);
        applyWallpaperGlassEffect(mEnableWallpaperBlur);

        setupViewPager(savedInstanceState);
        setupBottomNav();
        setupBottomNavInsets();
        setupBackPressHandling();
    }

    // ==================== 返回键处理（已移除详情Fragment判断） ====================

    private void setupBackPressHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 已经改成独立Activity，不需要再检查是否显示详情Fragment
                if (viewPager.getCurrentItem() != 0) {
                    viewPager.setCurrentItem(0, true);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }

    // ==================== ViewPager2 设置 ====================

    private void setupViewPager(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag("home");
            settingsFragment = (SettingsFragment) getSupportFragmentManager().findFragmentByTag("settings");
        }

        FragmentStateAdapter adapter = new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                if (position == 0) {
                    if (homeFragment == null) homeFragment = new HomeFragment();
                    return homeFragment;
                } else {
                    if (settingsFragment == null) settingsFragment = new SettingsFragment();
                    return settingsFragment;
                }
            }

            @Override
            public int getItemCount() {
                return 2;
            }
        };
        viewPager.setAdapter(adapter);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                int navId = (position == 0) ? R.id.nav_home : R.id.nav_settings;
                if (bottomNav.getSelectedItemId() != navId) {
                    bottomNav.setSelectedItemId(navId);
                }
                currentNavItemId = navId;
                applyWallpaperGlassEffect(mEnableWallpaperBlur);
            }
        });
    }

    // ==================== 底部导航设置（已移除详情Fragment判断） ====================

    private void setupBottomNav() {
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home && currentNavItemId != R.id.nav_home) {
                viewPager.setCurrentItem(0, true);
                currentNavItemId = R.id.nav_home;
                return true;
            }
            if (itemId == R.id.nav_settings && currentNavItemId != R.id.nav_settings) {
                viewPager.setCurrentItem(1, true);
                currentNavItemId = R.id.nav_settings;
                return true;
            }
            return false;
        });
    }
    // ==================== 壁纸背景（模糊） ====================

    @SuppressLint("MissingPermission")
    public void applyWallpaperGlassEffect(boolean enable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enable) {
                setViewBlurRadius(bottomNav, 24);
                applyBlurToCurrentVisibleFragment(enable);
            } else {
                setViewBlurRadius(bottomNav, 0);
                applyBlurToCurrentVisibleFragment(false);
            }
        } else {
            applyBlurToCurrentVisibleFragment(enable);
        }
    }

    private void applyBlurToCurrentVisibleFragment(boolean enable) {
        Fragment current = getCurrentMainFragment();
        if (current != null && current.getView() != null) {
            setBlurForCards(current.getView(), enable);
        }
    }

    private Fragment getCurrentMainFragment() {
        int position = viewPager.getCurrentItem();
        if (position == 0) return homeFragment;
        else return settingsFragment;
    }

    private void setBlurForCards(View view, boolean enable) {
        if (view == null) return;
        if (TAG_BLUR_CARD.equals(view.getTag())) {
            if (enable) {
                view.setBackgroundResource(R.drawable.card_background_blur);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setViewBlurRadius(view, 20);
                }
            } else {
                view.setBackgroundResource(R.drawable.card_background);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setViewBlurRadius(view, 0);
                }
            }
            // 卡片内部不存在嵌套卡片，处理完直接返回，避免无谓递归整棵子树
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setBlurForCards(group.getChildAt(i), enable);
            }
        }
    }

    private void setViewBlurRadius(View view, int radius) {
        if (view == null || sSetBackgroundBlurRadiusMethod == null) return;
        try {
            sSetBackgroundBlurRadiusMethod.invoke(view, radius);
        } catch (Exception ignored) {}
    }

    // ==================== 最近任务隐藏 ====================

    public void applyHideTaskFromRecents(boolean exclude) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return;
        List<ActivityManager.AppTask> taskList = am.getAppTasks();
        if (taskList != null && !taskList.isEmpty()) {
            taskList.get(0).setExcludeFromRecents(exclude);
        }
    }

    // ==================== 底部导航边距 ====================

    private void setupBottomNavInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                int navBarHeight = insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom;
                updateBottomLayout(navBarHeight);
                return insets;
            });
        } else {
            bottomNav.post(() -> {
                int navBarHeight = getNavigationBarHeightFallback();
                updateBottomLayout(navBarHeight);
            });
        }
    }

    private void updateBottomLayout(int navBarHeight) {
        FrameLayout.LayoutParams navParams = (FrameLayout.LayoutParams) bottomNav.getLayoutParams();
        navParams.bottomMargin = FanUtil.dp2px(this, 25) + navBarHeight;
        bottomNav.setLayoutParams(navParams);
    }

    private int getNavigationBarHeightFallback() {
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener(null);
        }
    }
}