package com.exteragram.messenger.feed.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.feed.FeedChannelActions;
import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

public class FeedChannelsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_SEARCH = 0;
    private static final int MENU_SELECT_ALL = 1;
    private static final int MENU_DESELECT_ALL = 2;
    private static final int MENU_OTHER = 3;

    private static final Comparator<TLRPC.Chat> BY_TITLE = Comparator.comparing(chat -> chat.title == null ? "" : chat.title.toLowerCase(Locale.ROOT));

    public enum SettingItem {
        FEED_TAB,
        WIDE_POSTS,
        UNREAD_COUNTER,
        ARCHIVE;

        public int getId() {
            return ordinal() + 1000;
        }
    }

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private ActionBarMenuItem otherItem;
    private String query;
    private boolean searching;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.FeedSettings);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogDeleted);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogDeleted);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.feedNeedReload) {
            reloadChannels();
        } else if (id == NotificationCenter.dialogDeleted) {
            removeChannel((Long) args[0]);
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SELECT_ALL) {
                    setAllExcluded(false);
                } else if (id == MENU_DESELECT_ALL) {
                    setAllExcluded(true);
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searching = true;
                if (otherItem != null) {
                    otherItem.setVisibility(View.GONE);
                }
            }

            @Override
            public void onSearchCollapse() {
                searching = false;
                query = null;
                if (otherItem != null) {
                    otherItem.setVisibility(View.VISIBLE);
                }
                if (listView != null) {
                    listView.adapter.update(true);
                }
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim().toLowerCase(Locale.getDefault());
                if (listView != null) {
                    listView.adapter.update(true);
                }
            }
        });
        searchItem.setSearchPaddingStart(7);
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        otherItem.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll));
        otherItem.addSubItem(MENU_DESELECT_ALL, R.drawable.msg_cancel, LocaleController.getString(R.string.DeselectAll));
        reloadChannels();
        return view;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!searching) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            actionBar.closeSearchField();
        }
        return false;
    }

    private void reloadChannels() {
        FeedController.getInstance(currentAccount).loadChannels(true, (loaded, includedCount, failed, configGeneration) -> {
            if (failed) {
                return;
            }
            channels.clear();
            for (int i = 0; i < loaded.size(); i++) {
                TLRPC.Chat chat = loaded.get(i);
                TLRPC.Chat actual = getMessagesController().getChat(chat.id);
                channels.add(actual != null ? actual : chat);
            }
            channels.sort(BY_TITLE);
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void removeChannel(long dialogId) {
        for (int i = 0; i < channels.size(); i++) {
            if (-channels.get(i).id == dialogId) {
                channels.remove(i);
                if (listView != null) {
                    listView.adapter.update(true);
                }
                return;
            }
        }
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        boolean noQuery = TextUtils.isEmpty(query);
        if (noQuery) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
            items.add(UItem.asCheck(SettingItem.FEED_TAB.getId(), LocaleController.getString(R.string.FeedBottomTab), LocaleController.getString(R.string.FeedBottomTabInfo), true).setChecked(ExteraConfig.getShowFeedTab()).setSearchable(this).setLinkAlias("feedBottomTab", this));
            items.add(UItem.asCheck(SettingItem.WIDE_POSTS.getId(), LocaleController.getString(R.string.WidePostsInFeed)).setChecked(ExteraConfig.getWidePostsInFeed()).setSearchable(this).setLinkAlias("feedWidePosts", this));
            items.add(UItem.asCheck(SettingItem.UNREAD_COUNTER.getId(), LocaleController.getString(R.string.FeedUnreadCounter)).setChecked(ExteraConfig.getShowFeedUnreadCounter()).setSearchable(this).setLinkAlias("feedUnreadCounter", this));
            items.add(UItem.asCheck(SettingItem.ARCHIVE.getId(), LocaleController.getString(R.string.FeedIncludeArchived)).setChecked(feedConfig.isIncludeArchived()).setSearchable(this).setLinkAlias("feedIncludeArchived", this));
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedIncludeArchivedInfo)));
        }
        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            if (!noQuery && (chat.title == null || !chat.title.toLowerCase(Locale.getDefault()).contains(query))) {
                continue;
            }
            boolean excluded = feedConfig.isExcluded(-chat.id);
            (excluded ? hidden : shown).add(UItem.asUserCheckbox(Long.hashCode(chat.id), chat).setChecked(!excluded));
        }
        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedShownChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow());
            }
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedHiddenChannels)));
            items.addAll(hidden);
        }
        if (noQuery && (!shown.isEmpty() || !hidden.isEmpty())) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedChannelsInfo)));
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) item.object;
            toggleBooleanSettingAndRefresh(item, checked -> FeedConfig.getInstance(currentAccount).setExcluded(-chat.id, !checked));
            return;
        }
        SettingItem settingItem = null;
        for (SettingItem entry : SettingItem.values()) {
            if (entry.getId() == item.id) {
                settingItem = entry;
                break;
            }
        }
        if (settingItem == null) {
            return;
        }
        switch (settingItem) {
            case FEED_TAB:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setShowFeedTab);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                getNotificationCenter().postNotificationName(NotificationCenter.feedTabVisibleToggled);
                break;
            case WIDE_POSTS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setWidePostsInFeed);
                parentLayout.rebuildFragments(AndroidUtilities.isTablet() ? 1 : 0);
                break;
            case UNREAD_COUNTER:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setShowFeedUnreadCounter);
                getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, 0);
                break;
            case ARCHIVE:
                FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
                feedConfig.setIncludeArchived(!feedConfig.isIncludeArchived());
                reloadChannels();
                break;
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof TLRPC.Chat)) {
            return super.onLongClick(item, view, position, x, y);
        }
        TLRPC.Chat chat = (TLRPC.Chat) item.object;
        ItemOptions.makeOptions(this, view)
            .setLongPressSelectionEnabled(false)
            .setScrimViewBackground(listView.getClipBackground(view))
            .add(R.drawable.msg_channel, LocaleController.getString(R.string.OpenChannel2), () -> presentFragment(ChatActivity.of(-chat.id)))
            .addIf(FeedChannelActions.canLeave(chat), R.drawable.msg_leave, LocaleController.getString(R.string.LeaveChannelMenu), true, () -> FeedChannelActions.leaveChannel(this, chat, null, null))
            .show();
        return true;
    }

    private void setAllExcluded(boolean excluded) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        if (excluded) {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(-channels.get(i).id);
            }
            feedConfig.excludeAll(dialogIds);
        } else {
            feedConfig.clearExcluded();
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }
}
