package com.exteragram.messenger.pillstack.ui.pills.crypto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ColoredBackground;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ExchangeRates;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.PillStackCurrencies;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicReference;

@SuppressLint("ViewConstructor")
public abstract class RatePill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private static final long REFRESH_INTERVAL = 5 * 60 * 1000L;

    private final RateCache cache;
    private final String baseCurrency;
    private final int scale;
    private final int iconResId;
    private final ColoredBackground background;
    private final LinearLayout layout;
    private final ImageView iconView;
    private final AnimatedTextView textView;
    private boolean requestInFlight;

    public static final class RateCache {
        private final AtomicReference<String> cachedPrice = new AtomicReference<>();
        private final AtomicReference<String> cachedCurrency = new AtomicReference<>();
    }

    public RatePill(Context context, Theme.ResourcesProvider resourcesProvider, RateCache cache, String baseCurrency, int scale, int iconResId, ColoredBackground background) {
        super(context, resourcesProvider);
        this.cache = cache;
        this.baseCurrency = baseCurrency;
        this.scale = scale;
        this.iconResId = iconResId;
        this.background = background;

        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48));
        layout.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setIncludeFontPadding(false);
        textView.setTypeface(AndroidUtilities.bold());
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        String cachedPrice = cache.cachedPrice.get();
        if (cachedPrice != null) {
            setData(cachedPrice, false);
        }
    }

    @Override
    public long getRefreshInterval() {
        return REFRESH_INTERVAL;
    }

    public abstract String getTargetSelection();

    public abstract void setTargetSelection(String currency);

    @Override
    public void onPillClicked() {
        if (iconView.getVisibility() == View.VISIBLE && textView.getText() != null && TextUtils.equals(textView.getText(), LocaleController.getString(R.string.Retry))) {
            onUpdateData(true);
        } else {
            onPillLongClicked();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pillStackSettingsChanged && PillStackConfig.shouldUpdatePill(args, getPillId()) && getTargetSelection().equals("AUTO")) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    @Override
    public boolean onPillLongClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(fragment, this, true);
        ItemOptions currencyOptions = options.makeSwipeback(true)
            .add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), options::closeSwipeback)
            .addGap();
        String currentSelection = getTargetSelection();
        for (String currency : getTargetCurrencies()) {
            currencyOptions.addChecked(currency.equalsIgnoreCase(currentSelection), PillStackCurrencies.getTargetCurrencyLabel(currency), () -> {
                options.dismiss();
                if (currency.equalsIgnoreCase(currentSelection)) {
                    return;
                }
                setTargetSelection(currency);
                onUpdateData(false);
            });
        }

        ActionBarMenuSubItem currencyItem = new ActionBarMenuSubItem(options.getContext(), false, false, resourcesProvider);
        currencyItem.setTextAndIcon(LocaleController.getString(R.string.CryptoPillTargetCurrency), R.drawable.msg_language);
        currencyItem.setSubtext(PillStackCurrencies.getTargetCurrencySubtext(getTargetSelection()));
        currencyItem.setItemHeight(56);
        currencyItem.setOnClickListener(v -> options.openSwipeback(currencyOptions));

        options.add(currencyItem)
            .addGap()
            .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
            .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), () -> fragment.presentFragment(new PillStackPreferencesActivity()))
            .setSwipebackGravity(!LocaleController.isRTL, false)
            .forceBelowScrim(true)
            .setDrawScrim(false)
            .setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)
            .setDimAlpha(0)
            .show();
        return true;
    }

    @Override
    public void onUpdateData(boolean force) {
        String targetCurrency = ExchangeRates.resolveTargetCurrency(UserConfig.selectedAccount, getTargetSelection());
        String cachedPrice = cache.cachedPrice.get();
        if (!TextUtils.equals(targetCurrency, cache.cachedCurrency.get())) {
            cachedPrice = null;
        }
        if (!force && cachedPrice != null && !isRefreshDue()) {
            setData(cachedPrice, false);
            return;
        }
        if (requestInFlight) {
            return;
        }
        requestInFlight = true;
        if (force) {
            animateSizeChange();
        }
        startLoading();
        if (cachedPrice == null && cache.cachedPrice.get() == null) {
            iconView.setVisibility(View.GONE);
            textView.setVisibility(View.GONE);
        } else {
            iconView.setImageResource(iconResId);
            iconView.setVisibility(View.VISIBLE);
            textView.setVisibility(View.VISIBLE);
        }
        if (force) {
            ExchangeRates.clearCache();
        }
        ExchangeRates.fetch(state -> {
            requestInFlight = false;
            BigDecimal rate = state != null ? state.getRate(baseCurrency, targetCurrency) : null;
            if (rate == null) {
                String lastPrice = cache.cachedPrice.get();
                if (lastPrice != null) {
                    setData(lastPrice, true);
                } else {
                    setErrorState(true);
                }
                return;
            }
            String price = formatPrice(rate, targetCurrency);
            cache.cachedPrice.set(price);
            cache.cachedCurrency.set(targetCurrency);
            setData(price, true);
            markDataUpdated();
        });
    }

    public String formatPrice(BigDecimal rate, String currency) {
        String fiatPrice = PillStackCurrencies.formatFiatPrice(rate, currency);
        if (fiatPrice != null) {
            return fiatPrice;
        }
        return rate.setScale(scale, RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }

    public String[] getTargetCurrencies() {
        return PillStackCurrencies.TARGET_CURRENCIES;
    }

    private void setErrorState(boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(R.drawable.msg_retry);
        iconView.setVisibility(View.VISIBLE);
        textView.setText(LocaleController.getString(R.string.Retry), animated);
        textView.setVisibility(View.VISIBLE);
    }

    private void setData(String price, boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(iconResId);
        iconView.setVisibility(View.VISIBLE);
        textView.setText(price, animated);
        textView.setVisibility(View.VISIBLE);
    }

    @Override
    public void setPressed(boolean pressed) {
        if (loading) {
            pressed = false;
        }
        super.setPressed(pressed);
        layout.setPressed(pressed);
    }

    @Override
    public void updateColors() {
        layout.setBackground(background);
        textView.setTextColor(Color.WHITE);
        iconView.setColorFilter(Color.WHITE);
        updateLoadingColors();
    }

    @Override
    public void updateLoadingColors() {
        if (loadingDrawable != null) {
            loadingDrawable.setColors(Theme.multAlpha(Color.WHITE, 0.1f), Theme.multAlpha(Color.WHITE, 0.3f), Theme.multAlpha(Color.WHITE, 0.2f), Theme.multAlpha(Color.WHITE, 0.45f));
        }
    }
}
