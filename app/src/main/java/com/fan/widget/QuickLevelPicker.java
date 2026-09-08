package com.fan.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * 快捷档位滑动选择器：横向滑动/点击选择 关闭/静谧/高速/狂暴。
 * 点击直达目标档位（无动画竞争）；拖动实时跟随胶囊底色，松手吸附并回调；
 * 位移小于 6dp 视为点击，只回调一次，避免误吸附到相邻档位。
 */
public class QuickLevelPicker extends View {

    public interface OnLevelSelectedListener {
        void onLevelSelected(int level);
    }

    // 关闭/静谧/高速/狂暴 —— 与 FanUtil 档位值一致
    public static final int[] LEVELS = {0, 1, 2, 4};
    private static final String[] LABELS = {"关闭", "静谧", "高速", "狂暴"};
    private static final int[] COLOR_IDS = {
            R.color.btn_close, R.color.btn_silent, R.color.btn_fast, R.color.btn_rage};

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mThumbRect = new RectF();

    private int mSelectedIndex = 0;
    private float mThumbCenterX = 0f;
    private boolean mTracking = false;
    private boolean mDragged = false; // 手指位移超过阈值才算拖动
    private float mDownX = 0f;
    private ValueAnimator mAnimator;
    private OnLevelSelectedListener mListener;

    private float mSegW;
    private final int mTextIdleColor;

    public QuickLevelPicker(Context context) {
        this(context, null);
    }

    public QuickLevelPicker(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
        mTextIdleColor = ContextCompat.getColor(getContext(), R.color.text_secondary);
    }

    public void setOnLevelSelectedListener(OnLevelSelectedListener listener) {
        mListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mSegW = w / 4f;
        mThumbCenterX = mSegW * mSelectedIndex + mSegW / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        // 选中胶囊：档位色 40% 透明圆角（深浅模式下都足够明显）
        float thumbW = mSegW - dp(8);
        float left = mThumbCenterX - thumbW / 2f;
        float top = dp(4);
        mThumbRect.set(left, top, left + thumbW, h - dp(4));
        int baseColor = ContextCompat.getColor(getContext(), COLOR_IDS[mSelectedIndex]);
        mThumbPaint.setColor((baseColor & 0x00FFFFFF) | 0xCC000000); // 80% 透明度
        canvas.drawRoundRect(mThumbRect, dp(14), dp(14), mThumbPaint);

        // 文字：选中 = 档位色实色加粗；未选中 = 灰色
        mTextPaint.setTextSize(sp(14));
        mTextPaint.setFakeBoldText(true);
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < 4; i++) {
            mTextPaint.setColor(i == mSelectedIndex ? 0xFFFFFFFF : mTextIdleColor);
            float cx = mSegW * i + mSegW / 2f;
            canvas.drawText(LABELS[i], cx - mTextPaint.measureText(LABELS[i]) / 2f, baseline, mTextPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 点击直达：立即定位目标档位（不用动画，避免与拖动竞争导致只滑一格）
                cancelAnim();
                mDownX = ev.getX();
                mDragged = false;
                int idx = clampIndex((int) (ev.getX() / mSegW));
                mSelectedIndex = idx;
                mThumbCenterX = mSegW * idx + mSegW / 2f;
                invalidate();
                if (mListener != null) mListener.onLevelSelected(LEVELS[idx]);
                mTracking = true;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mTracking) return true;
                cancelAnim();
                if (Math.abs(ev.getX() - mDownX) > dp(6)) {
                    mDragged = true;
                }
                float min = mSegW / 2f;
                float max = getWidth() - mSegW / 2f;
                mThumbCenterX = Math.max(min, Math.min(max, ev.getX()));
                // 滑到哪个档位就实时显示哪个档位的胶囊底色
                int idx = clampIndex(Math.round((mThumbCenterX - mSegW / 2f) / mSegW));
                if (idx != mSelectedIndex) {
                    mSelectedIndex = idx;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!mTracking) return true;
                mTracking = false;
                // 纯点击（无位移）：档位已在 DOWN 确定并回调，这里不重复触发
                if (!mDragged) {
                    return true;
                }
                // 拖动：吸附最近档位并回调
                int idx = clampIndex(Math.round((mThumbCenterX - mSegW / 2f) / mSegW));
                moveTo(idx, true);
                if (mListener != null) mListener.onLevelSelected(LEVELS[idx]);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                mTracking = false;
                cancelAnim();
                int idx = clampIndex(Math.round((mThumbCenterX - mSegW / 2f) / mSegW));
                moveTo(idx, false);
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private void cancelAnim() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    private void moveTo(int index, boolean animate) {
        if (index < 0 || index > 3) return;
        mSelectedIndex = index;
        float targetX = mSegW * index + mSegW / 2f;
        cancelAnim();
        if (!animate || Math.abs(targetX - mThumbCenterX) < 1f) {
            mThumbCenterX = targetX;
            invalidate();
            return;
        }
        float start = mThumbCenterX;
        mAnimator = ValueAnimator.ofFloat(start, targetX);
        mAnimator.setDuration(180);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(a -> {
            mThumbCenterX = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    /** 外部状态同步（不触发回调）；用户拖动中不打断 */
    public void setCurrentLevel(int level, boolean animate) {
        if (mTracking) return;
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == level) {
                moveTo(i, animate);
                return;
            }
        }
    }

    public boolean isTracking() {
        return mTracking;
    }

    private int clampIndex(int i) {
        return Math.max(0, Math.min(3, i));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int sp(int v) {
        return (int) (v * getResources().getDisplayMetrics().scaledDensity + 0.5f);
    }
}
