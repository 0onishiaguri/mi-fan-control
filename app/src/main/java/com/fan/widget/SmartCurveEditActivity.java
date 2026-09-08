package com.fan.widget;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.PointF;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SmartCurveEditActivity extends BaseActivity {
    private static final String SP_CONFIG = "fan_widget_config";
    private static final int POINT_COUNT = 8;
    private static final String CONFIG_FILE_NAME = "smart_curve_export.json";

    private SmartCurveEditView mCurveEditView;
    private Button btnSave, btnReset, btnExport, btnImport;
    private final EditText[] mTempEts = new EditText[POINT_COUNT];
    private final EditText[] mPwmEts = new EditText[POINT_COUNT];

    // 步进相关控件
    private RadioGroup mStepModeGroup;
    private EditText etFixedStep;
    private EditText[] intervalLow = new EditText[3];
    private EditText[] intervalHigh = new EditText[3];
    private EditText[] intervalStep = new EditText[3];

    private boolean mIsProgrammaticUpdate = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smart_curve_edit);

        // 状态栏由 BaseActivity 统一处理

        mCurveEditView = findViewById(R.id.curve_edit_view);
        btnSave = findViewById(R.id.btn_save_curve);
        btnReset = findViewById(R.id.btn_reset_curve);
        btnExport = findViewById(R.id.btn_export_config);
        btnImport = findViewById(R.id.btn_import_config);

        // 绑定步进控件
        mStepModeGroup = findViewById(R.id.rg_step_mode);
        etFixedStep = findViewById(R.id.et_fixed_step);
        intervalLow[0] = findViewById(R.id.et_interval1_low);
        intervalHigh[0] = findViewById(R.id.et_interval1_high);
        intervalStep[0] = findViewById(R.id.et_interval1_step);
        intervalLow[1] = findViewById(R.id.et_interval2_low);
        intervalHigh[1] = findViewById(R.id.et_interval2_high);
        intervalStep[1] = findViewById(R.id.et_interval2_step);
        intervalLow[2] = findViewById(R.id.et_interval3_low);
        intervalHigh[2] = findViewById(R.id.et_interval3_high);
        intervalStep[2] = findViewById(R.id.et_interval3_step);

        bindPointInputs();
        loadCurve();
        loadStepConfig();

        // 曲线拖动变化 -> 更新输入框
        mCurveEditView.setOnPointChangedListener(() -> {
            if (mIsProgrammaticUpdate) return;
            mIsProgrammaticUpdate = true;
            updateInputsFromCurve();
            mIsProgrammaticUpdate = false;
        });

        btnSave.setOnClickListener(v -> {
            saveCurve();
            saveStepConfig();
            Toast.makeText(this, "已保存所有配置", Toast.LENGTH_SHORT).show();
            finish();
        });

        btnReset.setOnClickListener(v -> resetAllConfig());

        btnExport.setOnClickListener(v -> exportConfig());
        btnImport.setOnClickListener(v -> importConfig());
    }

    // ========== 绑定节点输入框 ==========
    private void bindPointInputs() {
        int[] pointIds = {
                R.id.point_1, R.id.point_2, R.id.point_3, R.id.point_4,
                R.id.point_5, R.id.point_6, R.id.point_7, R.id.point_8
        };

        for (int i = 0; i < POINT_COUNT; i++) {
            View pointView = findViewById(pointIds[i]);
            TextView title = pointView.findViewById(R.id.tv_point_title);
            title.setText("节点" + (i + 1));
            mTempEts[i] = pointView.findViewById(R.id.et_temp);
            mPwmEts[i] = pointView.findViewById(R.id.et_pwm);

            final int index = i;
            TextWatcher watcher = createTextWatcher(index);
            mTempEts[i].addTextChangedListener(watcher);
            mPwmEts[i].addTextChangedListener(watcher);
        }
    }

    private TextWatcher createTextWatcher(final int index) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (mIsProgrammaticUpdate) return;
                mIsProgrammaticUpdate = true;
                updateCurveFromInputs();
                mIsProgrammaticUpdate = false;
            }
        };
    }

    // ========== 更新UI ==========
    private void updateInputsFromCurve() {
        List<PointF> points = mCurveEditView.getCurvePoints();
        for (int i = 0; i < POINT_COUNT && i < points.size(); i++) {
            PointF p = points.get(i);
            mTempEts[i].setText(String.valueOf((int) p.x));
            mPwmEts[i].setText(String.valueOf((int) p.y));
        }
        for (int i = points.size(); i < POINT_COUNT; i++) {
            mTempEts[i].setText("");
            mPwmEts[i].setText("");
        }
    }

    private void updateCurveFromInputs() {
        List<PointF> points = new ArrayList<>();
        for (int i = 0; i < POINT_COUNT; i++) {
            PointF p = parsePointFromInput(i);
            if (p != null) {
                points.add(p);
            }
        }
        if (points.size() >= 2) {
            mCurveEditView.setCurvePoints(points);
        }
    }

    private PointF parsePointFromInput(int index) {
        String tempStr = mTempEts[index].getText().toString().trim();
        String pwmStr = mPwmEts[index].getText().toString().trim();
        if (tempStr.isEmpty() || pwmStr.isEmpty()) return null;
        try {
            float temp = Float.parseFloat(tempStr);
            float pwm = Float.parseFloat(pwmStr);
            temp = Math.max(30f, Math.min(100f, temp));
            pwm = Math.max(0f, Math.min(99f, pwm));
            return new PointF(temp, pwm);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========== 步进配置加载与保存 ==========
    private void loadStepConfig() {
        SharedPreferences sp = getSharedPreferences(SP_CONFIG, MODE_PRIVATE);
        int mode = sp.getInt(FanUtil.KEY_STEP_MODE, FanUtil.STEP_MODE_FIXED);
        mStepModeGroup.check(mode == FanUtil.STEP_MODE_FIXED ? R.id.rb_fixed_step : R.id.rb_interval_step);
        int fixedStep = sp.getInt(FanUtil.KEY_FIXED_STEP, 5);
        etFixedStep.setText(String.valueOf(fixedStep));

        String intervalStr = sp.getString(FanUtil.KEY_INTERVAL_LIST, "");
        List<FanUtil.Interval> intervals = FanUtil.parseIntervalList(intervalStr);
        for (int i = 0; i < 3; i++) {
            if (i < intervals.size()) {
                FanUtil.Interval inv = intervals.get(i);
                intervalLow[i].setText(String.valueOf((int) inv.lowTemp));
                intervalHigh[i].setText(String.valueOf((int) inv.highTemp));
                intervalStep[i].setText(String.valueOf((int) inv.step));
            } else {
                intervalLow[i].setText("");
                intervalHigh[i].setText("");
                intervalStep[i].setText("");
            }
        }
    }

    private void saveStepConfig() {
        SharedPreferences sp = getSharedPreferences(SP_CONFIG, MODE_PRIVATE);
        int mode = mStepModeGroup.getCheckedRadioButtonId() == R.id.rb_fixed_step ?
                FanUtil.STEP_MODE_FIXED : FanUtil.STEP_MODE_INTERVAL;

        String fixedStepStr = etFixedStep.getText().toString().trim();
        int fixedStep = 5;
        try {
            fixedStep = Integer.parseInt(fixedStepStr);
            fixedStep = Math.max(1, Math.min(20, fixedStep));
        } catch (NumberFormatException e) {
            fixedStep = 5;
        }

        List<FanUtil.Interval> intervals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String lowStr = intervalLow[i].getText().toString().trim();
            String highStr = intervalHigh[i].getText().toString().trim();
            String stepStr = intervalStep[i].getText().toString().trim();
            if (!lowStr.isEmpty() && !highStr.isEmpty() && !stepStr.isEmpty()) {
                try {
                    float low = Float.parseFloat(lowStr);
                    float high = Float.parseFloat(highStr);
                    float step = Float.parseFloat(stepStr);
                    low = Math.max(30f, Math.min(99f, low));
                    high = Math.max(30f, Math.min(99f, high));
                    step = Math.max(1f, Math.min(20f, step));
                    if (low < high) {
                        intervals.add(new FanUtil.Interval(low, high, step));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        intervals.sort((a, b) -> Float.compare(a.lowTemp, b.lowTemp));
        FanUtil.saveStepConfig(sp, mode, fixedStep, intervals);
    }

    // ========== 导出与导入功能 ==========
    private void exportConfig() {
        try {
            JSONObject root = new JSONObject();

            List<PointF> points = mCurveEditView.getCurvePoints();
            JSONArray curveArray = new JSONArray();
            for (PointF p : points) {
                JSONObject pointObj = new JSONObject();
                pointObj.put("temp", p.x);
                pointObj.put("pwm", p.y);
                curveArray.put(pointObj);
            }
            root.put("curve", curveArray);

            root.put("mode", mStepModeGroup.getCheckedRadioButtonId() == R.id.rb_fixed_step ? 0 : 1);
            root.put("fixedStep", etFixedStep.getText().toString());

            JSONArray intervalArray = new JSONArray();
            for (int i = 0; i < 3; i++) {
                JSONObject intervalObj = new JSONObject();
                intervalObj.put("low", intervalLow[i].getText().toString());
                intervalObj.put("high", intervalHigh[i].getText().toString());
                intervalObj.put("step", intervalStep[i].getText().toString());
                intervalArray.put(intervalObj);
            }
            root.put("intervals", intervalArray);

            // 导出到公共 下载/MiFanControl 目录（与日志保存路径一致，无需存储权限）
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, CONFIG_FILE_NAME);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/MiFanControl");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Toast.makeText(this, "导出失败：无法创建文件", Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
            }

            Toast.makeText(this, "配置已导出到 下载/MiFanControl/" + CONFIG_FILE_NAME,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void importConfig() {
        try {
            // 从公共 下载/MiFanControl 目录读取（与日志保存路径一致）
            Uri configUri = queryConfigUri();
            if (configUri == null) {
                Toast.makeText(this, "未找到配置文件（下载/MiFanControl/" + CONFIG_FILE_NAME + "）",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            byte[] bytes;
            try (InputStream is = getContentResolver().openInputStream(configUri)) {
                if (is == null) {
                    Toast.makeText(this, "读取配置文件失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                bytes = bos.toByteArray();
            }

            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            mIsProgrammaticUpdate = true;

            int mode = root.optInt("mode", 0);
            mStepModeGroup.check(mode == 0 ? R.id.rb_fixed_step : R.id.rb_interval_step);
            etFixedStep.setText(root.optString("fixedStep", "5"));

            JSONArray intervalArray = root.optJSONArray("intervals");
            if (intervalArray != null) {
                for (int i = 0; i < 3; i++) {
                    JSONObject intervalObj = intervalArray.optJSONObject(i);
                    if (intervalObj != null) {
                        intervalLow[i].setText(intervalObj.optString("low"));
                        intervalHigh[i].setText(intervalObj.optString("high"));
                        intervalStep[i].setText(intervalObj.optString("step"));
                    } else {
                        intervalLow[i].setText("");
                        intervalHigh[i].setText("");
                        intervalStep[i].setText("");
                    }
                }
            }

            JSONArray curveArray = root.optJSONArray("curve");
            List<PointF> points = new ArrayList<>();
            if (curveArray != null) {
                for (int i = 0; i < curveArray.length(); i++) {
                    JSONObject pointObj = curveArray.optJSONObject(i);
                    if (pointObj != null) {
                        float temp = (float) pointObj.optDouble("temp");
                        float pwm = (float) pointObj.optDouble("pwm");
                        points.add(new PointF(temp, pwm));
                    }
                }
            }
            mCurveEditView.setCurvePoints(points);
            updateInputsFromCurve();
            mIsProgrammaticUpdate = false;

            saveCurve();
            saveStepConfig();

            Toast.makeText(this, "配置已成功导入", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            mIsProgrammaticUpdate = false;
            e.printStackTrace();
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 查询 下载/MiFanControl 下的配置文件（按文件名+相对路径精确匹配）
    private Uri queryConfigUri() {
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        String[] args = {CONFIG_FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/MiFanControl/"};
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID}, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ========== 恢复默认（含步进配置） ==========
    private void resetAllConfig() {
        List<PointF> defaultPoints = FanUtil.getDefaultCurve();
        mCurveEditView.setCurvePoints(defaultPoints);
        updateInputsFromCurve();

        mIsProgrammaticUpdate = true;
        mStepModeGroup.check(R.id.rb_fixed_step);
        etFixedStep.setText("5");
        for (int i = 0; i < 3; i++) {
            intervalLow[i].setText("");
            intervalHigh[i].setText("");
            intervalStep[i].setText("");
        }
        mIsProgrammaticUpdate = false;

        Toast.makeText(this, "已恢复默认曲线及步进设置", Toast.LENGTH_SHORT).show();
    }

    // ========== 曲线加载与保存 ==========
    private void loadCurve() {
        List<PointF> points = new ArrayList<>(FanUtil.smartCurvePoints);
        mCurveEditView.setCurvePoints(points);
        updateInputsFromCurve();
    }

    private void saveCurve() {
        List<PointF> points = mCurveEditView.getCurvePoints();
        if (points.size() < 2) {
            Toast.makeText(this, "至少需要两个节点", Toast.LENGTH_SHORT).show();
            return;
        }
        FanUtil.saveSmartCurve(points);
    }
}