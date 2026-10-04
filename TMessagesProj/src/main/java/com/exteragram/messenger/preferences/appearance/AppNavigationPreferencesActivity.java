package com.exteragram.messenger.preferences.appearance;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.MainMenuItem;
import com.exteragram.messenger.TransitionAnimation;
import com.exteragram.messenger.config.BottomNavigationBar;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.components.AltSeekbar;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class AppNavigationPreferencesActivity extends BasePreferencesActivity {

    private static final int FIRST_DIVIDER_ID = -2000;
    private static final int ADD_DIVIDER_ID = -200;
    private static final float MAX_PREDICTIVE_BACK_INTENSITY = 2f;

    private CharSequence[] bottomNavigationModes;
    private CharSequence[] tabletMode;
    private CharSequence[] transitionAnimations;
    private AltSeekbar predictiveBackSeekbar;
    private Drawable reorderIcon;
    private ActionBarMenuItem resetItem;

    private final HashMap<Integer, ItemInfo> itemDetails = new HashMap<>();
    private final ArrayList<Integer> stableDividerIds = new ArrayList<>();
    private int nextDividerId = FIRST_DIVIDER_ID;

    public enum AppNavigationItem {
        DRAWER,
        IMMERSIVE_ANIMATION,
        BOTTOM_NAVIGATION_BAR_MODE,
        PREDICTIVE_BACK_ANIMATION,
        TRANSITION_ANIMATION,
        TABLET_MODE;

        private static final int ID_OFFSET = 150;

        public int getId() {
            return ordinal() + ID_OFFSET;
        }

        public static AppNavigationItem fromId(int id) {
            int index = id - ID_OFFSET;
            if (index < 0 || index >= values().length) {
                return null;
            }
            return values()[index];
        }
    }

    public static class ItemInfo {
        int iconRes;
        CharSequence name;

        public ItemInfo(CharSequence name, int iconRes) {
            this.name = name;
            this.iconRes = iconRes;
        }
    }

    @Override
    public void initializeOptionStrings() {
        initItemDetails();
        tabletMode = new CharSequence[]{
                LocaleController.getString(R.string.DistanceUnitsAutomatic),
                LocaleController.getString(R.string.PasswordOn),
                LocaleController.getString(R.string.PasswordOff)
        };
        bottomNavigationModes = new CharSequence[]{
                LocaleController.getString(R.string.BottomNavigationModeShow),
                LocaleController.getString(R.string.BottomNavigationModeHide),
                LocaleController.getString(R.string.BottomNavigationModeFloating)
        };
        transitionAnimations = new CharSequence[]{
                LocaleController.getString(R.string.Default),
                LocaleController.getString(R.string.TransitionAnimationAosp),
                LocaleController.getString(R.string.TransitionAnimationSpring)
        };
    }

    private void initItemDetails() {
        itemDetails.put(MainMenuItem.PROFILE.getId(), new ItemInfo(LocaleController.getString(R.string.MyProfile), R.drawable.left_status_profile));
        itemDetails.put(MainMenuItem.ARCHIVE.getId(), new ItemInfo(LocaleController.getString(R.string.ArchivedChats), R.drawable.msg_archive));
        itemDetails.put(MainMenuItem.BOTS.getId(), new ItemInfo(LocaleController.getString(R.string.FilterBots), R.drawable.msg_bot));
        itemDetails.put(MainMenuItem.NEW_GROUP.getId(), new ItemInfo(LocaleController.getString(R.string.NewGroup), R.drawable.msg_groups));
        itemDetails.put(MainMenuItem.CONTACTS.getId(), new ItemInfo(LocaleController.getString(R.string.Contacts), R.drawable.msg_contacts));
        itemDetails.put(MainMenuItem.NEW_CHANNEL.getId(), new ItemInfo(LocaleController.getString(R.string.NewChannel), R.drawable.msg_channel));
        itemDetails.put(MainMenuItem.CALLS.getId(), new ItemInfo(LocaleController.getString(R.string.Calls), R.drawable.msg_calls));
        itemDetails.put(MainMenuItem.SAVED.getId(), new ItemInfo(LocaleController.getString(R.string.SavedMessages), R.drawable.msg_saved));
        itemDetails.put(MainMenuItem.FEED.getId(), new ItemInfo(LocaleController.getString(R.string.Feed), R.drawable.ic_feed));
        itemDetails.put(MainMenuItem.SETTINGS.getId(), new ItemInfo(LocaleController.getString(R.string.Settings), R.drawable.msg_settings_old));
        itemDetails.put(MainMenuItem.PLUGINS.getId(), new ItemInfo(LocaleController.getString(R.string.Plugins), R.drawable.msg_plugins));
        itemDetails.put(MainMenuItem.BROWSER.getId(), new ItemInfo(LocaleController.getString(R.string.BrowserSettingsTitle), R.drawable.msg2_language));
        itemDetails.put(MainMenuItem.QR.getId(), new ItemInfo(LocaleController.getString(R.string.AuthAnotherClient), R.drawable.msg_qrcode));
        ExteraConfig.getMainMenuHiddenItems().removeIf(id -> id == MainMenuItem.DIVIDER.getId());
        rebuildStableDividerIds();
    }

    private void rebuildStableDividerIds() {
        stableDividerIds.clear();
        nextDividerId = FIRST_DIVIDER_ID;
        for (Integer id : ExteraConfig.getMainMenuLayout()) {
            if (id == MainMenuItem.DIVIDER.getId()) {
                stableDividerIds.add(nextDividerId--);
            }
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.AppNavigation);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        if (Build.VERSION.SDK_INT >= 34) {
            predictiveBackSeekbar = new AltSeekbar(context, this::onPredictiveBackIntensityChanged, 0, 2, LocaleController.getString(R.string.PredictiveBackIntensity), LocaleController.getString(R.string.BlurOff), LocaleController.getString(R.string.PredictiveBackMax)) {
                @Override
                public boolean useExactEndpointHaptic() {
                    return true;
                }

                @Override
                public CharSequence getTextForHeader() {
                    float value = Math.round(currentValue * 10f) / 10f;
                    if (value <= 0) {
                        return leftTextView.getText().toString().toUpperCase(Locale.US);
                    }
                    if (value >= MAX_PREDICTIVE_BACK_INTENSITY) {
                        return rightTextView.getText().toString().toUpperCase(Locale.US);
                    }
                    int intValue = (int) value;
                    if (value == intValue) {
                        return String.valueOf(intValue);
                    }
                    return String.format(Locale.US, "%.1f", value);
                }
            };
            float intensity = Math.min(ExteraConfig.getPredictiveBackIntensity(), MAX_PREDICTIVE_BACK_INTENSITY);
            if (intensity != ExteraConfig.getPredictiveBackIntensity()) {
                ExteraConfig.setPredictiveBackIntensity(intensity);
            }
            predictiveBackSeekbar.setProgress(intensity);
        }

        resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset);
        resetItem.setContentDescription(LocaleController.getString(R.string.Reset));
        resetItem.setOnClickListener(v -> resetToDefault());
        updateResetButtonVisibility(false);

        if (listView != null) {
            listView.allowReorder(true);
            listView.listenReorder(this::updateConfigFromReorder);
        }
        return view;
    }

    private void onPredictiveBackIntensityChanged(float value) {
        boolean wasOff = isPredictiveBackOff(ExteraConfig.getPredictiveBackIntensity());
        boolean isOff = isPredictiveBackOff(value);
        ExteraConfig.setPredictiveBackIntensity(value);
        if (predictiveBackSeekbar != null) {
            predictiveBackSeekbar.updateHeader(value);
        }
        if (wasOff != isOff) {
            showRestartBulletin();
        }
    }

    private boolean isPredictiveBackOff(float value) {
        return Math.round(value * 10f) / 10f <= 0;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (reorderIcon == null) {
            reorderIcon = ContextCompat.getDrawable(getContext(), R.drawable.list_reorder);
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        items.add(UItem.asButton(AppNavigationItem.TABLET_MODE.getId(), LocaleController.getString(R.string.TabletMode), tabletMode[ExteraConfig.getTabletMode()]).setSearchable(this).setLinkAlias("tabletMode", this));
        items.add(UItem.asButton(AppNavigationItem.BOTTOM_NAVIGATION_BAR_MODE.getId(), LocaleController.getString(R.string.BottomNavigationBarMode), bottomNavigationModes[BottomNavigationBar.getMode()]).setSearchable(this).setLinkAlias("bottomNavigationBarMode", this));
        items.add(UItem.asButton(AppNavigationItem.TRANSITION_ANIMATION.getId(), LocaleController.getString(R.string.TransitionAnimation), transitionAnimations[ExteraConfig.getTransitionAnimation().ordinal()]).setSearchable(this).setLinkAlias("transitionAnimation", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.TransitionAnimationInfo)));
        if (Build.VERSION.SDK_INT >= 34 && predictiveBackSeekbar != null) {
            items.add(UItem.asCustom(AppNavigationItem.PREDICTIVE_BACK_ANIMATION.getId(), predictiveBackSeekbar).setLinkAlias("predictiveBackAnimation", this));
            items.add(UItem.asShadow(LocaleController.getString(R.string.PredictiveBackInfo)));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.AppNavigation)));
        items.add(UItem.asCheck(AppNavigationItem.DRAWER.getId(), LocaleController.getString(R.string.NavigationDrawer)).setChecked(ExteraConfig.getNavigationDrawer()).setSearchable(this).setLinkAlias("navigationDrawer", this));
        if (ExteraConfig.getNavigationDrawer()) {
            items.add(UItem.asCheck(AppNavigationItem.IMMERSIVE_ANIMATION.getId(), LocaleController.getString(R.string.NavigationDrawerImmersiveAnimation)).setChecked(ExteraConfig.getImmersiveDrawerAnimation()).setSearchable(this).setLinkAlias("immersiveDrawerAnimation", this));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.NavigationDrawerInfo)));

        addMenuSection(items, adapter, LocaleController.getString(R.string.MainMenuItems), ExteraConfig.getMainMenuLayout(), true);
        items.add(UItem.asShadow(LocaleController.getString(R.string.MainMenuItemsInfo)));
        if (!ExteraConfig.getMainMenuHiddenItems().isEmpty()) {
            addMenuSection(items, adapter, LocaleController.getString(R.string.MainMenuHiddenItems), ExteraConfig.getMainMenuHiddenItems(), false);
            items.add(UItem.asShadow(null));
        }
    }

    private void addMenuSection(ArrayList<UItem> items, UniversalAdapter adapter, String title, ArrayList<Integer> ids, boolean visibleSection) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(title));
        adapter.reorderSectionStart();
        int dividerIndex = 0;
        for (Integer id : ids) {
            if (id == MainMenuItem.PLUGINS.getId() && !PluginsController.isPluginEngineSupported()) {
                continue;
            }
            if (id == MainMenuItem.DIVIDER.getId()) {
                if (visibleSection && dividerIndex < stableDividerIds.size()) {
                    items.add(createMenuItem(stableDividerIds.get(dividerIndex), null));
                } else if (!visibleSection) {
                    items.add(createMenuItem(MainMenuItem.DIVIDER.getId(), null));
                }
                dividerIndex++;
            } else {
                ItemInfo info = itemDetails.get(id);
                if (info != null) {
                    items.add(createMenuItem(id, info));
                }
            }
        }
        adapter.reorderSectionEnd();
        if (visibleSection) {
            items.add(UItem.asButton(ADD_DIVIDER_ID, R.drawable.msg_add, LocaleController.getString(R.string.MainMenuAddDivider)).accent());
        }
        adapter.whiteSectionEnd();
    }

    private UItem createMenuItem(int id, ItemInfo info) {
        UItem item;
        if (id <= FIRST_DIVIDER_ID || id == MainMenuItem.DIVIDER.getId()) {
            item = UItem.asButton(id, R.drawable.msg_block, LocaleController.getString(R.string.MainMenuDivider));
        } else {
            if (info == null) {
                return null;
            }
            item = UItem.asButton(id, info.iconRes, info.name);
        }
        item.object2 = reorderIcon;
        return item;
    }

    private void updateConfigFromReorder(int sectionId, ArrayList<UItem> sectionItems) {
        ArrayList<Integer> ids = new ArrayList<>();
        ArrayList<Integer> dividerIds = new ArrayList<>();
        for (UItem item : sectionItems) {
            if (item.id > FIRST_DIVIDER_ID && item.id != MainMenuItem.DIVIDER.getId()) {
                ids.add(item.id);
            } else if (sectionId == 0) {
                ids.add(MainMenuItem.DIVIDER.getId());
                dividerIds.add(item.id > FIRST_DIVIDER_ID ? nextDividerId-- : item.id);
            }
        }
        if (sectionId == 0) {
            stableDividerIds.clear();
            stableDividerIds.addAll(dividerIds);
            if (BottomNavigationBar.hidden() && !ids.contains(MainMenuItem.SETTINGS.getId())) {
                ids.add(MainMenuItem.SETTINGS.getId());
            }
            ExteraConfig.getMainMenuLayout().clear();
            ExteraConfig.getMainMenuLayout().addAll(ids);
        } else if (sectionId == 1) {
            if (BottomNavigationBar.hidden()) {
                ids.remove(Integer.valueOf(MainMenuItem.SETTINGS.getId()));
            }
            ExteraConfig.getMainMenuHiddenItems().clear();
            ExteraConfig.getMainMenuHiddenItems().addAll(ids);
        }
        saveAndNotify();
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        AppNavigationItem navigationItem = AppNavigationItem.fromId(id);
        if (navigationItem != null) {
            switch (navigationItem) {
                case DRAWER:
                    toggleBooleanSettingAndRefresh(item, ExteraConfig::setNavigationDrawer);
                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                    parentLayout.rebuildFragments(0);
                    break;
                case IMMERSIVE_ANIMATION:
                    toggleBooleanSettingAndRefresh(item, ExteraConfig::setImmersiveDrawerAnimation);
                    break;
                case BOTTOM_NAVIGATION_BAR_MODE:
                    showListDialog(item, bottomNavigationModes, LocaleController.getString(R.string.BottomNavigationBarMode), BottomNavigationBar.getMode(), which -> {
                        ExteraConfig.getPreferences().edit().putInt("bottomNavigationBarMode", which).apply();
                        BottomNavigationBar.setMode(which);
                        ExteraConfig.ensureSettingsVisibility();
                        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                        refreshEditorList();
                        updateResetButtonVisibility();
                        parentLayout.rebuildFragments(0);
                    });
                    break;
                case TRANSITION_ANIMATION:
                    showListDialog(item, transitionAnimations, LocaleController.getString(R.string.TransitionAnimation), ExteraConfig.getTransitionAnimation().ordinal(), which -> {
                        ExteraConfig.setTransitionAnimation(TransitionAnimation.values()[which]);
                        if (ExteraConfig.getTransitionAnimation() != TransitionAnimation.DEFAULT) {
                            MessagesController.getGlobalMainSettings().edit().putBoolean("view_animations", true).apply();
                            SharedConfig.setAnimationsEnabled(true);
                        }
                    });
                    break;
                case TABLET_MODE:
                    showListDialog(item, tabletMode, LocaleController.getString(R.string.TabletMode), ExteraConfig.getTabletMode(), which -> {
                        ExteraConfig.setTabletMode(which);
                        showRestartBulletin();
                    });
                    break;
                default:
                    break;
            }
            return;
        }

        if (id == ADD_DIVIDER_ID) {
            stableDividerIds.add(nextDividerId--);
            ExteraConfig.getMainMenuLayout().add(MainMenuItem.DIVIDER.getId());
            saveAndNotify();
            return;
        }

        if (id <= FIRST_DIVIDER_ID) {
            int dividerIndex = stableDividerIds.indexOf(id);
            if (dividerIndex == -1) {
                return;
            }
            ArrayList<Integer> layout = ExteraConfig.getMainMenuLayout();
            int layoutIndex = -1;
            for (int i = 0, seen = 0; i < layout.size(); i++) {
                if (layout.get(i) == MainMenuItem.DIVIDER.getId()) {
                    if (seen == dividerIndex) {
                        layoutIndex = i;
                        break;
                    }
                    seen++;
                }
            }
            if (layoutIndex != -1) {
                stableDividerIds.remove(dividerIndex);
                ExteraConfig.getMainMenuLayout().remove(layoutIndex);
                saveAndNotify();
            }
            return;
        }

        if (id == MainMenuItem.DIVIDER.getId()) {
            ExteraConfig.getMainMenuHiddenItems().remove(Integer.valueOf(id));
            saveAndNotify();
            return;
        }

        if (BottomNavigationBar.hidden() && id == MainMenuItem.SETTINGS.getId() && ExteraConfig.getMainMenuLayout().contains(id)) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.MainMenuRemoveSettingsInfo)).show();
            return;
        }

        if (ExteraConfig.getMainMenuLayout().contains(id)) {
            ExteraConfig.getMainMenuLayout().remove(Integer.valueOf(id));
            if (!ExteraConfig.getMainMenuHiddenItems().contains(id)) {
                ExteraConfig.getMainMenuHiddenItems().add(0, id);
            }
        } else if (ExteraConfig.getMainMenuHiddenItems().contains(id)) {
            ExteraConfig.getMainMenuHiddenItems().remove(Integer.valueOf(id));
            ExteraConfig.getMainMenuLayout().add(id);
        }
        saveAndNotify();
    }

    private void saveAndNotify() {
        ExteraConfig.saveMainMenuLayout();
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        refreshEditorList();
        updateResetButtonVisibility();
    }

    private void refreshEditorList() {
        if (listView == null || listView.adapter == null) {
            return;
        }
        listView.hideSelector(false);
        listView.cancelClickRunnables(false);
        listView.adapter.update(true);
    }

    private void updateResetButtonVisibility() {
        updateResetButtonVisibility(true);
    }

    private void updateResetButtonVisibility(boolean animated) {
        if (resetItem == null) {
            return;
        }
        boolean changed = !ExteraConfig.getMainMenuLayout().equals(ExteraConfig.getDefaultMainMenuLayout());
        AndroidUtilities.updateViewVisibilityAnimated(resetItem, changed, 0.5f, animated);
    }

    private void resetToDefault() {
        ExteraConfig.getMainMenuLayout().clear();
        ExteraConfig.getMainMenuLayout().addAll(ExteraConfig.getDefaultMainMenuLayout());
        ExteraConfig.getMainMenuHiddenItems().clear();
        for (MainMenuItem menuItem : MainMenuItem.getEntries()) {
            if (menuItem == MainMenuItem.DIVIDER || ExteraConfig.getMainMenuLayout().contains(menuItem.getId())) {
                continue;
            }
            if (menuItem != MainMenuItem.PLUGINS || PluginsController.isPluginEngineSupported()) {
                ExteraConfig.getMainMenuHiddenItems().add(menuItem.getId());
            }
        }
        rebuildStableDividerIds();
        ExteraConfig.saveMainMenuLayout();
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        refreshEditorList();
        updateResetButtonVisibility();
    }
}
