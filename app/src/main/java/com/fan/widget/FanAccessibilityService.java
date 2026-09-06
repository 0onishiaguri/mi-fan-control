package com.fan.widget;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class FanAccessibilityService extends AccessibilityService {

    // 日志标签
    private static final String TAG = "FanAccessibilityService";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // 配置服务信息：不监听任何事件（仅保持服务存活）
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = 0; // 不监听任何事件
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = 0;
        setServiceInfo(info);

        LogRecorder.getInstance().info(TAG, "无障碍服务已启动（用于后台保活）");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 服务无实际功能，仅在后台保活，无需处理事件
        // 但为避免日志泛滥，不做任何操作
    }

    @Override
    public void onInterrupt() {
        // 服务中断时无需特殊处理
    }
}