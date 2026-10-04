package com.exteragram.messenger.pillstack.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.pillstack.core.PillRegistry;
import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PillStackPreferencesActivity extends BasePreferencesActivity {

    private static final int ID_INFINITE_SCROLLING = 1000;

    private final HashMap<Integer, ItemInfo> itemDetails = new HashMap<>();
    private Drawable reorderIcon;
    private ActionBarMenuItem resetItem;
    private int activeSectionId = -1;
    private int hiddenSectionId = -1;

    public static class ItemInfo {
        CharSequence name;
        int iconRes;
        int iconColorTop;
        int iconColorBottom;

        public ItemInfo(CharSequence name, int iconRes, int iconColorTop, int iconColorBottom) {
            this.name = name;
            this.iconRes = iconRes;
            this.iconColorTop = iconColorTop;
            this.iconColorBottom = iconColorBottom;
        }
    }

    @Override
    public void initializeOptionStrings() {
        initItemDetails();
    }

    private void initItemDetails() {
        for (PillRegistry.PillInfo pill : PillRegistry.getRegisteredPills()) {
            itemDetails.put(pill.id(), new ItemInfo(pill.name(), pill.iconRes(), pill.iconColorTop(), pill.iconColorBottom()));
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.PillStackPills);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
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

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (reorderIcon == null) {
            reorderIcon = ContextCompat.getDrawable(getContext(), R.drawable.list_reorder);
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.Settings)));
        items.add(UItem.asCheck(ID_INFINITE_SCROLLING, LocaleController.getString(R.string.PillStackInfiniteScrolling)).setSearchable(this).setLinkAlias("pillStackInfiniteScrolling", this).setChecked(PillStackConfig.getInfiniteScrolling()));
        items.add(UItem.asShadow(null));
        activeSectionId = -1;
        hiddenSectionId = -1;
        if (!PillStackConfig.getActivePills().isEmpty()) {
            activeSectionId = addMenuSection(items, adapter, LocaleController.getString(R.string.PillStackActivePills), PillStackConfig.getActivePills());
            items.add(UItem.asShadow(LocaleController.getString(R.string.PillStackPillsSettingsInfo)));
        }
        if (PillStackConfig.getHiddenPills().isEmpty()) {
            return;
        }
        hiddenSectionId = addMenuSection(items, adapter, LocaleController.getString(R.string.PillStackHiddenPills), PillStackConfig.getHiddenPills());
        if (PillStackConfig.getActivePills().isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PillStackPillsSettingsInfo)));
        }
    }

    private int addMenuSection(ArrayList<UItem> items, UniversalAdapter adapter, String title, List<Integer> pills) {
        adapter.whiteSectionStart();
        items.add(UItem.asHeader(title));
        int sectionId = adapter.reorderSectionStart();
        for (Integer id : pills) {
            ItemInfo info = itemDetails.get(id);
            if (info != null) {
                items.add(createMenuItem(id, info));
            }
        }
        adapter.reorderSectionEnd();
        adapter.whiteSectionEnd();
        return sectionId;
    }

    private UItem createMenuItem(int id, ItemInfo info) {
        UItem item = UItem.asButton(id, info.iconRes, info.name);
        item.object2 = reorderIcon;
        item.bind = view -> {
            if (view instanceof TextCell) {
                TextCell cell = (TextCell) view;
                cell.setColorfulIcon(info.iconColorTop, info.iconColorBottom, info.iconRes);
                cell.setImageLeft(21);
                cell.setOffsetFromImage(65);
            }
        };
        return item;
    }

    private void updateConfigFromReorder(int sectionId, ArrayList<UItem> items) {
        ArrayList<Integer> ids = new ArrayList<>();
        for (UItem item : items) {
            ids.add(item.id);
        }
        if (sectionId == activeSectionId) {
            PillStackConfig.getActivePills().clear();
            PillStackConfig.getActivePills().addAll(ids);
        } else if (sectionId == hiddenSectionId) {
            PillStackConfig.getHiddenPills().clear();
            PillStackConfig.getHiddenPills().addAll(ids);
        }
        saveAndNotify();
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id <= 0) {
            return;
        }
        if (id == ID_INFINITE_SCROLLING) {
            toggleBooleanSettingAndRefresh(item, PillStackConfig::setInfiniteScrolling);
            return;
        }
        List<Integer> activePills = PillStackConfig.getActivePills();
        List<Integer> hiddenPills = PillStackConfig.getHiddenPills();
        if (activePills.contains(id)) {
            activePills.remove(Integer.valueOf(id));
            if (!hiddenPills.contains(id)) {
                hiddenPills.add(0, id);
            }
        } else if (hiddenPills.contains(id)) {
            hiddenPills.remove(Integer.valueOf(id));
            activePills.add(id);
        }
        saveAndNotify();
    }

    private void saveAndNotify() {
        PillStackConfig.savePillsLayout();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        updateResetButtonVisibility();
    }

    private void updateResetButtonVisibility() {
        updateResetButtonVisibility(true);
    }

    private void updateResetButtonVisibility(boolean animated) {
        if (resetItem == null) {
            return;
        }
        AndroidUtilities.updateViewVisibilityAnimated(resetItem, !PillStackConfig.getActivePills().equals(PillStackConfig.getDefaultActivePills()), 0.5f, animated);
    }

    private void resetToDefault() {
        PillStackConfig.getActivePills().clear();
        PillStackConfig.getActivePills().addAll(PillStackConfig.getDefaultActivePills());
        PillStackConfig.getHiddenPills().clear();
        for (PillRegistry.PillInfo pill : PillRegistry.getRegisteredPills()) {
            if (!PillStackConfig.getActivePills().contains(pill.id())) {
                PillStackConfig.getHiddenPills().add(pill.id());
            }
        }
        PillStackConfig.savePillsLayout();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        updateResetButtonVisibility();
    }
}
