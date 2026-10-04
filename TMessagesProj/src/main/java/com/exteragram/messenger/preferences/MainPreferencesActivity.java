package com.exteragram.messenger.preferences;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.ui.PluginsActivity;
import com.exteragram.messenger.preferences.appearance.AppearancePreferencesActivity;
import com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity;
import com.exteragram.messenger.preferences.components.HeaderSettingsCell;
import com.exteragram.messenger.utils.system.VibratorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class MainPreferencesActivity extends BasePreferencesActivity {

    private HeaderSettingsCell headerSettingsCell;

    public enum PreferenceItem {
        HEADER_CELL,
        GENERAL_CATEGORY,
        APPEARANCE_CATEGORY,
        CHATS_CATEGORY,
        PLUGINS_CATEGORY,
        OTHER_CATEGORY,
        CHANNEL,
        FORUM,
        CROWDIN,
        WEBSITE;

        public int getId() {
            return ordinal() + 1;
        }
    }

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Preferences);
    }

    @Override
    public View createView(Context context) {
        headerSettingsCell = new HeaderSettingsCell(context);
        View view = super.createView(context);
        return fragmentView = view;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustomShadow(headerSettingsCell, 198).setId(PreferenceItem.HEADER_CELL.getId()));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Categories)));
        items.add(UItem.asButton(PreferenceItem.GENERAL_CATEGORY.getId(), R.drawable.msg_media, LocaleController.getString(R.string.General)).setSearchable(this).setLinkAlias("general", this));
        items.add(UItem.asButton(PreferenceItem.APPEARANCE_CATEGORY.getId(), R.drawable.msg_theme, LocaleController.getString(R.string.Appearance)).setSearchable(this).setLinkAlias("appearance", this));
        items.add(UItem.asButton(PreferenceItem.CHATS_CATEGORY.getId(), R.drawable.msg_discussion, LocaleController.getString(R.string.SearchAllChatsShort)).setSearchable(this).setLinkAlias("chats", this));
        if (PluginsController.isPluginEngineSupported()) {
            items.add(UItem.asButton(PreferenceItem.PLUGINS_CATEGORY.getId(), R.drawable.msg_plugins, LocaleController.getString(R.string.Plugins)).setSearchable(this).setLinkAlias("plugins", this));
        }
        items.add(UItem.asButton(PreferenceItem.OTHER_CATEGORY.getId(), R.drawable.msg_fave, LocaleController.getString(R.string.LocalOther)).setSearchable(this).setLinkAlias("other", this));
        items.add(UItem.asShadow());
        items.add(UItem.asHeader(LocaleController.getString(R.string.Links)));
        items.add(UItem.asButton(PreferenceItem.CHANNEL.getId(), R.drawable.msg_channel, LocaleController.getString(R.string.ProfileChannel), "@exteraGram").setSearchable(this).setLinkAlias("channel", this));
        items.add(UItem.asButton(PreferenceItem.FORUM.getId(), R.drawable.msg_groups, LocaleController.getString(R.string.SearchAllChatsShort), "@exteraForum").setSearchable(this).setLinkAlias("chat", this));
        items.add(UItem.asButton(PreferenceItem.CROWDIN.getId(), R.drawable.msg_translate, LocaleController.getString(R.string.Crowdin), "Crowdin").setSearchable(this).setLinkAlias("crowdin", this));
        items.add(UItem.asButton(PreferenceItem.WEBSITE.getId(), R.drawable.msg_language, LocaleController.getString(R.string.Website), "exteraGram.app").showDivider(false).setSearchable(this).setLinkAlias("website", this));
        items.add(UItem.asShadow());
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id <= 0 || item.id > PreferenceItem.values().length) {
            return;
        }
        switch (PreferenceItem.values()[item.id - 1]) {
            case HEADER_CELL:
                // TODO(openextera): disabled, exteraSquad infrastructure (was: if (!BuildVars.PM_BUILD) ((LaunchActivity) getParentActivity()).checkAppUpdate(true))
                break;
            case GENERAL_CATEGORY:
                presentFragment(new GeneralPreferencesActivity());
                break;
            case APPEARANCE_CATEGORY:
                presentFragment(new AppearancePreferencesActivity());
                break;
            case CHATS_CATEGORY:
                presentFragment(new ChatsPreferencesActivity());
                break;
            case PLUGINS_CATEGORY:
                presentFragment(new PluginsActivity());
                break;
            case OTHER_CATEGORY:
                presentFragment(new OtherPreferencesActivity());
                break;
            case CHANNEL:
                getMessagesController().openByUserName("exteraGram", this, 1);
                break;
            case FORUM:
                getMessagesController().openByUserName("exteraForum", this, 1);
                break;
            case CROWDIN:
                Browser.openUrl(getParentActivity(), "https://crowdin.com/project/exteralocales");
                break;
            case WEBSITE:
                Browser.openUrl(getParentActivity(), "https://exteraGram.app");
                break;
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id <= 0 || item.id > PreferenceItem.values().length) {
            return false;
        }
        if (PreferenceItem.values()[item.id - 1] == PreferenceItem.HEADER_CELL) {
            ExteraConfig.setUseSystemIconShape(!ExteraConfig.getUseSystemIconShape());
            view.performHapticFeedback(VibratorUtils.getType(HapticFeedbackConstants.KEYBOARD_TAP), HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            view.invalidate();
            return true;
        }
        return super.onLongClick(item, view, position, x, y);
    }

    @Override
    public int getListTopPadding(int statusBarHeight) {
        return statusBarHeight + AndroidUtilities.dp(12);
    }
}
