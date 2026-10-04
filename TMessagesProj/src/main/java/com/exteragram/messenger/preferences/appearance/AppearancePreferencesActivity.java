package com.exteragram.messenger.preferences.appearance;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

import com.exteragram.messenger.DividerStyle;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.GlassOutlineStyle;
import com.exteragram.messenger.TabCounterMode;
import com.exteragram.messenger.TabIconsMode;
import com.exteragram.messenger.appicons.AppIcon;
import com.exteragram.messenger.appicons.AppIconController;
import com.exteragram.messenger.appicons.AppIconPreviewDrawable;
import com.exteragram.messenger.appicons.ui.AppIconsActivity;
import com.exteragram.messenger.icons.ui.IconPacksActivity;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.SwitchGroup;
import com.exteragram.messenger.preferences.appearance.components.AvatarCornersPreviewCell;
import com.exteragram.messenger.preferences.appearance.components.ChatListPreviewCell;
import com.exteragram.messenger.preferences.appearance.components.FabShapeCell;
import com.exteragram.messenger.preferences.appearance.components.FilterTabsPreviewCell;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.chats.FolderCounters;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class AppearancePreferencesActivity extends BasePreferencesActivity {

    private static final int MAX_SECTION_RADIUS = 28;

    private AppIconPreviewDrawable appIconDrawable;
    private AppIcon appIconDrawableIcon;
    private boolean appIconDrawableShape;

    private AvatarCornersPreviewCell avatarCornersPreviewCell;
    private ChatListPreviewCell chatListPreviewCell;
    private FabShapeCell fabShapeCell;
    private FilterTabsPreviewCell filterTabsPreviewCell;

    private CharSequence[] titles;
    private CharSequence[] tabIcons;
    private CharSequence[] tabCounterModes;
    private CharSequence[] dividerStyles;
    private CharSequence[] glassOutlineStyles;

    private final SwitchGroup md3Styles = SwitchGroup.of(this, AppearanceItem.MD3_STYLES.getId(), R.string.MaterialDesign3)
            .searchable()
            .linkAlias("md3Styles")
            .onChanged(this::updateMD3Styles)
            .add(AppearanceItem.NEW_LOADING_STYLE.getId(), R.string.NewLoadingStyle, ExteraConfig::getNewLoadingStyle, ExteraConfig::setNewLoadingStyle)
            .add(AppearanceItem.NEW_SLIDER_STYLE.getId(), R.string.NewSliderStyle, ExteraConfig::getNewSliderStyle, ExteraConfig::setNewSliderStyle)
            .add(AppearanceItem.NEW_SWITCH_STYLE.getId(), R.string.NewSwitchStyle, ExteraConfig::getNewSwitchStyle, ExteraConfig::setNewSwitchStyle)
            .add(AppearanceItem.NEW_CHAT_HEADER_STYLE.getId(), R.string.ChatHeader, ExteraConfig::getNewChatHeaderStyle, ExteraConfig::setNewChatHeaderStyle)
            .markNew("Appearance-M3Styles-ChatHeader")
            .add(AppearanceItem.NEW_NAVIGATION_BAR_STYLE.getId(), R.string.BottomNavigationBarMode, ExteraConfig::getNewNavigationBarStyle, ExteraConfig::setNewNavigationBarStyle)
            .markNew("Appearance-M3Styles-NavigationBar")
            .add(AppearanceItem.NEW_FAB_STYLE.getId(), R.string.NewFabStyle, ExteraConfig::getNewFabStyle, ExteraConfig::setNewFabStyle)
            .markNew("Appearance-M3Styles-FloatingButton");

    public enum AppearanceItem {
        AVATAR_CORNERS_PREVIEW,
        SINGLE_CORNER_RADIUS,
        CHAT_LIST_PREVIEW,
        FORCE_SNOW,
        HIDE_ACTION_BAR_STATUS,
        CENTER_TITLE,
        HIDE_STORIES,
        HIDE_FLOATING_BUTTON,
        HIDE_DIALOGS_SEARCH_BAR,
        SENDER_MINI_AVATARS,
        ACTION_BAR_TITLE,
        PILL_STACK,
        FOLDERS_PREVIEW,
        TAB_TITLE,
        TAB_COUNTER,
        HIDE_ALL_CHATS,
        APP_NAVIGATION_SETTINGS,
        APP_ICON,
        ICON_PACKS,
        FAB_SHAPE,
        SECTION_RADIUS,
        SEPARATED_HEADERS,
        DIVIDER_STYLE,
        MD3_STYLES,
        NEW_LOADING_STYLE,
        NEW_SLIDER_STYLE,
        NEW_SWITCH_STYLE,
        USE_SYSTEM_FONTS,
        USE_SYSTEM_EMOJI,
        GOOEY_AVATAR_ANIMATION,
        CUSTOM_THEMES,
        GLASS_OUTLINE_STYLE,
        FORCE_BLUR,
        GLASS_MESSAGE_MENU,
        NEW_CHAT_HEADER_STYLE,
        NEW_NAVIGATION_BAR_STYLE,
        NEW_FAB_STYLE;

        public int getId() {
            return ordinal() + 1;
        }
    }

    @Override
    public void initializeOptionStrings() {
        titles = new CharSequence[]{
                LocaleController.getString(R.string.exteraAppName),
                LocaleController.getString(R.string.ActionBarTitleUsername),
                LocaleController.getString(R.string.ActionBarTitleName),
                LocaleController.getString(R.string.FilterChats)
        };
        tabIcons = new CharSequence[]{
                LocaleController.getString(R.string.TabTitleStyleTextWithIcons),
                LocaleController.getString(R.string.TabTitleStyleTextOnly),
                LocaleController.getString(R.string.TabTitleStyleIconsOnly)
        };
        tabCounterModes = new CharSequence[]{
                LocaleController.getString(R.string.FilterAllChats),
                LocaleController.getString(R.string.TabCounterUnmuted),
                LocaleController.getString(R.string.BlurOff)
        };
        dividerStyles = new CharSequence[]{
                LocaleController.getString(R.string.DividerStyleHidden),
                LocaleController.getString(R.string.DividerStyleLine),
                LocaleController.getString(R.string.DividerStyleSegments)
        };
        glassOutlineStyles = new CharSequence[]{
                LocaleController.getString(R.string.GlassOutlineGlare),
                LocaleController.getString(R.string.GlassOutlineSolid),
                LocaleController.getString(R.string.GlassOutlineHidden)
        };
    }

    @Override
    public View createView(Context context) {
        avatarCornersPreviewCell = new AvatarCornersPreviewCell(context, this, resourceProvider, RemoteUtils.getIntConfigValue("preferences_preview_style", 0));
        chatListPreviewCell = new ChatListPreviewCell(context);
        filterTabsPreviewCell = new FilterTabsPreviewCell(context);
        fabShapeCell = new FabShapeCell(context) {
            @Override
            public void rebuildFragments() {
                parentLayout.rebuildFragments(0);
            }
        };
        return super.createView(context);
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Appearance);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(AppearanceItem.AVATAR_CORNERS_PREVIEW.getId(), avatarCornersPreviewCell).setLinkAlias("avatarCorners", this));
        items.add(UItem.asCheck(AppearanceItem.SINGLE_CORNER_RADIUS.getId(), LocaleController.getString(R.string.SingleCornerRadius)).setChecked(ExteraConfig.getSingleCornerRadius()).setSearchable(this).setLinkAlias("singleCornerRadius", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.SingleCornerRadiusInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ListOfChats)));
        items.add(UItem.asCustom(AppearanceItem.CHAT_LIST_PREVIEW.getId(), chatListPreviewCell));
        items.add(UItem.asCheck(AppearanceItem.FORCE_SNOW.getId(), LocaleController.getString(R.string.ForceSnow), LocaleController.getString(R.string.ForceSnowInfo), true).setChecked(ExteraConfig.getForceSnow()).setSearchable(this).setLinkAlias("forceSnow", this));
        if (getUserConfig().isPremium()) {
            items.add(UItem.asCheck(AppearanceItem.HIDE_ACTION_BAR_STATUS.getId(), LocaleController.getString(R.string.HideActionBarStatus)).setChecked(ExteraConfig.getHideActionBarStatus()).setSearchable(this).setLinkAlias("hideActionBarStatus", this));
        }
        items.add(UItem.asCheck(AppearanceItem.CENTER_TITLE.getId(), LocaleController.getString(R.string.CenterTitle)).setChecked(ExteraConfig.getCenterTitle()).setSearchable(this).setLinkAlias("centerTitle", this));
        items.add(UItem.asCheck(AppearanceItem.HIDE_STORIES.getId(), LocaleController.getString(R.string.HideStories)).setChecked(ExteraConfig.getHideStories()).setSearchable(this).setLinkAlias("hideStories", this));
        items.add(UItem.asCheck(AppearanceItem.HIDE_FLOATING_BUTTON.getId(), LocaleController.getString(R.string.HideFloatingButton)).setChecked(ExteraConfig.getHideFloatingButton()).setSearchable(this).setLinkAlias("hideFloatingButton", this));
        items.add(UItem.asCheck(AppearanceItem.HIDE_DIALOGS_SEARCH_BAR.getId(), LocaleController.getString(R.string.HideDialogsSearchBar)).setChecked(ExteraConfig.getHideDialogsSearchBar()).setSearchable(this).setLinkAlias("hideDialogsSearchBar", this));
        items.add(UItem.asCheck(AppearanceItem.SENDER_MINI_AVATARS.getId(), LocaleController.getString(R.string.SenderMiniAvatars)).setChecked(ExteraConfig.getSenderMiniAvatars()).setSearchable(this).setLinkAlias("senderMiniAvatars", this));
        items.add(UItem.asButton(AppearanceItem.ACTION_BAR_TITLE.getId(), LocaleController.getString(R.string.ActionBarTitle), titles[ExteraConfig.getTitleText()]).setSearchable(this).setLinkAlias("actionBarTitle", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ListOfChatsInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Filters)));
        items.add(UItem.asCustom(AppearanceItem.FOLDERS_PREVIEW.getId(), filterTabsPreviewCell));
        items.add(UItem.asButton(AppearanceItem.TAB_TITLE.getId(), LocaleController.getString(R.string.TabTitleStyle), tabIcons[ExteraConfig.getTabIcons().ordinal()]).setSearchable(this).setLinkAlias("tabTitleStyle", this));
        items.add(UItem.asButton(AppearanceItem.TAB_COUNTER.getId(), LocaleController.getString(R.string.TabCounter), tabCounterModes[ExteraConfig.getTabCounterMode().ordinal()]).setSearchable(this).setLinkAlias("tabCounter", this));
        items.add(UItem.asCheck(AppearanceItem.HIDE_ALL_CHATS.getId(), LocaleController.formatString(R.string.HideAllChats, LocaleController.getString(R.string.FilterAllChats))).setChecked(ExteraConfig.getHideAllChats()).setSearchable(this).setLinkAlias("hideAllChats", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.FoldersInfo)));

        items.add(UItem.asButtonWithSubtext(AppearanceItem.APP_NAVIGATION_SETTINGS.getId(), R.drawable.msg_newphone, LocaleController.getString(R.string.AppNavigation), LocaleController.getString(R.string.AppNavigationInfo), 64, 60).setSearchable(this).setLinkAlias("appNavigationSettings", this));
        items.add(UItem.asButtonWithSubtext(AppearanceItem.APP_ICON.getId(), R.drawable.menu_edit_appearance, LocaleController.getString(R.string.AppIcon), AppIconController.getSelectedIcon().getTitle(), 64, 60).setSearchable(this).setLinkAlias("appIcon", this).onBind(view -> {
            if (view instanceof TextCell) {
                TextCell textCell = (TextCell) view;
                ImageView valueImageView = textCell.getValueImageView();
                valueImageView.setVisibility(View.VISIBLE);
                valueImageView.setImageDrawable(getAppIconDrawable());
                textCell.valueImageRight = (textCell.heightDp - 34) / 2;
            }
        }));
        items.add(UItem.asButtonWithSubtext(AppearanceItem.ICON_PACKS.getId(), R.drawable.msg_sticker, LocaleController.getString(R.string.IconPacks), LocaleController.getString(R.string.IconPacksInfo), 64, 60).setSearchable(this).setLinkAlias("iconPacks", this));
        items.add(UItem.asButtonWithSubtext(AppearanceItem.PILL_STACK.getId(), R.drawable.outline_header_search, LocaleController.getString(R.string.PillStackPills), LocaleController.getString(R.string.PillStackPillsInfo), 64, 60).setSearchable(this).setLinkAlias("pillStack", this));
        items.add(UItem.asShadow());

        items.add(UItem.asHeader(LocaleController.getString(R.string.Appearance)));
        items.add(UItem.asCustom(AppearanceItem.FAB_SHAPE.getId(), fabShapeCell).setLinkAlias("fabShape", this));
        items.add(UItem.asCheck(AppearanceItem.USE_SYSTEM_FONTS.getId(), LocaleController.getString(R.string.UseSystemFonts)).setChecked(ExteraConfig.getUseSystemFonts()).setSearchable(this).setLinkAlias("useSystemFonts", this));
        items.add(UItem.asCheck(AppearanceItem.USE_SYSTEM_EMOJI.getId(), LocaleController.getString(R.string.UseSystemEmoji)).setChecked(SharedConfig.useSystemEmoji).setSearchable(this).setLinkAlias("useSystemEmoji", this));
        md3Styles.fill(items);
        items.add(UItem.asCheck(AppearanceItem.GOOEY_AVATAR_ANIMATION.getId(), LocaleController.getString(R.string.GooeyAvatarAnimation)).setChecked(ExteraConfig.getGooeyAvatarAnimation()).setSearchable(this).setLinkAlias("gooeyAvatarAnimation", this));
        items.add(UItem.asCheck(AppearanceItem.CUSTOM_THEMES.getId(), LocaleController.getString(R.string.CustomChatThemes)).setChecked(ExteraConfig.getCustomThemes()).setSearchable(this).setLinkAlias("customThemes", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.CustomChatThemesInfo)));

        CharSequence sections = LocaleController.getString(R.string.Sections);
        items.add(UItem.asHeader(SettingsRegistry.markAsNewFeature("Appearance-Sections") ? LocaleUtils.applyNewSpan(sections) : sections));
        items.add(createSectionRadiusSliderItem().setSearchable(this).setLinkAlias("sectionRadius", this));
        items.add(UItem.asCheck(AppearanceItem.SEPARATED_HEADERS.getId(), LocaleController.getString(R.string.SeparateHeaders)).setChecked(ExteraConfig.getSectionsSeparatedHeaders()).setEnabled(ExteraConfig.getDividerStyle() != DividerStyle.SEGMENTS).setSearchable(this).setLinkAlias("sectionsSeparatedHeaders", this));
        items.add(UItem.asButton(AppearanceItem.DIVIDER_STYLE.getId(), LocaleController.getString(R.string.DividerStyle), dividerStyles[ExteraConfig.getDividerStyle().ordinal()]).setSearchable(this).setLinkAlias("dividerStyle", this));
        items.add(UItem.asShadow());

        items.add(UItem.asHeader(LocaleController.getString(R.string.BlurOptions)));
        items.add(UItem.asButton(AppearanceItem.GLASS_OUTLINE_STYLE.getId(), LocaleController.getString(R.string.GlassOutlineStyle), glassOutlineStyles[ExteraConfig.getGlassOutlineStyle().ordinal()]).setSearchable(this).setLinkAlias("glassOutlineStyle", this));
        items.add(UItem.asCheck(AppearanceItem.GLASS_MESSAGE_MENU.getId(), LocaleController.getString(R.string.GlassMessageMenu), LocaleController.getString(R.string.GlassMessageMenuInfo), true).setChecked(ExteraConfig.getGlassMessageMenu()).setSearchable(this).setLinkAlias("glassMessageMenu", this));
        items.add(UItem.asCheck(AppearanceItem.FORCE_BLUR.getId(), LocaleController.getString(R.string.ForceBlur)).setChecked(ExteraConfig.getForceBlur()).setSearchable(this).setLinkAlias("forceBlur", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ForceBlurInfo)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id <= 0 || item.id > AppearanceItem.values().length) {
            return;
        }
        switch (AppearanceItem.values()[item.id - 1]) {
            case SINGLE_CORNER_RADIUS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setSingleCornerRadius);
                parentLayout.rebuildFragments(0);
                break;
            case ACTION_BAR_TITLE:
                showListDialog(item, titles, LocaleController.getString(R.string.ActionBarTitle), ExteraConfig.getTitleText(), which -> {
                    ExteraConfig.setTitleText(which);
                    handleActionBarTitleClick();
                });
                break;
            case PILL_STACK:
                presentFragment(new PillStackPreferencesActivity());
                break;
            case HIDE_STORIES:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideStories);
                getNotificationCenter().postNotificationName(NotificationCenter.storiesEnabledUpdate);
                break;
            case HIDE_ACTION_BAR_STATUS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideActionBarStatus);
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.updateStatus(true);
                }
                parentLayout.rebuildFragments(0);
                break;
            case CENTER_TITLE:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setCenterTitle);
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.updateCentered(true);
                }
                if (actionBar != null) {
                    actionBar.refreshTitlePosition(true);
                }
                parentLayout.rebuildFragments(0);
                break;
            case HIDE_FLOATING_BUTTON:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideFloatingButton);
                parentLayout.rebuildFragments(0);
                break;
            case HIDE_DIALOGS_SEARCH_BAR:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideDialogsSearchBar);
                parentLayout.rebuildFragments(0);
                break;
            case SENDER_MINI_AVATARS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setSenderMiniAvatars);
                break;
            case TAB_TITLE:
                showListDialog(item, tabIcons, LocaleController.getString(R.string.TabTitleStyle), ExteraConfig.getTabIcons().ordinal(), which -> {
                    ExteraConfig.setTabIcons(TabIconsMode.getEntries().get(which));
                    handleTabTitleClick();
                });
                break;
            case TAB_COUNTER:
                showListDialog(item, tabCounterModes, LocaleController.getString(R.string.TabCounter), ExteraConfig.getTabCounterMode().ordinal(), which -> {
                    ExteraConfig.setTabCounterMode(TabCounterMode.getEntries().get(which));
                    handleTabCounterClick();
                });
                break;
            case HIDE_ALL_CHATS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideAllChats);
                handleHideAllChatsClick();
                break;
            case APP_NAVIGATION_SETTINGS:
                presentFragment(new AppNavigationPreferencesActivity());
                break;
            case APP_ICON:
                presentFragment(new AppIconsActivity());
                break;
            case ICON_PACKS:
                presentFragment(new IconPacksActivity());
                break;
            case SEPARATED_HEADERS:
                if (ExteraConfig.getDividerStyle() != DividerStyle.SEGMENTS) {
                    toggleBooleanSettingAndRefresh(item, ExteraConfig::setSectionsSeparatedHeaders);
                    listView.invalidateItemDecorations();
                }
                break;
            case DIVIDER_STYLE:
                showListDialog(item, dividerStyles, LocaleController.getString(R.string.DividerStyle), ExteraConfig.getDividerStyle().ordinal(), which -> {
                    DividerStyle style = DividerStyle.getEntries().get(which);
                    ExteraConfig.setDividerStyle(style);
                    if (style == DividerStyle.SEGMENTS) {
                        ExteraConfig.setSectionsSeparatedHeaders(true);
                    }
                    handleDividerStyleChange();
                });
                break;
            case USE_SYSTEM_FONTS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setUseSystemFonts);
                handleUseSystemFontsClick();
                break;
            case USE_SYSTEM_EMOJI:
                handleUseSystemEmojiClick(item);
                break;
            case MD3_STYLES:
            case NEW_LOADING_STYLE:
            case NEW_SLIDER_STYLE:
            case NEW_SWITCH_STYLE:
            case NEW_CHAT_HEADER_STYLE:
            case NEW_NAVIGATION_BAR_STYLE:
            case NEW_FAB_STYLE:
                md3Styles.onClick(item);
                break;
            case GOOEY_AVATAR_ANIMATION:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setGooeyAvatarAnimation);
                break;
            case CUSTOM_THEMES:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setCustomThemes);
                break;
            case FORCE_SNOW:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setForceSnow);
                if (chatListPreviewCell != null) {
                    chatListPreviewCell.invalidate();
                }
                break;
            case GLASS_OUTLINE_STYLE:
                showListDialog(item, glassOutlineStyles, LocaleController.getString(R.string.GlassOutlineStyle), ExteraConfig.getGlassOutlineStyle().ordinal(), which -> {
                    ExteraConfig.setGlassOutlineStyle(GlassOutlineStyle.getEntries().get(which));
                    parentLayout.rebuildFragments(0);
                });
                break;
            case FORCE_BLUR:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setForceBlur);
                handleForceBlurChange();
                break;
            case GLASS_MESSAGE_MENU:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setGlassMessageMenu);
                handleGlassMessageMenuChange();
                break;
        }
    }

    private UItem createSectionRadiusSliderItem() {
        UItem item = UItem.asIntSlideView(1, 0, ExteraConfig.getSectionRadiusDp(), MAX_SECTION_RADIUS, this::formatSectionRadius, value -> {
            ExteraConfig.setSectionRadius(value);
            handleSectionRadiusChange();
        });
        item.id = AppearanceItem.SECTION_RADIUS.getId();
        item.text = LocaleController.getString(R.string.Sections);
        return item;
    }

    private CharSequence formatSectionRadius(int value) {
        if (value == 0) {
            return LocaleController.getString(R.string.BlurOff);
        }
        if (value == MAX_SECTION_RADIUS) {
            return LocaleController.getString(R.string.PredictiveBackMax);
        }
        return value + " dp";
    }

    private void handleDividerStyleChange() {
        Theme.applyCommonTheme();
        listView.invalidate();
        listView.invalidateItemDecorations();
        if (avatarCornersPreviewCell != null) {
            avatarCornersPreviewCell.invalidate();
        }
        if (chatListPreviewCell != null) {
            chatListPreviewCell.invalidate();
        }
        if (fabShapeCell != null) {
            fabShapeCell.invalidate();
        }
        if (filterTabsPreviewCell != null) {
            filterTabsPreviewCell.invalidate();
        }
        parentLayout.rebuildFragments(0);
    }

    private void handleSectionRadiusChange() {
        if (listView != null) {
            listView.setSections();
            listView.invalidate();
            listView.invalidateItemDecorations();
        }
        parentLayout.rebuildFragments(0);
    }

    private void updateMD3Styles() {
        if (avatarCornersPreviewCell != null) {
            avatarCornersPreviewCell.updateSliderStyle();
        }
        parentLayout.rebuildFragments(0);
    }

    private void handleActionBarTitleClick() {
        if (chatListPreviewCell != null) {
            chatListPreviewCell.updateStatus(true);
        }
        getNotificationCenter().postNotificationName(NotificationCenter.currentUserPremiumStatusChanged);
    }

    private void handleTabTitleClick() {
        getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
    }

    private void handleTabCounterClick() {
        FolderCounters.Companion.recountAll();
        getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
    }

    private AppIconPreviewDrawable getAppIconDrawable() {
        AppIcon selectedIcon = AppIconController.getSelectedIcon();
        boolean useSystemIconShape = ExteraConfig.getUseSystemIconShape();
        if (appIconDrawable == null || appIconDrawableIcon != selectedIcon || appIconDrawableShape != useSystemIconShape) {
            appIconDrawableIcon = selectedIcon;
            appIconDrawableShape = useSystemIconShape;
            appIconDrawable = new AppIconPreviewDrawable(selectedIcon, AndroidUtilities.dp(34));
        }
        return appIconDrawable;
    }

    private void handleHideAllChatsClick() {
        getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private void handleUseSystemFontsClick() {
        AndroidUtilities.clearTypefaceCache();
        parentLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
    }

    private void handleForceBlurChange() {
        if (!SharedConfig.chatBlurEnabled() && ExteraConfig.getForceBlur()) {
            SharedConfig.toggleChatBlur();
        }
    }

    private void handleGlassMessageMenuChange() {
        if (ExteraConfig.getGlassMessageMenu() && !SharedConfig.chatBlurEnabled()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.GlassMessageMenuBlurOff), LocaleController.getString(R.string.Enable), SharedConfig::toggleChatBlur).show();
        }
    }

    private void handleUseSystemEmojiClick(UItem item) {
        SharedConfig.toggleUseSystemEmoji();
        item.setChecked(!SharedConfig.useSystemEmoji);
        parentLayout.rebuildFragments(0);
        listView.adapter.update(true);
    }
}
