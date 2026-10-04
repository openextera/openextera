package com.exteragram.messenger.icons.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.icons.ExteraResources;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.icons.ui.components.NewIconPackBottomSheet;
import com.exteragram.messenger.icons.ui.picker.IconPickerController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class IconPacksEditorActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int search_button = 0;
    private static final int edit_button = 1;
    private static final int save_and_exit_button = 2;
    private static final int other_button = 3;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_REPLACED = 1;
    private static final int FILTER_NOT_REPLACED = 2;

    private static final ArrayList<UItem> cachedIconItems = new ArrayList<>();
    private static boolean isIconsLoaded = false;
    private static boolean isLoading = false;

    private final ActionBarMenuSubItem[] filterItems = new ActionBarMenuSubItem[3];
    private int iconFilter = FILTER_ALL;
    private IconPack iconPack;
    private ActionBarMenuItem otherItem;
    private String[] packItemLowerNames;
    private ArrayList<UItem> packItems;
    private IconPack packItemsFor;
    private String query;
    private Runnable searchRunnable;
    private boolean searching;

    public IconPacksEditorActivity(IconPack iconPack) {
        this.iconPack = iconPack;
    }

    @Override
    public View createView(Context context) {
        super.createView(context);

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(search_button, R.drawable.outline_header_search)
            .setIsSearchField(true)
            .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
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
                    updateAdapter();
                }

                @Override
                public void onTextChanged(EditText editText) {
                    if (searchRunnable != null) {
                        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                    }
                    searchRunnable = () -> {
                        query = editText.getText().toString();
                        updateAdapter();
                    };
                    AndroidUtilities.runOnUIThread(searchRunnable, 200);
                }
            })
            .setSearchPaddingStart(7);

        otherItem = menu.addItem(other_button, R.drawable.ic_ab_other);
        otherItem.addSwipeBackItem(R.drawable.msg_select, null, LocaleController.getString(R.string.IconPickerFilter), createFilterLayout(context));
        otherItem.addSubItem(edit_button, R.drawable.msg_edit, LocaleController.getString(R.string.Edit));
        if (ExteraConfig.getEditingIconPackId() != null) {
            ActionBarMenuSubItem saveItem = otherItem.addSubItem(save_and_exit_button, R.drawable.ic_ab_done, LocaleController.getString(R.string.IconPickerSaveAndExit));
            int color = getThemedColor(Theme.key_featuredStickers_addButtonPressed);
            saveItem.setColors(color, color);
        }

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == save_and_exit_button) {
                    ExteraConfig.setEditingIconPackId(null);
                    if (getParentActivity() instanceof LaunchActivity) {
                        IconPickerController.setActive((LaunchActivity) getParentActivity(), false);
                    }
                    finishFragment();
                } else if (id == edit_button) {
                    new NewIconPackBottomSheet(IconPacksEditorActivity.this, getContext(), iconPack).show();
                }
            }
        });

        return fragmentView;
    }

    private void updateAdapter() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private ActionBarPopupWindow.ActionBarPopupWindowLayout createFilterLayout(Context context) {
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(context, 0, getResourceProvider());
        layout.setFitItems(true);

        ActionBarMenuItem.addItem(layout, R.drawable.msg_arrow_back, LocaleController.getString(R.string.Back), false, getResourceProvider()).setOnClickListener(v -> {
            if (otherItem == null || otherItem.getPopupLayout() == null || otherItem.getPopupLayout().getSwipeBack() == null) {
                return;
            }
            otherItem.getPopupLayout().getSwipeBack().closeForeground();
        });

        View gap = ActionBarMenuItem.addGap(0, layout);
        gap.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        gap.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator));

        createFilterItem(layout, LocaleController.getString(R.string.IconPickerAllIcons), FILTER_ALL);
        createFilterItem(layout, LocaleController.getString(R.string.IconPickerReplacedIcons), FILTER_REPLACED);
        createFilterItem(layout, LocaleController.getString(R.string.IconPickerNotReplacedIcons), FILTER_NOT_REPLACED);
        updateFilterChecks();
        return layout;
    }

    private void createFilterItem(ActionBarPopupWindow.ActionBarPopupWindowLayout layout, String text, int filter) {
        ActionBarMenuSubItem item = new ActionBarMenuSubItem(layout.getContext(), true, false, false, getResourceProvider());
        item.setTextAndIcon(text, 0);
        item.setMinimumWidth(AndroidUtilities.dp(196));
        layout.addView(item, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        item.setOnClickListener(v -> setIconFilter(filter));
        filterItems[filter] = item;
    }

    private void setIconFilter(int filter) {
        if (iconFilter == filter) {
            return;
        }
        iconFilter = filter;
        updateFilterChecks();
        updateAdapter();
    }

    private void updateFilterChecks() {
        for (int i = 0; i < filterItems.length; i++) {
            if (filterItems[i] != null) {
                filterItems[i].setChecked(iconFilter == i);
            }
        }
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.iconPackUpdated);
        loadIconsAsync();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.iconPackUpdated);
        super.onFragmentDestroy();
    }

    private void loadIconsAsync() {
        if ((isIconsLoaded && !cachedIconItems.isEmpty()) || isLoading) {
            return;
        }
        isLoading = true;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<UItem> items = new ArrayList<>(1500);
            HashMap<String, Integer> systemIcons = new HashMap<>(IconManager.INSTANCE.getSystemIcons());
            if (systemIcons.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    isLoading = false;
                    IconManager.INSTANCE.initialize(true);
                });
                return;
            }
            for (Map.Entry<String, Integer> entry : systemIcons.entrySet()) {
                items.add(EditorIconCell.Factory.asIcon(entry.getValue(), entry.getKey(), null));
            }
            items.sort(Comparator.comparing((UItem item) -> item.text.toString()));
            AndroidUtilities.runOnUIThread(() -> {
                cachedIconItems.clear();
                cachedIconItems.addAll(items);
                isIconsLoaded = true;
                isLoading = false;
                packItems = null;
                updateAdapter();
            });
        });
    }

    @Override
    public String getTitle() {
        return iconPack == null || iconPack.getName() == null ? LocaleController.getString(R.string.NewIconPack) : iconPack.getName();
    }

    private ArrayList<UItem> getPackItems() {
        if (packItems != null && Objects.equals(packItemsFor, iconPack)) {
            return packItems;
        }
        int size = cachedIconItems.size();
        ArrayList<UItem> items = new ArrayList<>(size);
        String[] lowerNames = new String[size];
        for (int i = 0; i < size; i++) {
            UItem cached = cachedIconItems.get(i);
            items.add(EditorIconCell.Factory.asIcon(cached.id, cached.text, iconPack));
            lowerNames[i] = cached.text == null ? "" : cached.text.toString().toLowerCase(Locale.getDefault());
        }
        packItems = items;
        packItemLowerNames = lowerNames;
        packItemsFor = iconPack;
        return items;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (getContext() == null || !isIconsLoaded) {
            return;
        }
        ArrayList<UItem> packItems = getPackItems();
        String lowerQuery = searching && !TextUtils.isEmpty(query) ? query.toLowerCase(Locale.getDefault()) : null;
        Map<String, String> icons = iconPack == null ? null : iconPack.getIcons();
        for (int i = 0; i < packItems.size(); i++) {
            if (lowerQuery != null && !packItemLowerNames[i].contains(lowerQuery)) {
                continue;
            }
            UItem item = packItems.get(i);
            if (iconFilter != FILTER_ALL) {
                String name = item.text == null ? null : item.text.toString();
                boolean replaced = name != null && icons != null && icons.containsKey(name);
                if (iconFilter == FILTER_REPLACED && !replaced || iconFilter == FILTER_NOT_REPLACED && replaced) {
                    continue;
                }
            }
            items.add(item);
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        IconManager.INSTANCE.showReplaceAlert(getContext(), item.id, iconPack);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.iconPackUpdated || getContext() == null) {
            return;
        }
        if (!isIconsLoaded) {
            loadIconsAsync();
        }
        if (iconPack != null) {
            IconPack updatedPack = IconManager.INSTANCE.findPackById(iconPack.getId());
            if (updatedPack == null) {
                finishFragment();
                return;
            }
            iconPack = updatedPack;
            actionBar.setTitle(getTitle());
        }
        Parcelable state = listView != null && listView.getLayoutManager() != null ? listView.getLayoutManager().onSaveInstanceState() : null;
        updateAdapter();
        if (state != null && listView != null && listView.getLayoutManager() != null) {
            listView.getLayoutManager().onRestoreInstanceState(state);
        }
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

    @SuppressLint("ViewConstructor")
    public static class EditorIconCell extends TextCell {

        private int boundResId;

        public EditorIconCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context, resourcesProvider);
        }

        public static class Factory extends UItem.UItemFactory<EditorIconCell> {
            static {
                setup(new Factory());
            }

            @Override
            public EditorIconCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new EditorIconCell(context, resourcesProvider);
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (a.id != b.id || !TextUtils.equals(a.text, b.text)) {
                    return false;
                }
                IconPack packA = a.object instanceof IconPack ? (IconPack) a.object : null;
                IconPack packB = b.object instanceof IconPack ? (IconPack) b.object : null;
                if (packA == packB) {
                    return true;
                }
                if (packA == null || packB == null) {
                    return false;
                }
                String name = a.text.toString();
                return Objects.equals(packA.getIcons().get(name), packB.getIcons().get(name));
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                final EditorIconCell cell = (EditorIconCell) view;
                cell.reset();
                cell.boundResId = item.id;

                Drawable original;
                if (cell.getContext().getResources() instanceof ExteraResources) {
                    original = ((ExteraResources) cell.getContext().getResources()).getOriginalDrawable(item.id);
                } else {
                    original = cell.getContext().getResources().getDrawable(item.id);
                }
                Drawable replaced;
                if (item.object instanceof IconPack) {
                    replaced = IconManager.INSTANCE.getCachedPackIconDrawable((IconPack) item.object, item.id);
                } else {
                    replaced = IconManager.INSTANCE.getCachedDrawable(item.id);
                }
                if (original != null) {
                    original = original.mutate();
                }
                cell.setTextAndIconAndValueDrawable(item.text, original, replaced, divider);
                cell.setIsIcon(true);
                cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                cell.setOffsetFromImage(68);

                if (replaced == null) {
                    final int resId = item.id;
                    Utilities.Callback<Drawable> callback = drawable -> {
                        if (cell.boundResId == resId) {
                            cell.getValueImageView().setImageDrawable(drawable);
                        }
                    };
                    if (item.object instanceof IconPack) {
                        IconManager.INSTANCE.requestPackIconDrawable((IconPack) item.object, resId, callback);
                    } else {
                        IconManager.INSTANCE.requestDrawable(resId, callback);
                    }
                }
            }

            public static UItem asIcon(int resId, CharSequence name, Object object) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = resId;
                item.text = name;
                item.object = object;
                return item;
            }
        }
    }
}
