package com.exteragram.messenger.pillstack.ui.pills.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.core.PillType;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ChatAttachAlertLocationLayout;
import org.telegram.ui.Components.ClipRoundedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LocationActivity;
import org.telegram.ui.Stories.recorder.Weather;

import java.util.ArrayList;

public class WeatherSettingsActivity extends BasePreferencesActivity {

    private static final int ID_PILLS = 1;
    private static final int ID_CURRENT_LOCATION = 2;
    private static final int ID_SELECT_LOCATION = 3;
    private static final int ID_ENABLE_LOCATION = 4;
    private static final int ID_MAP_PREVIEW = 5;
    private static final int ID_ADDRESS = 6;

    private TLRPC.GeoPoint currentGeo;
    private FrameLayout addressContainer;
    private TextView addressText;
    private FrameLayout mapPreviewContainer;
    private BackupImageView mapPreview;
    private ClipRoundedDrawable mapLoadingDrawable;
    private View mapMarker;

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.WeatherPill);
    }

    @Override
    public View createView(Context context) {
        addressText = new TextView(context);
        addressText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        addressText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        addressText.setGravity(Gravity.CENTER);
        addressText.setPadding(0, 0, 0, 0);
        addressContainer = new FrameLayout(context);
        addressContainer.addView(addressText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 15, 21, 15));
        addressContainer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        mapPreview = new BackupImageView(context) {
            @Override
            protected ImageReceiver createImageReciever() {
                return new ImageReceiver(this) {
                    @Override
                    protected boolean setImageBitmapByKey(Drawable drawable, String key, int type, boolean memCache, int guid) {
                        if (drawable != null && type != TYPE_THUMB) {
                            mapMarker.animate().alpha(1f).translationY(0).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).setDuration(250).start();
                        }
                        return super.setImageBitmapByKey(drawable, key, type, memCache, guid);
                    }
                };
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(240), MeasureSpec.EXACTLY));
            }

            @Override
            protected boolean verifyDrawable(@NonNull Drawable who) {
                return who == mapLoadingDrawable || super.verifyDrawable(who);
            }
        };
        SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(R.raw.map_placeholder, Theme.key_chat_outLocationIcon, .2f);
        svgThumb.setColorKey(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider());
        svgThumb.setAspectCenter(true);
        svgThumb.setParent(mapPreview.getImageReceiver());
        mapLoadingDrawable = new ClipRoundedDrawable(svgThumb);
        mapLoadingDrawable.setCallback(mapPreview);
        mapPreview.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        mapMarker = new View(context) {
            final Drawable pin = getContext().getResources().getDrawable(R.drawable.map_pin_photo).mutate();
            final AvatarDrawable avatarDrawable = new AvatarDrawable();
            final ImageReceiver avatarImage = new ImageReceiver(this);

            {
                avatarDrawable.setInfo(getUserConfig().getCurrentUser());
                avatarImage.setForUserOrChat(getUserConfig().getCurrentUser(), avatarDrawable);
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                pin.setBounds(0, 0, AndroidUtilities.dp(62), AndroidUtilities.dp(85));
                pin.draw(canvas);
                avatarImage.setRoundRadius(AndroidUtilities.dp(62));
                avatarImage.setImageCoords(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(50), AndroidUtilities.dp(50));
                avatarImage.draw(canvas);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(62), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(85), MeasureSpec.EXACTLY));
            }
        };

        mapPreviewContainer = new FrameLayout(context);
        mapPreviewContainer.addView(mapPreview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        mapPreviewContainer.addView(mapMarker, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, -31, 0, 0));
        mapPreviewContainer.setOnClickListener(v -> openMapPicker());

        if (PillStackConfig.getCustomWeatherLocation() != null) {
            try {
                currentGeo = ExteraConfig.getGSON().fromJson(PillStackConfig.getCustomWeatherLocation(), TLRPC.TL_geoPoint.class);
            } catch (Exception ignore) {
            }
        }
        updateMapPreview();

        return super.createView(context);
    }

    private void updateMapPreview() {
        if (mapMarker == null || mapPreview == null) {
            return;
        }
        if (currentGeo != null) {
            mapMarker.setAlpha(0f);
            mapMarker.setTranslationY(-AndroidUtilities.dp(12));
            final int w = (int) ((mapPreview.getMeasuredWidth() <= 0 ? AndroidUtilities.displaySize.x : mapPreview.getMeasuredWidth()) / AndroidUtilities.density);
            final int h = 240;
            final int scale = Math.min(2, (int) Math.ceil(AndroidUtilities.density));
            mapPreview.setImage(ImageLocation.getForWebFile(WebFile.createWithGeoPoint(currentGeo.lat, currentGeo._long, 0, scale * w, scale * h, 15, scale)), w + "_" + h, mapLoadingDrawable, 0, null);
            if (addressText != null) {
                addressText.setText(PillStackConfig.getCustomWeatherAddress());
            }
        } else {
            mapPreview.setImageBitmap(null);
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.WeatherPillTopInfo), "RestrictedEmoji", "🌤"));
        items.add(UItem.asButton(ID_PILLS, R.drawable.msg_settings_old, LocaleController.getString(R.string.PillStackPills)));
        items.add(UItem.asShadow());
        if (!PillStackConfig.getActivePills().contains(PillType.WEATHER.getId())) {
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.WeatherLocation)));
        items.add(UItem.asRadio(ID_CURRENT_LOCATION, LocaleController.getString(R.string.CurrentLocation)).setChecked(PillStackConfig.getUseCurrentLocation()));
        items.add(UItem.asRadio(ID_SELECT_LOCATION, LocaleController.getString(R.string.SelectLocation)).setChecked(!PillStackConfig.getUseCurrentLocation()));
        if (PillStackConfig.getUseCurrentLocation()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.WeatherSettingsInfo)));
            if (!Weather.isLocationPermissionGranted()) {
                items.add(UItem.asButton(ID_CURRENT_LOCATION, R.drawable.report, LocaleController.getString(R.string.WeatherLocationPermissionGrant)).red());
                items.add(UItem.asShadow(LocaleController.getString(R.string.WeatherLocationPermissionNo)));
            } else if (!Weather.isLocationEnabled()) {
                items.add(UItem.asButton(ID_ENABLE_LOCATION, R.drawable.filled_location, LocaleController.getString(R.string.WeatherLocationServicesEnable)).accent());
                items.add(UItem.asShadow(LocaleController.getString(R.string.GpsDisabledAlertText)));
            }
            return;
        }
        items.add(UItem.asCustom(ID_MAP_PREVIEW, mapPreviewContainer));
        if (!TextUtils.isEmpty(PillStackConfig.getCustomWeatherAddress())) {
            items.add(UItem.asCustom(ID_ADDRESS, addressContainer));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.WeatherSettingsInfo)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_PILLS:
                presentFragment(new PillStackPreferencesActivity());
                break;
            case ID_CURRENT_LOCATION:
                if (PillStackConfig.getUseCurrentLocation() && Weather.isLocationPermissionGranted()) {
                    return;
                }
                Weather.getUserLocation(true, location -> {
                    if (location != null) {
                        updateLocationSetting(true);
                    }
                });
                break;
            case ID_ENABLE_LOCATION:
                Weather.getUserLocation(true, location -> {
                    if (location != null && fragmentView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                });
                break;
            case ID_SELECT_LOCATION:
                if (PillStackConfig.getUseCurrentLocation()) {
                    if (currentGeo == null) {
                        TLRPC.TL_geoPoint geoPoint = new TLRPC.TL_geoPoint();
                        geoPoint.lat = 55.7558;
                        geoPoint._long = 37.6173;
                        currentGeo = geoPoint;
                        PillStackConfig.setCustomWeatherLocation(ExteraConfig.getGSON().toJson(currentGeo));
                    }
                    updateLocationSetting(false);
                }
                openMapPicker();
                break;
            case ID_MAP_PREVIEW:
            case ID_ADDRESS:
                openMapPicker();
                break;
        }
    }

    private void updateLocationSetting(boolean useCurrentLocation) {
        PillStackConfig.setUseCurrentLocation(useCurrentLocation);
        PillStackConfig.notifySettingsChanged(PillType.WEATHER.getId());
        if (fragmentView != null) {
            updateMapPreview();
            if (listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
    }

    private void openMapPicker() {
        LocationActivity locationActivity = new LocationActivity(ChatAttachAlertLocationLayout.LOCATION_TYPE_BIZ);
        if (currentGeo != null) {
            TLRPC.TL_channelLocation initialLocation = new TLRPC.TL_channelLocation();
            initialLocation.geo_point = currentGeo;
            initialLocation.address = PillStackConfig.getCustomWeatherAddress();
            locationActivity.setInitialLocation(initialLocation);
        }
        locationActivity.setDelegate((location, live, notify, scheduleDate, payStars) -> {
            currentGeo = location.geo;
            String address = locationActivity.getAddressName();
            if (address == null) {
                address = "";
            }
            PillStackConfig.setCustomWeatherLocation(ExteraConfig.getGSON().toJson(currentGeo));
            PillStackConfig.setCustomWeatherAddress(address);
            updateLocationSetting(false);
        });
        presentFragment(locationActivity);
    }
}
