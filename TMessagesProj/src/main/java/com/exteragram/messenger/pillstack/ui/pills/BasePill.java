package com.exteragram.messenger.pillstack.ui.pills;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.exteragram.messenger.pillstack.core.PillStackConfig;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LoadingDrawable;

public abstract class BasePill extends FrameLayout {

    private static final SparseArray<Long> globalLastUpdateTimes = new SparseArray<>();

    protected Theme.ResourcesProvider resourcesProvider;
    protected boolean loading;
    protected LoadingDrawable loadingDrawable;
    protected View loadingTargetView;

    private final RectF rectF = new RectF();
    private boolean stackVisible = true;
    private final Runnable autoRefreshRunnable = () -> {
        onUpdateData(false);
        scheduleNextUpdate();
    };

    public BasePill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setLayoutParams(new FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));
        setClipChildren(false);
        setClipToPadding(false);
    }

    public abstract int getPillId();

    public abstract long getRefreshInterval();

    public abstract void onPillClicked();

    public abstract boolean onPillLongClicked();

    public void onPillSelected() {

    }

    public void onPillUnselected() {

    }

    public abstract void onUpdateData(boolean force);

    public abstract void updateColors();

    private void scheduleNextUpdate() {
        removeCallbacks(autoRefreshRunnable);
        if (stackVisible) {
            long refreshInterval = getRefreshInterval();
            if (refreshInterval > 0) {
                postDelayed(autoRefreshRunnable, refreshInterval);
            }
        }
    }

    public boolean isRefreshDue() {
        long refreshInterval = getRefreshInterval();
        if (refreshInterval <= 0) {
            return true;
        }
        long lastUpdateTime = globalLastUpdateTimes.get(getPillId(), 0L);
        return lastUpdateTime == 0 || SystemClock.elapsedRealtime() - lastUpdateTime >= refreshInterval;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        long refreshInterval = getRefreshInterval();
        if (refreshInterval <= 0) {
            return;
        }
        if (PillStackConfig.checkAndClearPendingUpdate(getPillId())) {
            onUpdateData(true);
            scheduleNextUpdate();
        } else if (stackVisible) {
            long lastUpdateTime = globalLastUpdateTimes.get(getPillId(), 0L);
            if (lastUpdateTime != 0) {
                long elapsed = SystemClock.elapsedRealtime() - lastUpdateTime;
                if (elapsed < refreshInterval) {
                    postDelayed(autoRefreshRunnable, refreshInterval - elapsed);
                    return;
                }
            }
            autoRefreshRunnable.run();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(autoRefreshRunnable);
    }

    public void onStackVisibilityChanged(boolean visible) {
        if (stackVisible == visible) {
            return;
        }
        stackVisible = visible;
        if (visible) {
            if (getRefreshInterval() <= 0 || !isAttachedToWindow()) {
                return;
            }
            if (isRefreshDue()) {
                onUpdateData(false);
            }
            scheduleNextUpdate();
            return;
        }
        removeCallbacks(autoRefreshRunnable);
    }

    public void markDataUpdated() {
        globalLastUpdateTimes.put(getPillId(), SystemClock.elapsedRealtime());
        scheduleNextUpdate();
    }

    public void setLoadingTargetView(View view) {
        loadingTargetView = view;
    }

    public void startLoading() {
        loading = true;
        if (loadingDrawable == null) {
            loadingDrawable = new LoadingDrawable(resourcesProvider);
            loadingDrawable.setCallback(this);
            loadingDrawable.setGradientScale(2f);
            loadingDrawable.setRadiiDp(14);
            updateLoadingColors();
        }
        loadingDrawable.reset();
        loadingDrawable.resetDisappear();
        loadingDrawable.setAlpha(255);
        invalidate();
    }

    public void animateSizeChange() {
        if (isLaidOut() && getVisibility() == View.VISIBLE && getParent() != null && getParent().getParent() instanceof ViewGroup) {
            TransitionManager.beginDelayedTransition((ViewGroup) getParent().getParent(), new TransitionSet()
                .addTransition(new ChangeBounds())
                .setDuration(300)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT));
        }
    }

    public void stopLoading() {
        loading = false;
        if (loadingDrawable != null) {
            loadingDrawable.disappear();
        }
    }

    public void updateLoadingColors() {
        if (loadingDrawable != null) {
            int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            loadingDrawable.setColors(Theme.multAlpha(color, 0.05f), Theme.multAlpha(color, 0.15f), Theme.multAlpha(color, 0.1f), Theme.multAlpha(color, 0.25f));
        }
    }

    public int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public int getThemedColor(int key, float alpha) {
        return Theme.multAlpha(getThemedColor(key), alpha);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (loadingDrawable != null && (loadingDrawable.getAlpha() > 0 || !loadingDrawable.isDisappearing())) {
            View target = loadingTargetView != null ? loadingTargetView : this;
            rectF.set(target.getLeft(), target.getTop(), target.getRight(), target.getBottom());
            if (loadingDrawable.stroke) {
                float inset = loadingDrawable.strokePaint.getStrokeWidth() / 2f;
                rectF.inset(inset, inset);
            }
            loadingDrawable.setBounds(rectF);
            loadingDrawable.draw(canvas);
            invalidate();
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == loadingDrawable || super.verifyDrawable(who);
    }
}
