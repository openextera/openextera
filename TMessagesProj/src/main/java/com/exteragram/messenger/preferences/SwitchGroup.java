package com.exteragram.messenger.preferences;

import android.view.View;

import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class SwitchGroup {

    private final List<Child> children = new ArrayList<>();
    private final BasePreferencesActivity fragment;
    private final int id;
    private final int titleRes;
    private final CharSequence title;

    private boolean expanded;
    private boolean searchable;
    private String linkAlias;
    private Runnable onChanged;

    public interface Setter {
        void set(boolean value);
    }

    public static final class Child {
        private final int id;
        private final int textRes;
        private final CharSequence text;
        private final BooleanSupplier visible;
        private final BooleanSupplier getter;
        private final Setter setter;
        private String newFeatureAlias;

        private Child(int id, int textRes, CharSequence text, BooleanSupplier visible, BooleanSupplier getter, Setter setter) {
            this.id = id;
            this.textRes = textRes;
            this.text = text;
            this.visible = visible;
            this.getter = getter;
            this.setter = setter;
        }

        private boolean isVisible() {
            return visible == null || visible.getAsBoolean();
        }

        private CharSequence text() {
            CharSequence result = text != null ? text : LocaleController.getString(textRes);
            if (newFeatureAlias != null && SettingsRegistry.markAsNewFeature(newFeatureAlias)) {
                return LocaleUtils.applyNewSpan(result);
            }
            return result;
        }
    }

    private SwitchGroup(BasePreferencesActivity fragment, int id, int titleRes, CharSequence title) {
        this.fragment = fragment;
        this.id = id;
        this.titleRes = titleRes;
        this.title = title;
    }

    public static SwitchGroup of(BasePreferencesActivity fragment, int id, int titleRes) {
        return new SwitchGroup(fragment, id, titleRes, null);
    }

    public static SwitchGroup of(BasePreferencesActivity fragment, int id, CharSequence title) {
        return new SwitchGroup(fragment, id, 0, title);
    }

    public SwitchGroup searchable() {
        searchable = true;
        return this;
    }

    public SwitchGroup linkAlias(String alias) {
        linkAlias = alias;
        return this;
    }

    public SwitchGroup onChanged(Runnable runnable) {
        onChanged = runnable;
        return this;
    }

    public SwitchGroup add(int id, int textRes, BooleanSupplier getter, Setter setter) {
        return add(new Child(id, textRes, null, null, getter, setter));
    }

    public SwitchGroup add(int id, CharSequence text, BooleanSupplier getter, Setter setter) {
        return add(new Child(id, 0, text, null, getter, setter));
    }

    public SwitchGroup addIf(BooleanSupplier visible, int id, int textRes, BooleanSupplier getter, Setter setter) {
        return add(new Child(id, textRes, null, visible, getter, setter));
    }

    public SwitchGroup markNew(String alias) {
        children.get(children.size() - 1).newFeatureAlias = alias;
        return this;
    }

    private SwitchGroup add(Child child) {
        children.add(child);
        return this;
    }

    public void fill(ArrayList<UItem> items) {
        int checkedCount = count(true);
        UItem item = UItem.asExteraExpandableSwitch(id, title(), String.format("%d/%d", checkedCount, count(false)), this::onSwitchClick)
                .setChecked(checkedCount > 0)
                .setCollapsed(!expanded);
        if (searchable) {
            item.setSearchable(fragment);
        }
        if (linkAlias != null) {
            item.setLinkAlias(linkAlias, fragment);
        }
        items.add(item);
        if (expanded) {
            for (Child child : children) {
                if (child.isVisible()) {
                    items.add(UItem.asRoundCheckbox(child.id, child.text()).setChecked(child.getter.getAsBoolean()).pad());
                }
            }
        }
    }

    public void onClick(UItem item) {
        if (item.id == id) {
            boolean wasExpanded = expanded;
            expanded = !wasExpanded;
            item.setCollapsed(wasExpanded);
            fragment.listView.adapter.update(true);
            return;
        }
        for (Child child : children) {
            if (child.id == item.id) {
                fragment.toggleBooleanSettingAndRefresh(item, child.setter::set);
                if (onChanged != null) {
                    onChanged.run();
                }
                return;
            }
        }
    }

    private void onSwitchClick(View view) {
        UItem item = fragment.listView.findItemByItemId(((TextCheckCell2) view).id);
        boolean checked = !item.checked;
        for (Child child : children) {
            if (child.isVisible()) {
                child.setter.set(checked);
            }
        }
        item.setChecked(checked);
        fragment.listView.adapter.update(true);
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private CharSequence title() {
        return title != null ? title : LocaleController.getString(titleRes);
    }

    private int count(boolean onlyChecked) {
        int count = 0;
        for (Child child : children) {
            if (child.isVisible() && (!onlyChecked || child.getter.getAsBoolean())) {
                count++;
            }
        }
        return count;
    }
}
