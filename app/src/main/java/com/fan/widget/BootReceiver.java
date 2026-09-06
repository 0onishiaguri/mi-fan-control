package com.fan.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action))) {
            // 初始化核心工具
            FanUtil.init(context.getApplicationContext());
            LogRecorder.getInstance().info("BootReceiver", "系统启动完成，正在启动风扇服务");

            // 启动后台服务
            Intent serviceIntent = new Intent(context, FanRefreshService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}