package com.fan.widget;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogRecorder {
    private static volatile LogRecorder sInstance;
    private final List<LogEntry> mLogList = new ArrayList<>();
    private static final int MAX_LOG_COUNT = 500;

    // 缓存日期格式化器（线程安全，因为 SimpleDateFormat 不是线程安全的，但这里仅在同步方法中使用，且不共享实例）
    private static final SimpleDateFormat DATE_FORMAT_FILE = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
    private static final SimpleDateFormat DATE_FORMAT_LOG = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public static class LogEntry {
        public final String level;
        public final String tag;
        public final String message;
        public final long timeMs;

        public LogEntry(String level, String tag, String message) {
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.timeMs = System.currentTimeMillis();
        }
    }

    private LogRecorder() {}

    public static LogRecorder getInstance() {
        if (sInstance == null) {
            synchronized (LogRecorder.class) {
                if (sInstance == null) {
                    sInstance = new LogRecorder();
                }
            }
        }
        return sInstance;
    }

    // 统一日志入口
    private void log(String level, String tag, String msg) {
        if (msg == null) msg = "";
        addLog(new LogEntry(level, tag, msg));
    }

    public void info(String tag, String msg) {
        log("INFO", tag, msg);
    }

    public void error(String tag, String msg) {
        log("ERROR", tag, msg);
    }

    public void warn(String tag, String msg) {
        log("WARN", tag, msg);
    }

    private synchronized void addLog(LogEntry entry) {
        // 尾插 O(1)，超限时移除最旧一条（原头插每次 O(n) 搬移）
        mLogList.add(entry);
        if (mLogList.size() > MAX_LOG_COUNT) {
            mLogList.remove(0);
        }
    }

    public synchronized List<LogEntry> getAllLogs() {
        // 对外保持"最新在前"的语义
        List<LogEntry> reversed = new ArrayList<>(mLogList);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    public synchronized File exportToFile(Context context) {
        try {
            String fileName = "fan_log_" + DATE_FORMAT_FILE.format(new Date()) + ".txt";

            StringBuilder sb = new StringBuilder();
            sb.append("=== Mi Fan Control 运行日志 ===\n");
            sb.append("导出时间：").append(DATE_FORMAT_LOG.format(new Date())).append("\n\n");

            // 列表按时间正序存储，正序输出即可
            for (int i = 0; i < mLogList.size(); i++) {
                LogEntry entry = mLogList.get(i);
                sb.append('[').append(DATE_FORMAT_LOG.format(new Date(entry.timeMs)))
                        .append("] [").append(entry.level)
                        .append("] ").append(entry.tag)
                        .append(": ").append(entry.message)
                        .append('\n');
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+（本项目 minSdk 35）：通过 MediaStore 写入公共 下载/MiFanControl 目录，无需存储权限
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MiFanControl");
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;
                try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    if (os == null) return null;
                    os.write(sb.toString().getBytes("UTF-8"));
                }
                return new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "MiFanControl/" + fileName);
            }

            // 极低版本兜底（minSdk 35 下不可达）
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "MiFanControl");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File file = new File(dir, fileName);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(sb.toString());
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }
}