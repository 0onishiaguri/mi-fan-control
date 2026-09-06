package com.fan.widget;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class FanSpeedSimulator {
    private static volatile FanSpeedSimulator sInstance;

    private static final String NODE_ULTRA = "/sys/devices/platform/soc/soc:xiaomi_fan";
    private static final String NODE_MAX = "/sys/devices/platform/odm/odm:xiaomi_fan";

    private FanSpeedSimulator() {}

    public static FanSpeedSimulator getInstance() {
        if (sInstance == null) {
            synchronized (FanSpeedSimulator.class) {
                if (sInstance == null) {
                    sInstance = new FanSpeedSimulator();
                }
            }
        }
        return sInstance;
    }

    /**
     * 获取真实硬件转速
     */
    public int getRealSpeed(boolean isMax) {
        String nodePath = isMax ? NODE_MAX : NODE_ULTRA;
        String filePath = nodePath + "/real_speed";
        // 复用 FanUtil 统一 SU 执行入口（含超时与失败熔断）
        String result = FanUtil.runSuCommand("cat " + filePath);
        if (result != null && !result.isEmpty()) {
            try {
                return Integer.parseInt(result.trim());
            } catch (NumberFormatException e) {
                LogRecorder.getInstance().warn("FanSpeedSim", "解析转速失败: " + e.getMessage());
            }
        }
        return -1;
    }

    /**
     * 确保只有一个 fix_speed 守护进程在运行
     * 步骤：1) 杀死所有已有的 fix_speed 进程  2) 启动一个新的
     */
    public synchronized void ensureBinaryRunning(boolean isMax) {
        // 1. 清理所有已存在的 fix_speed 进程
        killAllFixSpeed();

        // 2. 启动新的守护进程
        startBinaryDaemon();

        // 3. 等待并确认启动成功
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}
        if (isBinaryRunning()) {
            LogRecorder.getInstance().info("FanSpeedSim", "二进制守护进程启动成功，当前仅有一个进程");
        } else {
            LogRecorder.getInstance().warn("FanSpeedSim", "二进制守护进程启动失败");
        }
    }

    /**
     * 杀死所有 fix_speed 进程
     */
    private void killAllFixSpeed() {
        try {
            // 使用 pkill 强制终止所有同名进程
            String cmd = "pkill -f fix_speed";
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            process.waitFor(2, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            LogRecorder.getInstance().info("FanSpeedSim", "清理旧进程，pkill 退出码: " + exitCode);
            // 等待进程完全退出
            Thread.sleep(300);
        } catch (Exception e) {
            LogRecorder.getInstance().warn("FanSpeedSim", "清理旧进程异常: " + e.getMessage());
        }
    }

    /**
     * 检查 fix_speed 进程是否存在
     */
    private boolean isBinaryRunning() {
        try {
            Process pidProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", "pidof fix_speed"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(pidProcess.getInputStream()));
            String line = reader.readLine();
            pidProcess.waitFor(1, TimeUnit.SECONDS);
            reader.close();
            return line != null && !line.isEmpty();
        } catch (Exception e) {
            LogRecorder.getInstance().warn("FanSpeedSim", "检查PID异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 使用 nohup 在后台启动二进制守护进程
     */
    private void startBinaryDaemon() {
        Context context = FanUtil.sAppContext;
        if (context == null) {
            LogRecorder.getInstance().warn("FanSpeedSim", "Context 不可用，无法启动二进制");
            return;
        }
        File binary = new File(context.getFilesDir(), "fix_speed");
        if (!binary.exists() || !binary.canExecute()) {
            LogRecorder.getInstance().warn("FanSpeedSim", "二进制不存在或无执行权限");
            return;
        }

        try {
            String cmd = "nohup " + binary.getAbsolutePath() + " > /dev/null 2>&1 &";
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            LogRecorder.getInstance().info("FanSpeedSim", "已执行 nohup 启动命令");
        } catch (Exception e) {
            LogRecorder.getInstance().error("FanSpeedSim", "启动二进制异常: " + e.getMessage());
        }
    }
}