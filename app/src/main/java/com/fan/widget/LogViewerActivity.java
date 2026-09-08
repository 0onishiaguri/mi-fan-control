package com.fan.widget;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogViewerActivity extends BaseActivity {

    // 颜色常量
    private static final int COLOR_ERROR_BG = Color.parseColor("#FFEBEE");
    private static final int COLOR_ERROR_TEXT = Color.parseColor("#B71C1C");
    private static final int COLOR_WARN_BG = Color.parseColor("#FFF3E0");
    private static final int COLOR_WARN_TEXT = Color.parseColor("#FF6F00");
    private static final int COLOR_INFO_BG = Color.parseColor("#E8F5E9");
    private static final int COLOR_INFO_TEXT = Color.parseColor("#2E7D32");

    // dp 常量
    private static final int CORNER_RADIUS_DP = 8;
    private static final int PADDING_TAG_DP = 8;
    private static final int EMPTY_TIP_MARGIN_TOP_DP = 80;
    private static final int MAX_LOG_RENDER_COUNT = 200;

    private LinearLayout logContainer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        // 状态栏由 BaseActivity 统一处理，无需再设置

        logContainer = findViewById(R.id.log_container);
        loadAndRenderLogs();

        // 底部导航
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.menu_item_export_log) {
                exportLogFile();
                return true;
            }
            return false;
        });
    }

    // ========== 渲染日志列表 ==========

    private void loadAndRenderLogs() {
        List<LogRecorder.LogEntry> logList = LogRecorder.getInstance().getAllLogs();
        logContainer.removeAllViews();

        if (logList == null || logList.isEmpty()) {
            showEmptyTip();
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

        // 仅渲染最近 200 条，避免一次性 inflate 过多视图导致卡顿
        int renderCount = Math.min(logList.size(), MAX_LOG_RENDER_COUNT);
        for (int i = 0; i < renderCount; i++) {
            LogRecorder.LogEntry entry = logList.get(i);
            if (entry == null) continue;

            View itemView = inflater.inflate(R.layout.item_log_entry, logContainer, false);
            TextView tvTag = itemView.findViewById(R.id.tv_log_tag);
            TextView tvSource = itemView.findViewById(R.id.tv_log_source);
            TextView tvTime = itemView.findViewById(R.id.tv_log_time);
            TextView tvContent = itemView.findViewById(R.id.tv_log_content);

            String level = entry.level != null ? entry.level : "INFO";
            tvTag.setText(level);
            tvSource.setText(entry.tag != null ? entry.tag : "");
            tvTime.setText(timeFormat.format(new Date(entry.timeMs)));
            tvContent.setText(entry.message != null ? entry.message : "");

            int bgColor, textColor;
            if ("ERROR".equals(level)) {
                bgColor = COLOR_ERROR_BG;
                textColor = COLOR_ERROR_TEXT;
            } else if ("WARN".equals(level)) {
                bgColor = COLOR_WARN_BG;
                textColor = COLOR_WARN_TEXT;
            } else {
                bgColor = COLOR_INFO_BG;
                textColor = COLOR_INFO_TEXT;
            }

            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(FanUtil.dp2px(this, CORNER_RADIUS_DP));
            drawable.setPadding(FanUtil.dp2px(this, PADDING_TAG_DP), FanUtil.dp2px(this, 2), FanUtil.dp2px(this, PADDING_TAG_DP), FanUtil.dp2px(this, 2));
            drawable.setColor(bgColor);
            tvTag.setBackground(drawable);
            tvTag.setTextColor(textColor);

            logContainer.addView(itemView);
        }
    }

    private void showEmptyTip() {
        TextView emptyTip = new TextView(this);
        emptyTip.setText("暂无日志记录");
        emptyTip.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        emptyTip.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = FanUtil.dp2px(this, EMPTY_TIP_MARGIN_TOP_DP);
        emptyTip.setLayoutParams(params);
        logContainer.addView(emptyTip);
    }

    // ========== 导出日志 ==========

    private void exportLogFile() {
        doExportLog();
    }

    private void doExportLog() {
        File logFile = LogRecorder.getInstance().exportToFile(this);
        if (logFile != null) {
            Toast.makeText(this, "日志已导出至 下载/MiFanControl 目录", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "导出失败，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }
    // ========== 工具方法 ==========
}