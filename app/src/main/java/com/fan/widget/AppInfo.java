package com.fan.widget;

import android.graphics.drawable.Drawable;

public class AppInfo {
    public String appName;
    public String packageName;
    public Drawable icon;
    public int fanMode; // 0=默认(智能) 1=静谧 2=高速 4=狂暴
    public boolean isSystemApp;

    // 无参构造（默认）
    public AppInfo() {
    }

    @Override
    public String toString() {
        return "AppInfo{" +
                "appName='" + appName + '\'' +
                ", packageName='" + packageName + '\'' +
                ", fanMode=" + fanMode +
                ", isSystemApp=" + isSystemApp +
                '}';
    }
}