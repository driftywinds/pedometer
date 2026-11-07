/*
    Privacy Friendly Pedometer is licensed under the GPLv3.
    Copyright (C) 2017  Tobias Neidig

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <http://www.gnu.org/licenses/>.
*/
package org.secuso.privacyfriendlyactivitytracker.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.secuso.privacyfriendlyactivitytracker.R;

/**
 * Draws a circular progress ring for the step goal. The view only draws the ring, the step
 * count and goal are rendered by regular TextViews on top of it, so that font scaling and
 * accessibility work as usual.
 */
public class StepRingView extends View {

    private static final float START_ANGLE = -90f;
    private static final float MAX_ANGLE = 360f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringBounds = new RectF();

    private float strokeWidth;
    private float sweep = 0f;
    private boolean goalReached = false;
    private final int progressColor;
    private final int goalReachedColor;
    private ValueAnimator animator;

    public StepRingView(Context context) {
        this(context, null);
    }

    public StepRingView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StepRingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        strokeWidth = getResources().getDisplayMetrics().density * 12f;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidth);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.ring_track));

        progressColor = resolveThemeColor(context, androidx.appcompat.R.attr.colorPrimary);
        goalReachedColor = ContextCompat.getColor(context, R.color.status_ok);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(strokeWidth);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(progressColor);
    }

    private int resolveThemeColor(Context context, int attr) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, ContextCompat.getColor(context, R.color.colorPrimary));
        } finally {
            a.recycle();
        }
    }

    /**
     * Updates the ring to the given step count and goal. The progress is animated.
     *
     * @param steps The number of steps of the displayed period
     * @param goal  The step goal of the displayed period (0 means "no goal")
     */
    public void setProgress(int steps, int goal) {
        goalReached = goal > 0 && steps >= goal;
        float target = goal > 0 ? Math.min(1f, (float) steps / (float) goal) : 0f;
        animateTo(target);
    }

    private void animateTo(float target) {
        if (animator != null) {
            animator.cancel();
        }
        if (Math.abs(target - sweep) < 0.001f) {
            sweep = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(sweep, target);
        animator.setDuration(650);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                sweep = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = strokeWidth / 2f + getResources().getDisplayMetrics().density * 4f;
        ringBounds.set(padding, padding, w - padding, h - padding);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (ringBounds.isEmpty()) {
            return;
        }
        canvas.drawArc(ringBounds, START_ANGLE, MAX_ANGLE, false, trackPaint);
        if (sweep > 0f) {
            progressPaint.setColor(goalReached ? goalReachedColor : progressColor);
            canvas.drawArc(ringBounds, START_ANGLE, sweep * MAX_ANGLE, false, progressPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
