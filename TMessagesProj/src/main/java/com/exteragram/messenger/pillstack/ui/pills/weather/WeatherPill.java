package com.exteragram.messenger.pillstack.ui.pills.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.core.PillType;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.Weather;

@SuppressLint("ViewConstructor")
public class WeatherPill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private static final long REFRESH_INTERVAL = 20 * 60 * 1000L;

    private final LinearLayout layout;
    private final ImageView iconView;
    private final AnimatedTextView textView;
    private boolean showingWeather;

    public WeatherPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);

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
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.adaptWidth = true;
        NotificationCenter.listenEmojiLoading(textView);
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        Weather.State cached = Weather.getCached();
        if (cached != null) {
            setData(cached, false);
        }
    }

    @Override
    public long getRefreshInterval() {
        return REFRESH_INTERVAL;
    }

    @Override
    public int getPillId() {
        return PillType.WEATHER.getId();
    }

    @Override
    public void onPillClicked() {
        if (PillStackConfig.getUseCurrentLocation() && !showingWeather && (!Weather.isLocationPermissionGranted() || !Weather.isLocationEnabled())) {
            requestLocationAndUpdate();
        } else {
            onPillLongClicked();
        }
    }

    @Override
    public boolean onPillLongClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, this)
            .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
            .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), () -> fragment.presentFragment(new WeatherSettingsActivity()))
            .setDrawScrim(false)
            .setDimAlpha(0)
            .show();
        return true;
    }

    @Override
    public void onUpdateData(boolean force) {
        if (PillStackConfig.getUseCurrentLocation()) {
            if (!Weather.isLocationPermissionGranted()) {
                setLocationState(R.string.WeatherLocationPermissionGrant, showingWeather);
                return;
            } else if (!Weather.isLocationEnabled()) {
                setLocationState(R.string.WeatherLocationServicesEnable, showingWeather);
                return;
            }
        }
        if (force) {
            Weather.clearCache();
        }
        startLoading();
        Weather.fetchExtera(state -> {
            if (state != null) {
                markDataUpdated();
                postDelayed(() -> setData(state, true), 300);
            } else {
                postDelayed(() -> setErrorState(true), 300);
            }
        });
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
        if (id == NotificationCenter.pillStackSettingsChanged && PillStackConfig.shouldUpdatePill(args, getPillId())) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    private void setLocationState(int textRes, boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(R.drawable.filled_location);
        iconView.setVisibility(View.VISIBLE);
        textView.setText(LocaleController.getString(textRes), animated);
        showingWeather = false;
    }

    private void requestLocationAndUpdate() {
        Weather.getUserLocation(true, location -> {
            if (location != null) {
                onUpdateData(true);
            }
        });
    }

    private void setErrorState(boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(R.drawable.msg_retry);
        iconView.setVisibility(View.VISIBLE);
        textView.setText(LocaleController.getString(R.string.Retry), animated);
        showingWeather = false;
    }

    public void setData(Weather.State state, boolean animated) {
        stopLoading();
        if (state == null) {
            return;
        }
        if (animated) {
            animateSizeChange();
        }
        int iconRes = getWeatherIconRes(state.getEmoji());
        if (iconRes != 0) {
            iconView.setImageResource(iconRes);
            iconView.setVisibility(View.VISIBLE);
            textView.setText(state.getTemperature(), animated);
        } else {
            iconView.setVisibility(View.GONE);
            textView.setText(Emoji.replaceEmoji(String.format("%s %s", state.getEmoji(), state.getTemperature()), textView.getPaint().getFontMetricsInt(), true), animated);
        }
        showingWeather = true;
    }

    private int getWeatherIconRes(String emoji) {
        if (emoji == null) {
            return 0;
        }
        switch (emoji) {
            case "☀":
                return R.drawable.weather_sunny;
            case "☁":
                return R.drawable.weather_cloudy;
            case "⚡":
            case "⛈":
                return R.drawable.weather_thunderstorm;
            case "⛅":
            case "🌤":
                return R.drawable.weather_partly_cloudy;
            case "❄":
            case "🌨":
                return R.drawable.weather_snowy;
            case "🌓":
            case "🌔":
            case "🌖":
            case "🌗":
            case "🌚":
            case "🌛":
            case "🌜":
            case "🌝":
                return R.drawable.weather_night;
            case "🌦":
            case "🌧":
                return R.drawable.weather_rainy;
            case "😶‍🌫":
                return R.drawable.weather_foggy;
            default:
                return 0;
        }
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
    public void drawableHotspotChanged(float x, float y) {
        if (loading) {
            return;
        }
        super.drawableHotspotChanged(x, y);
        layout.drawableHotspotChanged(x - layout.getLeft(), y - layout.getTop());
    }

    @Override
    public void updateColors() {
        int color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        int backgroundColor = Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f);
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14), backgroundColor, Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
        iconView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        updateLoadingColors();
    }
}
