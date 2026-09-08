package com.fan.widget;

import android.app.Application;
import android.content.Intent;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class FanApp extends Application {
    public static volatile String globalDeviceModel = "";
    private static final String BINARY_NAME = "fix_speed";

    @Override
    public void onCreate() {
        super.onCreate();

        FanUtil.init(this);
        LogRecorder.getInstance().info("AppLifecycle", "应用进程冷启动");

        // 拷贝二进制文件并赋予执行权限
        copyFixBinary();

        // 启动二进制守护进程（若未运行）
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 等待系统完全初始化
                FanSpeedSimulator.getInstance().ensureBinaryRunning(FanUtil.isMaxModel());
            } catch (Exception e) {
                LogRecorder.getInstance().error("AppLifecycle", "启动二进制守护进程失败: " + e.getMessage());
            }
        }).start();

        // 异步读取设备型号
        new Thread(() -> {
            try {
                String rawModel = FanUtil.getDeviceModelName();
                globalDeviceModel = rawModel;
                LogRecorder.getInstance().info("AppLifecycle", "进程内机型加载完成：" + rawModel);
            } catch (Exception e) {
                globalDeviceModel = "读取失败";
                LogRecorder.getInstance().error("AppLifecycle", "读取设备型号异常:" + e.getMessage());
            }
        }).start();

        // 恢复悬浮窗（开机/冷启后若开关已开且权限已授）
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                if (FloatWindowService.isEnabled(getApplicationContext())
                        && Settings.canDrawOverlays(getApplicationContext())) {
                    startService(new Intent(getApplicationContext(), FloatWindowService.class));
                }
            } catch (Exception e) {
                LogRecorder.getInstance().error("AppLifecycle", "恢复悬浮窗异常: " + e.getMessage());
            }
        }).start();
    }

    private void copyFixBinary() {
        try {
            File destFile = new File(getFilesDir(), BINARY_NAME);
            if (destFile.exists()) {
                destFile.setExecutable(true, false);
                LogRecorder.getInstance().info("BinaryInit", "二进制已存在，权限已检查");
                return;
            }

            try (InputStream in = getAssets().open(BINARY_NAME);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }

            destFile.setExecutable(true, false);
            destFile.setReadable(true, false);
            destFile.setWritable(true, false);
            LogRecorder.getInstance().info("BinaryInit", "修复二进制已复制到: " + destFile.getAbsolutePath());
        } catch (Exception e) {
            LogRecorder.getInstance().error("BinaryInit", "复制修复二进制失败: " + e.getMessage());
        }
    }
}