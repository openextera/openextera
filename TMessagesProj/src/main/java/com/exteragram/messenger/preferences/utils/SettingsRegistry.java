package com.exteragram.messenger.preferences.utils;

import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.ai.ui.activities.AiPreferencesActivity;
import com.exteragram.messenger.feed.ui.FeedChannelsActivity;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.ui.PluginsInfoActivity;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.GeneralPreferencesActivity;
import com.exteragram.messenger.preferences.MainPreferencesActivity;
import com.exteragram.messenger.preferences.OtherPreferencesActivity;
import com.exteragram.messenger.preferences.appearance.AppNavigationPreferencesActivity;
import com.exteragram.messenger.preferences.appearance.AppearancePreferencesActivity;
import com.exteragram.messenger.preferences.chats.ChatsPreferencesActivity;
import com.exteragram.messenger.preferences.chats.SwipeActionsPreferencesActivity;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class SettingsRegistry {

    private static final Map<Class<? extends BaseFragment>, Integer> categoriesIcons;

    static {
        Map<Class<? extends BaseFragment>, Integer> icons = new HashMap<>();
        icons.put(MainPreferencesActivity.class, R.drawable.extera_outline);
        icons.put(GeneralPreferencesActivity.class, R.drawable.msg_media);
        icons.put(AppearancePreferencesActivity.class, R.drawable.msg_theme);
        icons.put(ChatsPreferencesActivity.class, R.drawable.msg_discussion);
        icons.put(PluginsInfoActivity.class, R.drawable.msg_plugins);
        icons.put(OtherPreferencesActivity.class, R.drawable.msg_fave);
        icons.put(AiPreferencesActivity.class, R.drawable.msg_bot);
        icons.put(AppNavigationPreferencesActivity.class, R.drawable.msg_list);
        icons.put(PillStackPreferencesActivity.class, R.drawable.outline_header_search);
        icons.put(FeedChannelsActivity.class, R.drawable.ic_feed);
        icons.put(SwipeActionsPreferencesActivity.class, R.drawable.menu_reply);
        categoriesIcons = Collections.unmodifiableMap(icons);
    }

    public static List<String> newFeatures = Collections.unmodifiableList(Arrays.asList(
            "customSavePath",
            "disableNotificationDelay",
            "Camera-ExtendedSettings-StartWithWideAngle",
            "zoomSlider",
            "widePosts",
            "swipeActions",
            "swipeActionsLoop",
            "swipeActionsReversed",
            "aiFeatures",
            "inlineMathResult",
            "stickerTime",
            "hideDialogsSearchBar",
            "Appearance-M3Styles-ChatHeader",
            "Appearance-M3Styles-NavigationBar",
            "Appearance-M3Styles-FloatingButton",
            "Appearance-Sections",
            "glassOutlineStyle",
            "glassMessageMenu",
            "feedBottomTab",
            "aiTemperature",
            "AI-Service-Reasoning"
    ));

    private boolean entriesFetched;
    private String entriesLangCode;
    private final ConcurrentHashMap<Integer, Entry> preparedEntries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> entriesStringAlias = new ConcurrentHashMap<>();

    private static class SingletonHolder {
        private static final SettingsRegistry INSTANCE = new SettingsRegistry();
    }

    public static SettingsRegistry getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private static String describe(UItem item) {
        return String.format("(UItem ID: %s; View type: %s; View: %s; Text: %s; Subtext: %s)",
                item.id, item.viewType, item.view == null ? null : item.view.getClass().getName(), item.text, TextUtils.concat(item.subtext, item.animatedText));
    }

    public static boolean isValidForSearch(UItem item) {
        if (item.id != 0 && !TextUtils.isEmpty(item.text)) {
            return true;
        }
        FileLog.e("[Extera] UItems with ID 0 or empty text cannot be added as search result. " + describe(item));
        return false;
    }

    public static boolean isValidForLinkAliases(UItem item) {
        if (item.id != 0) {
            return true;
        }
        FileLog.e("[Extera] Cannot set link aliases for UItems with ID 0. " + describe(item));
        return false;
    }

    public void addSearchEntry(BaseFragment fragment, UItem item) {
        if (!isValidForSearch(item)) {
            return;
        }
        Entry entry = Entry.fromUItem(fragment, item);
        if (!preparedEntries.containsKey(generateGUIDForUItem(fragment.getClass(), item))) {
            FileLog.d("[Extera] Added an entry: " + entry);
        }
        preparedEntries.putIfAbsent(entry.guid, entry);
    }

    public static boolean markAsNewFeature(String alias) {
        if (!newFeatures.contains(alias) || ExteraConfig.getDoNotMarkAsNew().contains(alias)) {
            return false;
        }
        Long showedAt = ExteraConfig.getNewFeaturesShowedAt().get(alias);
        if (showedAt == null || showedAt == 0) {
            ExteraConfig.getNewFeaturesShowedAt().put(alias, System.currentTimeMillis());
            ExteraConfig.getEditor().putString("newFeaturesShowedAt", ExteraConfig.getGSON().toJson(ExteraConfig.getNewFeaturesShowedAt())).apply();
            return true;
        }
        if (Math.abs(System.currentTimeMillis() - showedAt) <= 24 * 60 * 60 * 1000L) {
            return true;
        }
        ExteraConfig.getNewFeaturesShowedAt().remove(alias);
        ExteraConfig.getEditor().putString("newFeaturesShowedAt", ExteraConfig.getGSON().toJson(ExteraConfig.getNewFeaturesShowedAt()));
        ExteraConfig.getDoNotMarkAsNew().add(alias);
        ExteraConfig.getEditor().putString("doNotMarkAsNew", ExteraConfig.getGSON().toJson(ExteraConfig.getDoNotMarkAsNew())).apply();
        return false;
    }

    public void addLinkAliasForOption(String alias, BaseFragment fragment, UItem item) {
        if (!isValidForLinkAliases(item)) {
            return;
        }
        if (markAsNewFeature(alias) && item.text != null && item.text.length() > 0) {
            String text = item.text.toString();
            if (text.charAt(text.length() - 1) != 'd') {
                item.text = LocaleUtils.applyNewSpan(text);
            }
        }
        if (entriesStringAlias.containsKey(alias)) {
            FileLog.d("[Extera] Key '" + alias + "' already linked to an entry.");
            return;
        }
        Entry entry = preparedEntries.get(generateGUIDForUItem(fragment.getClass(), item));
        if (entry == null) {
            entry = Entry.fromUItem(fragment, item);
        }
        FileLog.d(String.format("[Extera] Added link alias %s for an entry %s", alias, entry));
        entriesStringAlias.put(alias, entry);
    }

    public void handleLink(String alias, String pluginId) {
        FileLog.d("[Extera] Setting link handler called with alias " + alias);
        if (!TextUtils.isEmpty(pluginId)) {
            PluginsController.openPluginSettings(pluginId, alias);
            return;
        }
        createEntriesIfNeeded();
        Entry entry = entriesStringAlias.get(alias);
        if (entry == null) {
            onSettingNotFound();
            return;
        }
        FileLog.d("[Extera] Found entry for alias: " + entry);
        FileLog.d("[Extera] Opening fragment...");
        openActivity(entry.fragmentClass, entry.itemId);
    }

    public void onSettingNotFound() {
        onSettingNotFound(LaunchActivity.getLastFragment());
    }

    public void onSettingNotFound(BaseFragment fragment) {
        BulletinFactory.of(fragment).createEmojiBulletin("🤷‍♂️", LocaleController.getString(R.string.NoSuchSetting)).show();
    }

    public String getFirstSettingLink(Class<? extends BaseFragment> fragmentClass, UItem item) {
        int guid = generateGUIDForUItem(fragmentClass, item);
        Map.Entry<String, Entry> found = entriesStringAlias.entrySet().stream()
                .filter(e -> e.getValue().guid == guid)
                .findFirst()
                .orElse(null);
        if (found == null) {
            return null;
        }
        return "https://t.me/exteraSettings?s=" + found.getKey();
    }

    public ProfileActivity.SearchAdapter.SearchResult[] getSearchResults(ProfileActivity.SearchAdapter searchAdapter) {
        createEntriesIfNeeded();
        return preparedEntries.values().stream()
                .map(entry -> entry.toSearchResult(searchAdapter))
                .toArray(ProfileActivity.SearchAdapter.SearchResult[]::new);
    }

    private int getCategoryIcon(Class<? extends BaseFragment> fragmentClass) {
        Integer icon = categoriesIcons.get(fragmentClass);
        return icon != null ? icon : 0;
    }

    private BaseFragment initiateFragment(Class<? extends BaseFragment> fragmentClass) {
        try {
            BaseFragment lastFragment = LaunchActivity.getLastFragment();
            if (lastFragment == null) {
                return null;
            }
            BaseFragment fragment = fragmentClass.getDeclaredConstructor().newInstance();
            fragment.setParentFragment(lastFragment);
            fragment.createActionBar(lastFragment.getContext());
            return fragment;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void openActivity(Class<? extends BaseFragment> fragmentClass, Integer itemId) {
        BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment == null) {
            return;
        }
        BaseFragment fragment = initiateFragment(fragmentClass);
        if (fragment == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> lastFragment.presentFragment(fragment));
        if (itemId != null && fragment instanceof BasePreferencesActivity) {
            BasePreferencesActivity preferencesActivity = (BasePreferencesActivity) fragment;
            AndroidUtilities.runOnUIThread(() -> preferencesActivity.scrollToItem(itemId));
        }
    }

    public boolean isSearchIndexReady() {
        return entriesFetched && TextUtils.equals(entriesLangCode, getCurrentLangCode());
    }

    private static String getCurrentLangCode() {
        LocaleController.LocaleInfo localeInfo = LocaleController.getInstance().getCurrentLocaleInfo();
        return localeInfo == null ? "" : localeInfo.getKey();
    }

    private void createEntriesIfNeeded() {
        String langCode = getCurrentLangCode();
        if (entriesFetched) {
            if (TextUtils.equals(entriesLangCode, langCode)) {
                return;
            }
            FileLog.d("[Extera] Language changed to " + langCode + ", rebuilding entries...");
            preparedEntries.clear();
        }
        FileLog.d("[Extera] Initialising activities...");
        // Instantiating the fragments makes them register their items via UItem.setSearchable / setLinkAlias
        categoriesIcons.keySet().forEach(this::initiateFragment);
        entriesFetched = true;
        entriesLangCode = langCode;
    }

    private static int generateGUIDForUItem(Class<?> fragmentClass, UItem item) {
        return Objects.hash(fragmentClass.getName(), item.id);
    }

    public static final class Entry {
        private final int guid;
        private final int itemId;
        private final String title;
        private final String subtext;
        private final int icon;
        private final Class<? extends BaseFragment> fragmentClass;

        private Entry(int guid, int itemId, String title, String subtext, int icon, Class<? extends BaseFragment> fragmentClass) {
            this.guid = guid;
            this.itemId = itemId;
            this.title = title;
            this.subtext = subtext;
            this.icon = icon;
            this.fragmentClass = fragmentClass;
        }

        public int guid() {
            return guid;
        }

        public int itemId() {
            return itemId;
        }

        public String title() {
            return title;
        }

        public String subtext() {
            return subtext;
        }

        public int icon() {
            return icon;
        }

        public Class<? extends BaseFragment> fragmentClass() {
            return fragmentClass;
        }

        public static Entry fromUItem(BaseFragment fragment, UItem item) {
            Class<? extends BaseFragment> fragmentClass = fragment.getClass();
            return new Entry(
                    generateGUIDForUItem(fragmentClass, item),
                    item.id,
                    item.text == null ? null : String.valueOf(item.text),
                    fragment instanceof BasePreferencesActivity ? ((BasePreferencesActivity) fragment).getTitle() : null,
                    getInstance().getCategoryIcon(fragmentClass),
                    fragmentClass
            );
        }

        public ProfileActivity.SearchAdapter.SearchResult toSearchResult(ProfileActivity.SearchAdapter searchAdapter) {
            return searchAdapter.new SearchResult(guid, title, String.valueOf(itemId), subtext, icon, () -> getInstance().openActivity(fragmentClass, itemId));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry other = (Entry) o;
            return guid == other.guid && itemId == other.itemId && icon == other.icon
                    && Objects.equals(title, other.title)
                    && Objects.equals(subtext, other.subtext)
                    && Objects.equals(fragmentClass, other.fragmentClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(guid, itemId, icon, title, subtext, fragmentClass);
        }

        @Override
        public String toString() {
            return "Entry[guid=" + guid + ", itemId=" + itemId + ", title=" + title + ", subtext=" + subtext + ", icon=" + icon + ", fragmentClass=" + fragmentClass + "]";
        }
    }
}
