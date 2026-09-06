package com.fan.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class SmartCurveEditView extends View {

    // 常量
    private static final float MIN_TEMP = 30f;
    private static final float MAX_TEMP = 100f;
    private static final float MIN_PWM = 0f;
    private static final float MAX_PWM = 99f;
    private static final int TOUCH_SLOP = 48; // 像素，用于触摸容差
    private static final float[] Y_TICKS = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 99};
    private static final float[] X_TICKS = {30, 40, 50, 60, 70, 80, 90, 99};

    // 边距（dp）
    private static final int PADDING_LEFT_DP = 20;
    private static final int PADDING_RIGHT_DP = 5;
    private static final int PADDING_TOP_DP = 10;
    private static final int PADDING_BOTTOM_DP = 10;

    // 画笔
    private Paint mGridPaint, mLinePaint, mPointPaint, mTextPaint;

    // 数据
    private List<PointF> mPoints = new ArrayList<>();
    private int mActivePointIndex = -1;
    private OnPointChangedListener mListener;

    // 绘图区域（像素）
    private final RectF mChartRect = new RectF();
    private int mChartWidth, mChartHeight;

    public interface OnPointChangedListener {
        void onPointChanged();
    }

    public SmartCurveEditView(Context context) {
        super(context);
        initPaint(context);
    }

    public SmartCurveEditView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaint(context);
    }

    private void initPaint(Context context) {
        mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGridPaint.setColor(context.getColor(R.color.curve_grid));
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(1f);

        mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mLinePaint.setColor(0xFF2D7DFF);
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(2.6f);

        mPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPointPaint.setColor(0xFFFFFFFF);
        mPointPaint.setStyle(Paint.Style.FILL);
        mPointPaint.setStrokeWidth(2.5f);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(context.getColor(R.color.curve_axis_text));
        mTextPaint.setTextSize(FanUtil.dp2px(getContext(), 10));
        mTextPaint.setFakeBoldText(true);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    // onSizeChanged 逻辑不变，现在的 side 将随 View 尺寸变化
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float paddingLeft = FanUtil.dp2px(getContext(), PADDING_LEFT_DP);
        float paddingRight = FanUtil.dp2px(getContext(), PADDING_RIGHT_DP);
        float paddingTop = FanUtil.dp2px(getContext(), PADDING_TOP_DP);
        float paddingBottom = FanUtil.dp2px(getContext(), PADDING_BOTTOM_DP);

        // 移除强制正方形（min）逻辑，让图表完全填满设定的区域
        float availableW = w - paddingLeft - paddingRight;
        float availableH = h - paddingTop - paddingBottom;

        mChartRect.set(paddingLeft, paddingTop, paddingLeft + availableW, paddingTop + availableH);
        mChartWidth = (int) availableW;
        mChartHeight = (int) availableH;
    }

    // ========== 坐标转换 ==========
    private float getXFromTemp(float temp) {
        return mChartRect.left + (temp - MIN_TEMP) / (MAX_TEMP - MIN_TEMP) * mChartWidth;
    }

    private float getYFromPwm(float pwm) {
        return mChartRect.top + mChartHeight - (pwm - MIN_PWM) / (MAX_PWM - MIN_PWM) * mChartHeight;
    }

    private float getTempFromX(float x) {
        return MIN_TEMP + (x - mChartRect.left) / mChartWidth * (MAX_TEMP - MIN_TEMP);
    }

    private float getPwmFromY(float y) {
        return MAX_PWM - (y - mChartRect.top) / mChartHeight * (MAX_PWM - MIN_PWM);
    }

    // ========== 外部接口 ==========
    public void setCurvePoints(List<PointF> points) {
        mPoints = new ArrayList<>(points);
        invalidate();
    }

    public List<PointF> getCurvePoints() {
        return new ArrayList<>(mPoints);
    }

    public void setOnPointChangedListener(OnPointChangedListener listener) {
        mListener = listener;
    }

    // ========== 绘制 ==========
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mChartWidth <= 0 || mChartHeight <= 0) return;

        drawGridAndLabels(canvas);

        if (mPoints.size() < 2) return;

        // 折线
        for (int i = 0; i < mPoints.size() - 1; i++) {
            PointF p1 = mPoints.get(i);
            PointF p2 = mPoints.get(i + 1);
            float x1 = getXFromTemp(p1.x);
            float y1 = getYFromPwm(p1.y);
            float x2 = getXFromTemp(p2.x);
            float y2 = getYFromPwm(p2.y);
            canvas.drawLine(x1, y1, x2, y2, mLinePaint);
        }

        // 节点
        for (PointF p : mPoints) {
            float cx = getXFromTemp(p.x);
            float cy = getYFromPwm(p.y);
            canvas.drawCircle(cx, cy, 8f, mPointPaint);
            canvas.drawCircle(cx, cy, 8f, mLinePaint);
        }
    }

    private void drawGridAndLabels(Canvas canvas) {
        // 纵轴
        for (float tick : Y_TICKS) {
            float y = getYFromPwm(tick);
            canvas.drawLine(mChartRect.left, y, mChartRect.right, y, mGridPaint);
            canvas.drawText((int) tick + "%", mChartRect.left - 30, y + 6, mTextPaint);
        }
        // 横轴
        for (float tick : X_TICKS) {
            float x = getXFromTemp(tick);
            canvas.drawLine(x, mChartRect.top, x, mChartRect.bottom, mGridPaint);
            canvas.drawText((int) tick + "", x, mChartRect.bottom + 24, mTextPaint);
        }
    }

    // ========== 触摸交互 ==========
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mChartWidth <= 0 || mChartHeight <= 0) return false;

        float touchX = event.getX();
        float touchY = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mActivePointIndex = findNearestPoint(touchX, touchY);
                return mActivePointIndex != -1;
            case MotionEvent.ACTION_MOVE:
                if (mActivePointIndex != -1) {
                    PointF p = mPoints.get(mActivePointIndex);
                    float temp = clamp(getTempFromX(touchX), MIN_TEMP, MAX_TEMP);
                    float pwm = clamp(getPwmFromY(touchY), MIN_PWM, MAX_PWM);
                    p.x = temp;
                    p.y = pwm;
                    invalidate();
                    if (mListener != null) {
                        mListener.onPointChanged();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mActivePointIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int findNearestPoint(float touchX, float touchY) {
        int nearest = -1;
        float minDistSq = TOUCH_SLOP * TOUCH_SLOP;
        for (int i = 0; i < mPoints.size(); i++) {
            PointF p = mPoints.get(i);
            float px = getXFromTemp(p.x);
            float py = getYFromPwm(p.y);
            float dx = touchX - px;
            float dy = touchY - py;
            float distSq = dx * dx + dy * dy;
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearest = i;
            }
        }
        return nearest;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}