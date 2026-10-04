package com.exteragram.messenger.adblock.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.adblock.WebAdBlocker;
import com.exteragram.messenger.adblock.backend.AdBlockManager;
import com.exteragram.messenger.utils.ui.SwitchUiHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

@SuppressLint("ViewConstructor")
public class AdBlockMenuItem extends ActionBarMenuSubItem {

    private static final int[] BLOCKED_COUNT_SAMPLES = {991, 992, 999};

    private final WebAdBlocker adBlocker;
    private final ItemOptions options;
    private final Switch switchView;
    private CharSequence subtext;
    private boolean toggled;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (toggled) {
                return;
            }
            update();
            AndroidUtilities.runOnUIThread(this, 500);
        }
    };

    public static void addTo(ItemOptions options, WebAdBlocker adBlocker) {
        if (adBlocker == null || !AdBlockManager.isAvailable()) {
            return;
        }
        if (ExteraConfig.getEnableAdBlock() && !AdBlockManager.isActive()) {
            AdBlockManager.initialize();
        }
        AdBlockMenuItem item = new AdBlockMenuItem(options.getContext(), adBlocker, options);
        options.add(item);
        item.alignSwitch();
        item.fitWidth();
        options.addGap();
    }

    private AdBlockMenuItem(Context context, WebAdBlocker adBlocker, ItemOptions options) {
        super(context, false, false, (Theme.ResourcesProvider) null);
        this.adBlocker = adBlocker;
        this.options = options;
        setTextAndIcon(LocaleController.getString(R.string.BlockAds), R.drawable.msg_policy);
        setClipToPadding(false);

        switchView = new Switch(context);
        switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        switchView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        switchView.setChecked(ExteraConfig.getEnableAdBlock(), false);
        if (SwitchUiHelper.isMaterial3SwitchStyle()) {
            switchView.setScaleX(0.85f);
            switchView.setScaleY(0.85f);
        }
        addView(switchView, LayoutHelper.createFrame(44, 28, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        setOnClickListener(v -> toggle());
        update();
    }

    private void alignSwitch() {
        float switchWidth;
        float switchHeight;
        if (SwitchUiHelper.isMaterial3SwitchStyle()) {
            RectF bounds = new RectF();
            SwitchUiHelper.setTrackBounds(bounds, 0, 0);
            switchWidth = bounds.width() * 0.85f;
            switchHeight = bounds.height() * 0.85f;
        } else {
            switchWidth = AndroidUtilities.dp(37);
            switchHeight = AndroidUtilities.dp(20);
        }
        float inset = (AndroidUtilities.dp(subtext != null ? 56 : 48) - switchHeight) / 2f;
        float padding = LocaleController.isRTL ? getPaddingLeft() : getPaddingRight();
        int margin = Math.round((switchWidth - AndroidUtilities.dp(44)) / 2f + inset - padding);
        int textPadding = Math.round(inset + switchWidth + AndroidUtilities.dp(12) - padding);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) switchView.getLayoutParams();
        if (LocaleController.isRTL) {
            params.leftMargin = margin;
            textView.setPadding(textPadding, 0, textView.getPaddingRight(), 0);
        } else {
            params.rightMargin = margin;
            textView.setPadding(textView.getPaddingLeft(), 0, textPadding, 0);
        }
        if (subtextView != null) {
            subtextView.setPadding(textView.getPaddingLeft(), 0, textView.getPaddingRight(), 0);
        }
    }

    private void fitWidth() {
        int minimumWidth = getMinimumWidth();
        if (minimumWidth <= 0) {
            return;
        }
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(AndroidUtilities.dp(13));
        float subtextWidth = paint.measureText(LocaleController.getString(R.string.AdBlockFiltersLoading));
        for (int count : BLOCKED_COUNT_SAMPLES) {
            subtextWidth = Math.max(subtextWidth, paint.measureText(LocaleController.formatPluralString("BlockedRequests", count)));
        }
        int width = Math.min(
                getPaddingLeft() + getPaddingRight() + textView.getPaddingLeft() + textView.getPaddingRight() + (int) Math.ceil(Math.max(textView.getPaint().measureText(textView.getText().toString()), subtextWidth)),
                AndroidUtilities.displaySize.x - AndroidUtilities.dp(32)
        );
        if (width > minimumWidth) {
            int widthDp = (int) Math.ceil(width / AndroidUtilities.density);
            options.setMinWidth(widthDp);
            for (int i = 0; i < options.getItemsCount(); i++) {
                View child = options.getItemAt(i);
                if (child instanceof ActionBarMenuSubItem) {
                    child.setMinimumWidth(AndroidUtilities.dp(widthDp));
                    child.getLayoutParams().width = AndroidUtilities.dp(widthDp);
                }
            }
        }
    }

    private void update() {
        String text;
        if (!ExteraConfig.getEnableAdBlock()) {
            text = null;
        } else if (AdBlockManager.isActive()) {
            text = LocaleController.formatPluralString("BlockedRequests", adBlocker.getBlockedCount());
        } else {
            text = LocaleController.getString(R.string.AdBlockFiltersLoading);
        }
        if (subtextView == null || !TextUtils.equals(subtext, text)) {
            subtext = text;
            setItemHeight(text != null ? 56 : 48);
            setSubtext(subtext);
        }
    }

    private void toggle() {
        if (toggled) {
            return;
        }
        toggled = true;
        boolean enabled = ExteraConfig.getEnableAdBlock();
        switchView.setChecked(!enabled, true);
        if (!enabled) {
            AdBlockManager.setEnabled(true, adBlocker::reload);
        } else {
            AdBlockManager.setEnabled(false, null);
            adBlocker.reload();
        }
        AndroidUtilities.runOnUIThread(options::dismiss, 200);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AndroidUtilities.runOnUIThread(updateRunnable, 500);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(updateRunnable);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(switchView.isChecked());
    }
}
