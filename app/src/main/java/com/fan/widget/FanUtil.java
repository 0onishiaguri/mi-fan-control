package com.fan.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.os.BatteryManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class FanUtil {
    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LOW_LEVEL = 1;
    public static final int MODE_SMART = 2;
    public static final int MODE_APP_CUSTOM = 3;

    public static volatile int cacheRpm = 0;
    public static volatile int cacheTemp = 0;
    public static volatile boolean sIsCharging = false;
    public static volatile int currentControlMode = MODE_SYSTEM;
    public static volatile int targetPwmDuty = 0;
    public static volatile int currentTargetLevel = 1;
    public static volatile long totalRunTimeMs = 0;

    public static volatile float currentSmoothPwm = 0f;
    // 步进相关
    public static final int STEP_MODE_FIXED = 0;
    public static final int STEP_MODE_INTERVAL = 1;
    public static final String KEY_STEP_MODE = "step_mode";
    public static final String KEY_FIXED_STEP = "fixed_step";
    public static final String KEY_INTERVAL_LIST = "interval_list";

    public static class Interval {
        public float lowTemp;
        public float highTemp;
        public float step;
        public Interval(float low, float high, float step) {
            this.lowTemp = low;
            this.highTemp = high;
            this.step = step;
        }
    }

    private static int sStepMode = STEP_MODE_FIXED;
    private static float sFixedStep = 5f;
    private static List<Interval> sIntervalList = new ArrayList<>();

    private static final long SMART_PWM_LOG_INTERVAL = 5 * 60 * 1000L;
    private static long sLastSmartPwmLogTime = 0;

    // ========== 温度节点 ==========
    private static final String[] TEMP_NODE_NAMES = {
            "核心温度(thermal_zone23)",
            "外壳温度(thermal_zone68)",
            "电池温度(dumpsys battery)",
            "充电IC温度(thermal_zone71)",
            "闪存温度(thermal_zone62)",
            "运存温度(thermal_zone46)",
            "大核温度(thermal_zone13)",
            "中核温度(thermal_zone20)"
    };
    private static final String[] TEMP_NODE_CMDS = {
            "echo $(($(cat /sys/class/thermal/thermal_zone23/temp) / 1000))",
            "echo $(($(cat /sys/class/thermal/thermal_zone68/temp) / 1000))",
            "dumpsys battery | grep temperature | awk '{printf \"%d\", $2/10}'",
            "echo $(($(cat /sys/class/thermal/thermal_zone71/temp) / 1000))",
            "echo $(($(cat /sys/class/thermal/thermal_zone62/temp) / 1000))",
            "echo $(($(cat /sys/class/thermal/thermal_zone46/temp) / 1000))",
            "echo $(($(cat /sys/class/thermal/thermal_zone13/temp) / 1000))",
            "echo $(($(cat /sys/class/thermal/thermal_zone20/temp) / 1000))"
    };
    private static int sCurrentTempNode = 0;

    // 智能温控曲线
    public static final List<PointF> DEFAULT_SMART_CURVE = new ArrayList<PointF>() {{
        add(new PointF(30, 0));
        add(new PointF(35, 30));
        add(new PointF(40, 70));
        add(new PointF(45, 80));
        add(new PointF(50, 90));
        add(new PointF(55, 99));
        add(new PointF(60, 99));
        add(new PointF(90, 99));
    }};
    public static volatile List<PointF> smartCurvePoints = getDefaultCurve();

    public static Context sAppContext;
    private static final String SP_CONFIG = "fan_widget_config";
    private static final String KEY_CONTROL_MODE = "control_mode";
    private static final String KEY_SMART_CURVE = "smart_curve_points";
    private static final String KEY_TEMP_NODE_INDEX = "temp_node_index";

    private static final String TARGET_LEVEL_PATH_ULTRA = "/sys/devices/platform/soc/soc:xiaomi_fan/target_level";
    private static final String PWM_DUTY_PATH_ULTRA = "/sys/devices/platform/soc/soc:xiaomi_fan/pwm_duty";
    private static final String TARGET_LEVEL_PATH_MAX = "/sys/devices/platform/odm/odm:xiaomi_fan/target_level";
    private static final String PWM_DUTY_PATH_MAX = "/sys/devices/platform/odm/odm:xiaomi_fan/pwm_duty";
    private static final String MARKET_NAME_CMD = "getprop ro.product.marketname";
    private static final String FAN_MODE_CMD = "settings get system fan_mode";

    private static String sCachedModel = null;
    private static boolean sIsMaxModel = false;

    // 充电/息屏备份
    private static boolean sLastIsCharging = false;
    private static int sOriginalFanMode = -1;
    private static int sOriginalTargetLevel = -1;
    private static int sOriginalAppLevel = -1;
    private static boolean sWasFanOffBeforeCharge = false;
    private static boolean sWasFanOffBeforeScreenOff = false;
    private static int sScreenOffBackupLevel = -1;
    private static int sScreenOffBackupSystemMode = -1;
    private static int sScreenOffBackupPwm = -1; // 用于智能模式备份

    // Root熔断
    private static final int SU_FAIL_THRESHOLD = 10;
    private static final long FUSE_DURATION_MS = 5000;
    private static int sSuFailCount = 0;
    private static long sFuseStartTime = 0;

    // ========== 初始化 ==========
    private static volatile boolean sInitialized = false;

    public static void init(Context context) {
        if (sInitialized) return; // 幂等：Application/Activity/Service/Boot 多处入口只初始化一次
        sAppContext = context.getApplicationContext();
        sIsMaxModel = getDeviceModelName().toLowerCase().contains("k90 max") ||
                getDeviceModelName().toLowerCase().contains("k90max");

        SharedPreferences sp = getSp();
        int savedNode = sp.getInt(KEY_TEMP_NODE_INDEX, 0);
        sCurrentTempNode = savedNode;

        restoreConfigFromSp();
        loadStepConfig(sp);
        LogRecorder.getInstance().info("FanInit", "风扇控制模块初始化完成");
        LogRecorder.getInstance().info("FanInit", "当前控制模式: " + getModeNameById(currentControlMode));
        LogRecorder.getInstance().info("FanInit", "当前温度节点: " + TEMP_NODE_NAMES[sCurrentTempNode]);
        sInitialized = true;
    }

    // ========== 步进配置加载与保存 ==========
    public static void loadStepConfig(SharedPreferences sp) {
        sStepMode = sp.getInt(KEY_STEP_MODE, STEP_MODE_FIXED);
        sFixedStep = sp.getInt(KEY_FIXED_STEP, 5);
        String intervalStr = sp.getString(KEY_INTERVAL_LIST, "");
        sIntervalList = parseIntervalList(intervalStr);
    }

    public static void saveStepConfig(SharedPreferences sp, int mode, int fixedStep, List<Interval> intervals) {
        String intervalStr = serializeIntervalList(intervals);
        sp.edit()
                .putInt(KEY_STEP_MODE, mode)
                .putInt(KEY_FIXED_STEP, fixedStep)
                .putString(KEY_INTERVAL_LIST, intervalStr)
                .apply();
        sStepMode = mode;
        sFixedStep = fixedStep;
        sIntervalList = intervals;
    }

    public static List<Interval> parseIntervalList(String str) {
        List<Interval> list = new ArrayList<>();
        if (str == null || str.isEmpty()) return list;
        for (String part : str.split(";")) {
            String[] parts = part.split(",");
            if (parts.length == 3) {
                try {
                    float low = Float.parseFloat(parts[0]);
                    float high = Float.parseFloat(parts[1]);
                    float step = Float.parseFloat(parts[2]);
                    if (low < high && step >= 1 && step <= 20) {
                        list.add(new Interval(low, high, step));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        list.sort((a, b) -> Float.compare(a.lowTemp, b.lowTemp));
        return list;
    }

    public static String serializeIntervalList(List<Interval> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            Interval inv = list.get(i);
            sb.append((int) inv.lowTemp).append(",")
                    .append((int) inv.highTemp).append(",")
                    .append((int) inv.step);
            if (i < list.size() - 1) sb.append(";");
        }
        return sb.toString();
    }

    public static int getCurrentStep(int currentTemp) {
        if (sStepMode == STEP_MODE_FIXED) {
            return Math.round(sFixedStep);
        } else {
            for (Interval inv : sIntervalList) {
                if (currentTemp >= inv.lowTemp && currentTemp <= inv.highTemp) {
                    return Math.round(inv.step);
                }
            }
            if (!sIntervalList.isEmpty()) {
                return Math.round(sIntervalList.get(0).step);
            } else {
                return 5;
            }
        }
    }

    // ========== 持久化 ==========
    private static SharedPreferences getSp() {
        return sAppContext.getSharedPreferences(SP_CONFIG, Context.MODE_PRIVATE);
    }

    public static void saveControlMode(int mode) {
        getSp().edit().putInt(KEY_CONTROL_MODE, mode).apply();
    }

    public static void saveSmartCurve(List<PointF> points) {
        if (points == null || points.size() < 2) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            PointF p = points.get(i);
            sb.append(p.x).append(",").append(p.y);
            if (i < points.size() - 1) sb.append(";");
        }
        getSp().edit().putString(KEY_SMART_CURVE, sb.toString()).apply();
        smartCurvePoints = new ArrayList<>(points);
        LogRecorder.getInstance().info("FanConfig", "自定义温控曲线已保存，节点数: " + points.size());
    }

    private static void restoreConfigFromSp() {
        SharedPreferences sp = getSp();
        int savedMode = sp.getInt(KEY_CONTROL_MODE, MODE_SYSTEM);
        currentControlMode = savedMode;
        String curveStr = sp.getString(KEY_SMART_CURVE, "");
        if (!curveStr.isEmpty()) {
            try {
                List<PointF> points = new ArrayList<>();
                String[] pointArr = curveStr.split(";");
                for (String s : pointArr) {
                    String[] xy = s.split(",");
                    if (xy.length == 2) {
                        points.add(new PointF(Float.parseFloat(xy[0]), Float.parseFloat(xy[1])));
                    }
                }
                if (points.size() >= 2) {
                    smartCurvePoints = points;
                }
            } catch (Exception e) {
                smartCurvePoints = getDefaultCurve();
                LogRecorder.getInstance().warn("FanConfig", "温控曲线解析失败，已恢复默认");
            }
        }
        if (savedMode != MODE_SYSTEM) {
            systemSetFanEnable(false);
            LogRecorder.getInstance().info("FanInit", "重启后已重新接管风扇控制");
        }
    }

    // ========== 温度节点 ==========
    public static void setTempNode(int index) {
        if (index >= 0 && index < TEMP_NODE_NAMES.length) {
            sCurrentTempNode = index;
            sLastTempReadTime = 0; // 切换节点后立即重读，清缓存
            getSp().edit().putInt(KEY_TEMP_NODE_INDEX, index).apply();
            LogRecorder.getInstance().info("TempNode", "切换温度读取节点：" + TEMP_NODE_NAMES[index]);
        }
    }

    public static String getCurrentTempNodeName() {
        return TEMP_NODE_NAMES[sCurrentTempNode];
    }

    public static int getCurrentTempNodeIndex() {
        return sCurrentTempNode;
    }

    public static String[] getAllTempNodeNames() {
        return TEMP_NODE_NAMES.clone();
    }

    // 温度短缓存：2 秒内复用上次读取结果，减少高频 su 进程创建（温度传感器变化缓慢）
    private static final long TEMP_READ_CACHE_MS = 2000;
    private static long sLastTempReadTime = 0;
    private static int sLastTempValue = 0;

    public static int getCurrentTemp() {
        long now = System.currentTimeMillis();
        if (sLastTempReadTime != 0 && now - sLastTempReadTime < TEMP_READ_CACHE_MS) {
            return sLastTempValue;
        }
        try {
            String raw = runSuCommand(TEMP_NODE_CMDS[sCurrentTempNode]);
            if (raw.isEmpty()) return 0;
            int temp = Integer.parseInt(raw.trim());
            cacheTemp = temp;
            sLastTempValue = temp;
            sLastTempReadTime = now;
            return temp;
        } catch (Exception e) {
            LogRecorder.getInstance().warn("TempRead", "读取温度节点失败:" + TEMP_NODE_NAMES[sCurrentTempNode]);
            return 0;
        }
    }

    // ========== 转速获取 ==========
    // 哨兵值 2：二进制守护进程未正常更新转速节点（风扇关闭/节点异常/通信失败）
    // 视为停转处理，避免把 "2" 当作真实转速污染主页/通知/悬浮窗显示与模式判断
    private static long sLastRpmInvalidWarnTime = 0;
    private static final long RPM_INVALID_WARN_INTERVAL_MS = 60 * 1000L;

    public static int getRealSpeed() {
        boolean isMax = isMaxModel();
        int rpm = FanSpeedSimulator.getInstance().getRealSpeed(isMax);
        if (rpm == 2) {
            // 无效哨兵值：按停转处理；日志 60 秒节流，避免每轮轮询刷屏
            long now = System.currentTimeMillis();
            if (now - sLastRpmInvalidWarnTime > RPM_INVALID_WARN_INTERVAL_MS) {
                sLastRpmInvalidWarnTime = now;
                LogRecorder.getInstance().warn("FanSpeed",
                        "转速节点返回无效值(2)，已按停转处理，请确认二进制守护进程运行正常");
            }
            rpm = 0;
        }
        int oldRpm = cacheRpm;
        cacheRpm = rpm;
        if (oldRpm != rpm) {
            if (rpm == 0) {
                LogRecorder.getInstance().info("FanState", "风扇已停转，上一次转速:" + oldRpm + "，当前控制模式：" + getModeNameById(currentControlMode));
            } else if (oldRpm == 0) {
                LogRecorder.getInstance().info("FanState", "风扇已开启，当前转速:" + rpm + "，当前控制模式：" + getModeNameById(currentControlMode));
            }
        }
        return rpm;
    }

    // ========== 看门狗 ==========
    public static void checkAndRegainControl() {
        if (currentControlMode == MODE_SYSTEM) return;
        try {
            String raw = runSuCommand("settings get system cooling_fan_enable");
            if ("1".equals(raw.trim())) {
                systemSetFanEnable(false);
                LogRecorder.getInstance().warn("FanWatchdog", "检测到系统接管风扇，已重新夺回控制权");
                if (currentControlMode == MODE_SMART) {
                    setFanPwmDuty(targetPwmDuty);
                } else if (currentControlMode == MODE_LOW_LEVEL || currentControlMode == MODE_APP_CUSTOM) {
                    setFanTargetLevel(currentTargetLevel);
                }
            }
        } catch (Exception e) {
            LogRecorder.getInstance().error("FanWatchdog", "控制权校验异常:" + e.getMessage());
        }
    }

    // ========== Root命令 ==========
    public synchronized static String runSuCommand(String cmd) {
        if (sSuFailCount >= SU_FAIL_THRESHOLD) {
            if (System.currentTimeMillis() - sFuseStartTime < FUSE_DURATION_MS) {
                return "";
            } else {
                sSuFailCount = 0;
            }
        }
        Process process = null;
        BufferedReader reader = null;
        try {
            process = new ProcessBuilder("su", "-c", cmd).start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            boolean finished = process.waitFor(200, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                sSuFailCount++;
                checkFuseTrigger();
                LogRecorder.getInstance().warn("SuCommand", "命令执行超时: " + cmd);
                return "";
            }
            sSuFailCount = 0;
            return sb.toString().trim();
        } catch (Exception e) {
            sSuFailCount++;
            checkFuseTrigger();
            LogRecorder.getInstance().error("SuCommand", "命令执行失败: " + cmd + ", 异常: " + e.getMessage());
            return "";
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            if (process != null) process.destroy();
        }
    }

    private static void checkFuseTrigger() {
        if (sSuFailCount >= SU_FAIL_THRESHOLD) {
            sFuseStartTime = System.currentTimeMillis();
            LogRecorder.getInstance().error("SuCommand", "Root命令连续失败，已触发熔断保护（5秒）");
        }
    }

    // ========== 设备信息 ==========
    public static String getDeviceModelName() {
        if (sCachedModel != null && !sCachedModel.isEmpty()) {
            return sCachedModel;
        }
        String marketName = runSuCommand(MARKET_NAME_CMD);
        if (!marketName.isEmpty()) {
            sCachedModel = marketName;
            LogRecorder.getInstance().info("DeviceInfo", "识别设备型号: " + marketName);
            return marketName;
        }
        sCachedModel = Build.MODEL;
        LogRecorder.getInstance().warn("DeviceInfo", "无法读取市场名称，使用系统型号: " + Build.MODEL);
        return sCachedModel;
    }

    public static boolean isMaxModel() {
        return sIsMaxModel;
    }

    // ========== PWM读取 ==========
    // PWM 采样：K90 Ultra / K90 Max 两条节点路径，1 秒节流（避免频繁 su 调用）
    private static final long PWM_READ_CACHE_MS = 1000;
    private static long sLastPwmReadTime = 0;
    private static int sLastPwmValue = -1;

    // 主线程安全：直接返回上次缓存值，不触发任何 IO / su 阻塞
    public static int getCachedPwmDuty() {
        return sLastPwmValue >= 0 ? sLastPwmValue : 0;
    }

    public static int readPwmDuty() {
        long now = System.currentTimeMillis();
        if (sLastPwmReadTime != 0 && now - sLastPwmReadTime < PWM_READ_CACHE_MS && sLastPwmValue >= 0) {
            return sLastPwmValue;
        }
        sLastPwmReadTime = now;
        String[] paths = {PWM_DUTY_PATH_ULTRA, PWM_DUTY_PATH_MAX};
        for (String path : paths) {
            String out = runSuCommand("cat " + path);
            if (out == null) continue;
            String trimmed = out.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("cat:")) continue;
            try {
                int v = Integer.parseInt(trimmed);
                if (v > 100) {
                    // 0-255 占空比原始值 → 百分比
                    v = (int) Math.round(v * 100 / 255.0);
                }
                sLastPwmValue = v;
                return v;
            } catch (Exception ignored) {}
        }
        return sLastPwmValue >= 0 ? sLastPwmValue : 0;
    }

    // ========== 功耗读取（通知 / 悬浮窗共用） ==========
    private static double sLastPowerW = -1;
    private static boolean sChargingState = false;

    // 返回当前功耗瓦数（充电正/放电负，已平滑），用于通知阈值化判断
    public static float readPowerWatts(Context context) {
        readPowerText(context);
        return (float) sLastPowerW;
    }

    public static String readPowerText(Context context) {
        // 优先 BatteryManager 瞬时电流（无 su 开销、实时准确）
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            long cur = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
            int vol = -1;
            try {
                Intent bi = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    vol = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                    int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    sChargingState = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
                }
            } catch (Exception ignored) {}
            if (cur != 0 && cur != Integer.MIN_VALUE && vol > 0) {
                return smoothPower(cur, vol);
            }
        } catch (Exception ignored) {}
        // 降级：dumpsys battery（su）
        try {
            String out = runSuCommand("dumpsys battery");
            if (out != null && !out.isEmpty()) {
                long cur = parseLongAfter(out, "current now:");
                // dumpsys battery 的 voltage 单位为 µV，需换算为 mV 再统一计算
                long volMv = parseLongAfter(out, "voltage:") / 1000;
                long status = parseLongAfter(out, "status:");
                sChargingState = status == 2 || status == 5; // 2=charging 5=full
                if (cur != 0 && volMv > 0) {
                    return smoothPower(cur, volMv);
                }
            }
        } catch (Exception ignored) {}
        return "--";
    }

    private static String smoothPower(long curUa, long volMv) {
        // 电流 µA × 电压 mV = 1e-9 W，保留正负号（放电负 / 充电正）
        double w = ((double) curUa) * volMv / 1e9;
        if (sChargingState) {
            w = Math.abs(w);
        } else {
            w = -Math.abs(w);
        }
        // 仅在同方向时平滑，避免充放电切换瞬间正负抵消出错误的小值
        if (sLastPowerW > 0 == w > 0) {
            w = sLastPowerW * 0.5 + w * 0.5;
        }
        sLastPowerW = w;
        return String.format(Locale.US, "%.1fW", w);
    }

    public static long parseLongAfter(String text, String key) {
        if (text == null) return 0;
        int idx = text.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int end = start;
        while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '-')) {
            end++;
        }
        try {
            return Long.parseLong(text.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== PWM写入 ==========
    public static void setFanPwmDuty(int percent) {
        percent = Math.max(0, Math.min(99, percent));
        String path = isMaxModel() ? PWM_DUTY_PATH_MAX : PWM_DUTY_PATH_ULTRA;
        runSuCommand("echo " + percent + " > " + path);
        targetPwmDuty = percent;
    }

    public static void setFanTargetLevel(int level) {
        String path = isMaxModel() ? TARGET_LEVEL_PATH_MAX : TARGET_LEVEL_PATH_ULTRA;
        runSuCommand("echo " + level + " > " + path);
        currentTargetLevel = level;
    }

    private static void systemSetFanEnable(boolean enable) {
        runSuCommand("settings put system cooling_fan_enable " + (enable ? 1 : 0));
    }

    private static void systemSetFanMode(int mode) {
        runSuCommand("settings put system fan_mode " + mode);
    }

    // ========== 统一档位 ==========
    private static void applyGear(int systemMode, int level, int pwm) {
        switch (currentControlMode) {
            case MODE_SYSTEM:
                if (systemMode == -1) {
                    setFanTargetLevel(0);
                    systemSetFanEnable(false);
                } else {
                    systemSetFanEnable(true);
                    systemSetFanMode(systemMode);
                }
                break;
            case MODE_LOW_LEVEL:
            case MODE_APP_CUSTOM:
                setFanTargetLevel(level);
                break;
            case MODE_SMART:
                setFanPwmDuty(pwm);
                currentSmoothPwm = pwm;
                break;
        }
    }

    public static void setGearStop() {
        applyGear(-1, 0, 0);
        LogRecorder.getInstance().info("FanGear", "切换档位: 关闭");
    }
    public static void setGearSilent() {
        applyGear(1, 1, 30);
        LogRecorder.getInstance().info("FanGear", "切换档位: 静谧");
    }
    public static void setGearFast() {
        applyGear(0, 2, 60);
        LogRecorder.getInstance().info("FanGear", "切换档位: 高速");
    }
    public static void setGearMax() {
        applyGear(4, 4, 99);
        LogRecorder.getInstance().info("FanGear", "切换档位: 狂暴");
    }

    public static void fanStop() { setGearStop(); }
    public static void fanSilent() { setGearSilent(); }
    public static void fanFast() { setGearFast(); }
    public static void fanMax() { setGearMax(); }

    // ========== 辅助映射函数 ==========
    private static int mapLevelToPwm(int level) {
        switch (level) {
            case 1: return 30;
            case 2: return 60;
            case 4: return 99;
            case 0:
            default: return 0;
        }
    }

    private static int mapPwmToLevel(int pwm) {
        if (pwm <= 0) return 0;
        else if (pwm <= 30) return 1;
        else if (pwm <= 60) return 2;
        else return 4;
    }

    // ========== 控制模式切换 ==========
    public static void switchControlMode(int mode) {
        String oldMode = getModeNameById(currentControlMode);
        int currentLevel = 0;
        int currentPwm = 0;
        boolean hasLevel = false;
        boolean hasPwm = false;

        if (currentControlMode == MODE_SYSTEM) {
            int sysMode = getCurrentFanMode();
            if (sysMode == 1) { currentLevel = 1; currentPwm = 30; }
            else if (sysMode == 0) { currentLevel = 2; currentPwm = 60; }
            else if (sysMode == 4) { currentLevel = 4; currentPwm = 99; }
            else { currentLevel = 1; currentPwm = 30; }
            hasLevel = hasPwm = true;
        } else if (currentControlMode == MODE_LOW_LEVEL || currentControlMode == MODE_APP_CUSTOM) {
            currentLevel = currentTargetLevel;
            currentPwm = mapLevelToPwm(currentLevel);
            hasLevel = hasPwm = true;
        } else if (currentControlMode == MODE_SMART) {
            currentPwm = targetPwmDuty;
            currentLevel = mapPwmToLevel(currentPwm);
            hasLevel = hasPwm = true;
        }

        currentControlMode = mode;
        saveControlMode(mode);

        switch (mode) {
            case MODE_SYSTEM:
                systemSetFanEnable(true);
                systemSetFanMode(-1);
                break;
            case MODE_LOW_LEVEL:
            case MODE_APP_CUSTOM:
                systemSetFanEnable(false);
                int targetLevel;
                if (hasLevel) {
                    targetLevel = currentLevel;
                } else {
                    targetLevel = (mode == MODE_LOW_LEVEL) ? 1 : 0;
                }
                setFanTargetLevel(targetLevel);
                break;
            case MODE_SMART:
                systemSetFanEnable(false);
                systemSetFanMode(0);
                int initPwm = hasPwm ? currentPwm : 0;
                currentSmoothPwm = initPwm;
                int temp = getCurrentTemp();
                int targetPwm = calcPwmByTemp(temp);
                if (targetPwm != initPwm) {
                    applySmoothPwm(targetPwm);
                } else {
                    setFanPwmDuty(targetPwm);
                }
                break;
        }
        LogRecorder.getInstance().info("FanMode", "控制模式切换: " + oldMode + " → " + getModeNameById(mode));
    }

    // ========== 平滑调速（使用动态步进） ==========
    public static void applySmoothPwm(int targetPwm) {
        if (currentControlMode != MODE_SMART) return;
        targetPwm = Math.max(0, Math.min(99, targetPwm));
        int step = getCurrentStep(cacheTemp);
        float oldSmooth = currentSmoothPwm;
        if (currentSmoothPwm < targetPwm) {
            currentSmoothPwm = Math.min(targetPwm, currentSmoothPwm + step);
        } else if (currentSmoothPwm > targetPwm) {
            currentSmoothPwm = Math.max(targetPwm, currentSmoothPwm - step);
        }
        int writeValue = Math.round(currentSmoothPwm);
        if (writeValue != targetPwmDuty) {
            setFanPwmDuty(writeValue);
            long now = System.currentTimeMillis();
            if (now - sLastSmartPwmLogTime >= SMART_PWM_LOG_INTERVAL) {
                LogRecorder.getInstance().info("SmartPwm", String.format("平滑调速 old=%.0f → new=%d，目标PWM=%d，步进值=%d，基准节点：%s",
                        oldSmooth, writeValue, targetPwm, step, getCurrentTempNodeName()));
                sLastSmartPwmLogTime = now;
            }
        }
    }

    public static int calcPwmByTemp(int temp) {
        if (smartCurvePoints == null || smartCurvePoints.size() < 2) return 0;
        if (temp <= smartCurvePoints.get(0).x) return (int) smartCurvePoints.get(0).y;
        if (temp >= smartCurvePoints.get(smartCurvePoints.size() - 1).x)
            return (int) smartCurvePoints.get(smartCurvePoints.size() - 1).y;
        for (int i = 0; i < smartCurvePoints.size() - 1; i++) {
            PointF p1 = smartCurvePoints.get(i);
            PointF p2 = smartCurvePoints.get(i + 1);
            if (temp >= p1.x && temp <= p2.x) {
                float ratio = (temp - p1.x) / (p2.x - p1.x);
                return (int) (p1.y + ratio * (p2.y - p1.y));
            }
        }
        return 0;
    }

    public static String getCurrentModeName(int rpm) {
        if (rpm == 0) return "关闭";
        if (currentControlMode == MODE_SMART) return "智能";
        if (currentControlMode == MODE_LOW_LEVEL || currentControlMode == MODE_APP_CUSTOM) {
            switch (currentTargetLevel) {
                case 0: return "关闭";
                case 1: return "静谧";
                case 2: return "高速";
                case 4: return "狂暴";
                default: return getModeByRpm(rpm);
            }
        }
        int systemMode = getCurrentFanMode();
        switch (systemMode) {
            case -1: return "智能";
            case 1: return "静谧";
            case 0: return "高速";
            case 4: return "狂暴";
            default: return getModeByRpm(rpm);
        }
    }

    public static String getModeNameById(int mode) {
        switch (mode) {
            case MODE_SYSTEM: return "系统联调";
            case MODE_LOW_LEVEL: return "底层写入";
            case MODE_SMART: return "智能调频";
            case MODE_APP_CUSTOM: return "应用自定义";
            default: return "未知";
        }
    }

    public static String getModeByRpm(int rpm) {
        if (rpm == 0) return "关闭";
        else if (rpm < 13000) return "静谧";
        else if (rpm < 18000) return "高速";
        else return "狂暴";
    }

    // ========== 档位/温度颜色映射（统一入口，避免多处重复阈值） ==========
    public static int getRpmColor(int rpm) {
        if (rpm == 0) return 0xFF9AA0A6;
        if (rpm < 13000) return 0xFF2D7DFF;
        if (rpm < 18000) return 0xFFFF9800;
        return 0xFFF44336;
    }

    public static int getTempColor(int temp) {
        if (temp < 30) return 0xFF888888;
        if (temp < 40) return 0xFF2D7DFF;
        if (temp < 50) return 0xFFFF9800;
        return 0xFFF44336;
    }

    public static List<PointF> getDefaultCurve() {
        List<PointF> copy = new ArrayList<>();
        for (PointF p : DEFAULT_SMART_CURVE) {
            copy.add(new PointF(p.x, p.y));
        }
        return copy;
    }

    public static int getCurrentFanMode() {
        try {
            String raw = runSuCommand(FAN_MODE_CMD);
            if (raw.isEmpty()) return -1;
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            LogRecorder.getInstance().warn("FanRead", "读取系统风扇模式失败");
            return -1;
        }
    }

    // ========== 息屏处理（智能模式也支持备份恢复） ==========
    public static void handleScreenOff() {
        int rpm = getRealSpeed();
        sWasFanOffBeforeScreenOff = (rpm == 0);

        if (!sWasFanOffBeforeScreenOff) {
            if (currentControlMode == MODE_SYSTEM) {
                sScreenOffBackupSystemMode = getCurrentFanMode();
                LogRecorder.getInstance().info("ScreenEvent", "备份系统模式: " + sScreenOffBackupSystemMode);
            } else if (currentControlMode == MODE_LOW_LEVEL || currentControlMode == MODE_APP_CUSTOM) {
                sScreenOffBackupLevel = currentTargetLevel;
                LogRecorder.getInstance().info("ScreenEvent", "备份档位 level: " + sScreenOffBackupLevel);
            } else if (currentControlMode == MODE_SMART) {
                sScreenOffBackupPwm = targetPwmDuty;
                LogRecorder.getInstance().info("ScreenEvent", "智能模式，备份 targetPwmDuty=" + sScreenOffBackupPwm);
            }
        } else {
            LogRecorder.getInstance().info("ScreenEvent", "风扇已在关闭状态，无需备份");
        }

        setGearStop();
        LogRecorder.getInstance().info("ScreenEvent", "屏幕熄灭，已执行息屏停转");
    }

    public static void handleScreenOn() {
        if (sWasFanOffBeforeScreenOff) {
            LogRecorder.getInstance().info("ScreenEvent", "熄屏前风扇已关闭，无需恢复");
            sScreenOffBackupLevel = -1;
            sScreenOffBackupSystemMode = -1;
            sScreenOffBackupPwm = -1;
            sWasFanOffBeforeScreenOff = false;
            return;
        }

        if (currentControlMode == MODE_SYSTEM) {
            if (sScreenOffBackupSystemMode == 1) fanSilent();
            else if (sScreenOffBackupSystemMode == 0) fanFast();
            else if (sScreenOffBackupSystemMode == 4) fanMax();
            else {
                LogRecorder.getInstance().warn("ScreenEvent", "系统模式备份无效，使用默认静谧");
                fanSilent();
            }
        } else if (currentControlMode == MODE_LOW_LEVEL || currentControlMode == MODE_APP_CUSTOM) {
            if (sScreenOffBackupLevel >= 0) {
                setFanTargetLevel(sScreenOffBackupLevel);
            } else {
                LogRecorder.getInstance().warn("ScreenEvent", "档位备份无效，使用默认静谧 (level=1)");
                setFanTargetLevel(1);
            }
        } else if (currentControlMode == MODE_SMART) {
            if (sScreenOffBackupPwm >= 0) {
                // 直接恢复到备份值
                setFanPwmDuty(sScreenOffBackupPwm);
                currentSmoothPwm = sScreenOffBackupPwm;
                LogRecorder.getInstance().info("ScreenEvent", "智能模式，恢复备份PWM=" + sScreenOffBackupPwm);
            } else {
                // 降级：重新计算
                int temp = getCurrentTemp();
                int targetPwm = calcPwmByTemp(temp);
                currentSmoothPwm = 0f;
                applySmoothPwm(targetPwm);
                LogRecorder.getInstance().info("ScreenEvent", "智能模式，无备份，重新计算PWM");
            }
        }

        // 清理备份变量
        sScreenOffBackupLevel = -1;
        sScreenOffBackupSystemMode = -1;
        sScreenOffBackupPwm = -1;
        sWasFanOffBeforeScreenOff = false;
        LogRecorder.getInstance().info("ScreenEvent", "屏幕点亮，已恢复风扇运行状态");
    }

    // ========== 充电自动狂暴 ==========
    public static void checkChargingAutoBoost(Context context) {
        if (context == null) return;
        try {
            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent == null) return;
            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            if (currentControlMode == MODE_SMART) {
                sLastIsCharging = isCharging;
                sIsCharging = isCharging;
                return;
            }

            if (!sLastIsCharging && isCharging) {
                int currentRpm = getRealSpeed();
                if (currentRpm > 0) {
                    sWasFanOffBeforeCharge = false;
                    if (currentControlMode == MODE_SYSTEM) {
                        sOriginalFanMode = getCurrentFanMode();
                    } else if (currentControlMode == MODE_LOW_LEVEL) {
                        sOriginalTargetLevel = currentTargetLevel;
                    } else if (currentControlMode == MODE_APP_CUSTOM) {
                        sOriginalAppLevel = currentTargetLevel;
                        if (currentTargetLevel == 4) {
                            sLastIsCharging = isCharging;
                            sIsCharging = isCharging;
                            return;
                        }
                    }
                } else {
                    sWasFanOffBeforeCharge = true;
                    sOriginalFanMode = -1;
                    sOriginalTargetLevel = -1;
                    sOriginalAppLevel = -1;
                }
                if (currentControlMode == MODE_APP_CUSTOM) {
                    setFanTargetLevel(4);
                } else {
                    fanMax();
                }
                LogRecorder.getInstance().info("ChargeBoost", "检测到充电，已触发自动狂暴模式");
            }

            if (sLastIsCharging && !isCharging) {
                if (sWasFanOffBeforeCharge) {
                    fanStop();
                } else {
                    if (currentControlMode == MODE_SYSTEM) {
                        switch (sOriginalFanMode) {
                            case 1: fanSilent(); break;
                            case 0: fanFast(); break;
                            case 4: break;
                            default: fanSilent(); break;
                        }
                    } else if (currentControlMode == MODE_LOW_LEVEL) {
                        if (sOriginalTargetLevel >= 0) {
                            setFanTargetLevel(sOriginalTargetLevel);
                        }
                    } else if (currentControlMode == MODE_APP_CUSTOM) {
                        if (sOriginalAppLevel >= 0) {
                            setFanTargetLevel(sOriginalAppLevel);
                        }
                    }
                }
                sOriginalFanMode = -1;
                sOriginalTargetLevel = -1;
                sOriginalAppLevel = -1;
                sWasFanOffBeforeCharge = false;
                LogRecorder.getInstance().info("ChargeBoost", "充电结束，已恢复原风扇档位");
            }

            sLastIsCharging = isCharging;
            sIsCharging = isCharging;
        } catch (Exception e) {
            LogRecorder.getInstance().error("ChargeBoost", "充电状态检测异常: " + e.getMessage());
        }
    }

    // ========== 通用工具 ==========
    public static int dp2px(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}