package com.fan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class SimpleLineChartView extends View {
    private Paint mRpmPaint, mTempPaint, mGridPaint, mAxisTextPaint;
    private final List<Integer> mRpmList = new ArrayList<>();
    private final List<Integer> mTempList = new ArrayList<>();
    private static final int MAX_POINT = 30;
    private static final int MAX_RPM = 26000;
    private static final int MAX_TEMP = 99;

    // 边距常量
    private static final int PADDING_LEFT = 38;
    private static final int PADDING_RIGHT = 38;
    private static final int PADDING_TOP = 10;
    private static final int PADDING_BOTTOM = 10;

    public SimpleLineChartView(Context context) {
        super(context);
        initPaint();
    }

    public SimpleLineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaint();
    }

    private void initPaint() {
        mRpmPaint = new Paint();
        mRpmPaint.setColor(Color.parseColor("#2D7DFF"));
        mRpmPaint.setStyle(Paint.Style.STROKE);
        mRpmPaint.setStrokeWidth(2.5f);
        mRpmPaint.setAntiAlias(true);

        mTempPaint = new Paint();
        mTempPaint.setColor(Color.parseColor("#FF9800"));
        mTempPaint.setStyle(Paint.Style.STROKE);
        mTempPaint.setStrokeWidth(2.5f);
        mTempPaint.setAntiAlias(true);

        mGridPaint = new Paint();
        mGridPaint.setColor(Color.parseColor("#4D4D4D"));
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(1f);
        mGridPaint.setAntiAlias(true);

        mAxisTextPaint = new Paint();
        mAxisTextPaint.setColor(Color.parseColor("#666666"));
        mAxisTextPaint.setTextSize(20f);
        mAxisTextPaint.setFakeBoldText(true);
        mAxisTextPaint.setAntiAlias(true);
    }

    public void addDataPoint(int rpm, int temp) {
        mRpmList.add(rpm);
        mTempList.add(temp);
        if (mRpmList.size() > MAX_POINT) {
            mRpmList.remove(0);
            mTempList.remove(0);
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        int chartW = width - PADDING_LEFT - PADDING_RIGHT;
        int chartH = height - PADDING_TOP - PADDING_BOTTOM;
        if (chartW <= 0 || chartH <= 0) return;

        drawGridAndLabels(canvas, chartW, chartH);

        if (mRpmList.size() < 2) return;
        float stepX = (float) chartW / (MAX_POINT - 1);

        // 转速折线
        for (int i = 0; i < mRpmList.size() - 1; i++) {
            float x1 = PADDING_LEFT + i * stepX;
            float y1 = PADDING_TOP + chartH - (mRpmList.get(i) * chartH / (float) MAX_RPM);
            float x2 = PADDING_LEFT + (i + 1) * stepX;
            float y2 = PADDING_TOP + chartH - (mRpmList.get(i + 1) * chartH / (float) MAX_RPM);
            canvas.drawLine(x1, y1, x2, y2, mRpmPaint);
        }

        // 温度折线（裁剪到 0~MAX_TEMP）
        for (int i = 0; i < mTempList.size() - 1; i++) {
            float x1 = PADDING_LEFT + i * stepX;
            int temp1 = Math.min(Math.max(mTempList.get(i), 0), MAX_TEMP);
            float y1 = PADDING_TOP + chartH - (temp1 * chartH / (float) MAX_TEMP);
            float x2 = PADDING_LEFT + (i + 1) * stepX;
            int temp2 = Math.min(Math.max(mTempList.get(i + 1), 0), MAX_TEMP);
            float y2 = PADDING_TOP + chartH - (temp2 * chartH / (float) MAX_TEMP);
            canvas.drawLine(x1, y1, x2, y2, mTempPaint);
        }
    }

    /**
     * 绘制网格和刻度标签（温度刻度恢复为均匀 5 等分）
     */
    private void drawGridAndLabels(Canvas canvas, int chartW, int chartH) {
        // 纵向网格线（均匀 5 等分）
        for (int i = 0; i <= 5; i++) {
            float x = PADDING_LEFT + i * chartW / 5f;
            canvas.drawLine(x, PADDING_TOP, x, PADDING_TOP + chartH, mGridPaint);
        }

        // 横向网格线 + 刻度标签（均匀 5 等分，从 99℃ 到 0℃）
        for (int i = 0; i <= 5; i++) {
            float temp = MAX_TEMP - i * MAX_TEMP / 5f;  // 99, 79.2, 59.4, 39.6, 19.8, 0
            float y = PADDING_TOP + i * chartH / 5f;
            // 绘制横向网格线
            canvas.drawLine(PADDING_LEFT, y, PADDING_LEFT + chartW, y, mGridPaint);
            // 右侧温度标签（显示整数）
            canvas.drawText((int) temp + "℃", PADDING_LEFT + chartW + 4, y + 5, mAxisTextPaint);
        }

        // 左侧转速刻度（均匀 5 等分，从顶部到底部显示 25k,20k,15k,10k,5k,0k）
        for (int i = 0; i <= 5; i++) {
            float speed = MAX_RPM - i * MAX_RPM / 5f;
            float y = PADDING_TOP + i * chartH / 5f;
            canvas.drawText((int) (speed / 1000) + "k", 2, y + 5, mAxisTextPaint);
        }
    }
}