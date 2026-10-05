/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.exteragram.messenger.ExteraConfig;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class RadialProgressView extends View {

    private long lastUpdateTime;
    private float radOffset;
    private float currentCircleLength;
    private boolean risingCircleLength;
    private float currentProgressTime;
    private RectF cicleRect = new RectF();
    private boolean useSelfAlpha;
    private float drawingCircleLenght;

    private int progressColor;

    private DecelerateInterpolator decelerateInterpolator;
    private AccelerateInterpolator accelerateInterpolator;
    private Paint progressPaint;
    private static final float rotationTime = 2000;
    private static final float risingTime = 500;
    private int size;

    private float currentProgress;
    private float progressAnimationStart;
    private int progressTime;
    private float animatedProgress;
    private boolean toCircle;
    private float toCircleProgress;

    private boolean noProgress = true;
    private final Theme.ResourcesProvider resourcesProvider;

    private int currentStyle = 0;
    private LoadingIndicator m3IndicatorView;
    private CircularProgressIndicator m3CircularProgressIndicator;
    private Drawable m3Drawable;

    public RadialProgressView(Context context) {
        this(context, null);
    }

    public RadialProgressView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        size = AndroidUtilities.dp(40);

        progressColor = getThemedColor(Theme.key_progressCircle);
        decelerateInterpolator = new DecelerateInterpolator();
        accelerateInterpolator = new AccelerateInterpolator();
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(AndroidUtilities.dp(3));
        progressPaint.setColor(progressColor);
    }

    public void setUseSelfAlpha(boolean value) {
        useSelfAlpha = value;
    }

    @Keep
    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (useSelfAlpha) {
            Drawable background = getBackground();
            int a = (int) (alpha * 255);
            if (background != null) {
                background.setAlpha(a);
            }
            progressPaint.setAlpha(a);
            if (m3Drawable != null) {
                m3Drawable.setAlpha(a);
            }
        }
    }

    public void setStyle(int style) {
        if (!ExteraConfig.getNewLoadingStyle() && style != 0 && style != 1) {
            style = 0;
        }
        if (currentStyle == style) {
            return;
        }
        final Drawable oldDrawable = m3Drawable;
        currentStyle = style;
        if (style == 1) {
            if (m3IndicatorView == null) {
                m3IndicatorView = new LoadingIndicator(getContext());
            }
            m3IndicatorView.setIndicatorColor(progressColor);
            m3Drawable = m3IndicatorView.getDrawable();
        } else if (style == 2 || style == 3) {
            if (m3CircularProgressIndicator == null) {
                m3CircularProgressIndicator = new CircularProgressIndicator(getContext());
                m3CircularProgressIndicator.setIndeterminate(true);
            }
            m3CircularProgressIndicator.setIndicatorColor(progressColor);
            m3CircularProgressIndicator.setIndicatorSize(size);
            m3CircularProgressIndicator.setTrackThickness((int) progressPaint.getStrokeWidth());
            m3CircularProgressIndicator.setTrackCornerRadius(AndroidUtilities.dp(2));
            m3CircularProgressIndicator.setIndicatorTrackGapSize(AndroidUtilities.dp(2));
            setWavy(currentStyle == 3);
            m3Drawable = m3CircularProgressIndicator.getIndeterminateDrawable();
        } else {
            m3Drawable = null;
        }
        if (oldDrawable != null && oldDrawable != m3Drawable) {
            oldDrawable.setVisible(false, false);
            oldDrawable.setCallback(null);
        }
        if (m3Drawable != null) {
            m3Drawable.setCallback(this);
            setM3Visible(isShown(), true);
        }
        invalidate();
    }

    public boolean isMaterial3ProgressStyle() {
        return currentStyle == 2 || currentStyle == 3;
    }

    @Keep
    public void setSpecValues(int indicatorSize, int trackThickness, int trackCornerRadius, int trackGapSize, int indicatorColor, int trackColor) {
        if (isMaterial3ProgressStyle()) {
            m3CircularProgressIndicator.setIndicatorSize(indicatorSize);
            m3CircularProgressIndicator.setTrackThickness(trackThickness);
            m3CircularProgressIndicator.setTrackCornerRadius(trackCornerRadius);
            m3CircularProgressIndicator.setIndicatorTrackGapSize(trackGapSize);
            m3CircularProgressIndicator.setIndicatorColor(indicatorColor);
            m3CircularProgressIndicator.setTrackColor(trackColor);
        }
    }

    @Keep
    public void setWavy(boolean wavy) {
        m3CircularProgressIndicator.setIndicatorInset(0);
        if (wavy) {
            setWavyValues(AndroidUtilities.dp(15), AndroidUtilities.dp(1.6f), AndroidUtilities.dp(5), 0.05f);
        } else {
            setWavyValues(0, 0, 0, 1f);
        }
    }

    public void setWavyValues(int wavelength, int amplitude, int speed, float rampProgressMin) {
        if (currentStyle != 3) {
            return;
        }
        m3CircularProgressIndicator.setWavelengthIndeterminate(wavelength);
        m3CircularProgressIndicator.setWaveAmplitude(amplitude);
        m3CircularProgressIndicator.setWaveSpeed(speed);
        m3CircularProgressIndicator.setWaveAmplitudeRampProgressMin(rampProgressMin);
    }

    public void setNoProgress(boolean value) {
        noProgress = value;
    }

    public void setProgress(float value) {
        currentProgress = value;
        if (animatedProgress > value) {
            animatedProgress = value;
        }
        progressAnimationStart = animatedProgress;
        progressTime = 0;
    }

    public void sync(RadialProgressView from) {
        lastUpdateTime = from.lastUpdateTime;
        radOffset = from.radOffset;
        toCircle = from.toCircle;
        toCircleProgress = from.toCircleProgress;
        noProgress = from.noProgress;
        currentCircleLength = from.currentCircleLength;
        drawingCircleLenght = from.drawingCircleLenght;
        currentProgressTime = from.currentProgressTime;
        currentProgress = from.currentProgress;
        progressTime = from.progressTime;
        animatedProgress = from.animatedProgress;
        risingCircleLength = from.risingCircleLength;
        progressAnimationStart = from.progressAnimationStart;
        updateAnimation(17 * 5);
    }

    private void updateAnimation() {
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        if (dt > 17) {
            dt = 17;
        }
        lastUpdateTime = newTime;
        updateAnimation(dt);
    }

    private void updateAnimation(long dt) {
        radOffset += 360 * dt / rotationTime;
        int count = (int) (radOffset / 360);
        radOffset -= count * 360;

        if (toCircle && toCircleProgress != 1f) {
            toCircleProgress += 16 / 220f;
            if (toCircleProgress > 1f) {
                toCircleProgress = 1f;
            }
        } else if (!toCircle && toCircleProgress != 0f) {
            toCircleProgress -= 16 / 400f;
            if (toCircleProgress < 0) {
                toCircleProgress = 0f;
            }
        }

        if (noProgress) {
            if (toCircleProgress == 0) {
                currentProgressTime += dt;
                if (currentProgressTime >= risingTime) {
                    currentProgressTime = risingTime;
                }
                if (risingCircleLength) {
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                } else {
                    currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                }

                if (currentProgressTime == risingTime) {
                    if (risingCircleLength) {
                        radOffset += 270;
                        currentCircleLength = -266;
                    }
                    risingCircleLength = !risingCircleLength;
                    currentProgressTime = 0;
                }
            } else {
                if (risingCircleLength) {
                    float old = currentCircleLength;
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                    currentCircleLength += 360 * toCircleProgress;
                    float dx = old - currentCircleLength;
                    if (dx > 0) {
                        radOffset += old - currentCircleLength;
                    }
                } else {
                    float old = currentCircleLength;
                    currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                    currentCircleLength -= 364 * toCircleProgress;
                    float dx = old - currentCircleLength;
                    if (dx > 0) {
                        radOffset += old - currentCircleLength;
                    }
                }
            }
        } else {
            float progressDiff = currentProgress - progressAnimationStart;
            if (progressDiff > 0) {
                progressTime += dt;
                if (progressTime >= 200.0f) {
                    animatedProgress = progressAnimationStart = currentProgress;
                    progressTime = 0;
                } else {
                    animatedProgress = progressAnimationStart + progressDiff * AndroidUtilities.decelerateInterpolator.getInterpolation(progressTime / 200.0f);
                }
            }
            currentCircleLength = Math.max(4, 360 * animatedProgress);
        }
        invalidate();
    }

    public void setSize(int value) {
        size = value;
        if (m3CircularProgressIndicator != null) {
            m3CircularProgressIndicator.setIndicatorSize(value);
        }
        invalidate();
    }

    public void setStrokeWidth(float value) {
        progressPaint.setStrokeWidth(AndroidUtilities.dp(value));
        if (m3CircularProgressIndicator != null) {
            m3CircularProgressIndicator.setTrackThickness(AndroidUtilities.dp(value));
        }
    }

    public void setProgressColor(int color) {
        progressColor = color;
        progressPaint.setColor(progressColor);
        if (m3IndicatorView != null) {
            m3IndicatorView.setIndicatorColor(progressColor);
        }
        if (m3CircularProgressIndicator != null) {
            m3CircularProgressIndicator.setIndicatorColor(progressColor);
        }
    }

    public void setTrackColor(int color) {
        if (m3CircularProgressIndicator != null) {
            m3CircularProgressIndicator.setTrackColor(color);
        }
    }

    public void toCircle(boolean toCircle, boolean animated) {
        this.toCircle = toCircle;
        if (!animated) {
            toCircleProgress = toCircle ? 1f : 0f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (currentStyle != 0 && m3Drawable != null) {
            final int x = (getMeasuredWidth() - size) / 2;
            final int y = (getMeasuredHeight() - size) / 2;
            m3Drawable.setBounds(x, y, x + size, y + size);
            m3Drawable.draw(canvas);
            return;
        }
        int x = (getMeasuredWidth() - size) / 2;
        int y = (getMeasuredHeight() - size) / 2;
        cicleRect.set(x, y, x + size, y + size);
        canvas.drawArc(cicleRect, radOffset, drawingCircleLenght = currentCircleLength, false, progressPaint);
        updateAnimation();
    }

    public void draw(Canvas canvas, float cx, float cy) {
        if (currentStyle != 0 && m3Drawable != null) {
            final int x = (int) (cx - size / 2f);
            final int y = (int) (cy - size / 2f);
            m3Drawable.setBounds(x, y, x + size, y + size);
            m3Drawable.draw(canvas);
            return;
        }
        cicleRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy +  size / 2f);
        canvas.drawArc(cicleRect, radOffset, drawingCircleLenght = currentCircleLength, false, progressPaint);
        updateAnimation();
    }

    public boolean isCircle() {
        return Math.abs(drawingCircleLenght) >= 360;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == m3Drawable || super.verifyDrawable(who);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        setM3Visible(isVisible, false);
    }

    @Override
    protected void onDetachedFromWindow() {
        setM3Visible(false, false);
        super.onDetachedFromWindow();
    }

    private void setM3Visible(boolean visible, boolean restart) {
        if (m3Drawable != null) {
            m3Drawable.setVisible(visible, restart && visible);
        }
    }
}
