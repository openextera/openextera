package com.exteragram.messenger.components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;

import com.exteragram.messenger.utils.ui.FolderIcons;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public abstract class IconSelectorAlert {

    public interface OnIconSelectedListener {
        void onIconSelected(String emoticon);
    }

    public static void show(BaseFragment fragment, View view, String selectedIcon, OnIconSelectedListener listener) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        int selectedColor = Theme.getColor(Theme.key_windowBackgroundWhiteValueText, resourcesProvider);
        int iconColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider);
        int selectorColor = Theme.getColor(Theme.key_listSelector, resourcesProvider);

        View parent = (View) view.getParent();
        ItemOptions options = ItemOptions.makeOptions(fragment, parent)
                .setGravity(Gravity.LEFT)
                .translate(view.getX() - AndroidUtilities.dp(13), 0);
        if (parent.getParent() instanceof RecyclerListView) {
            options.setScrimViewBackground(((RecyclerListView) parent.getParent()).getClipBackground(parent));
        }

        GridLayout gridLayout = new GridLayout(context);
        int columns = 6;
        while (columns > 1 && AndroidUtilities.dp(columns * 50 + 40) > AndroidUtilities.displaySize.x) {
            columns--;
        }
        gridLayout.setColumnCount(columns);

        for (String icon : FolderIcons.folderIcons.keySet()) {
            boolean selected = icon.equals(selectedIcon);
            ImageView button = new ImageView(context);
            button.setScaleType(ImageView.ScaleType.FIT_XY);
            button.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
            button.setImageResource(FolderIcons.getTabIcon(icon));
            button.setColorFilter(new PorterDuffColorFilter(selected ? selectedColor : iconColor, PorterDuff.Mode.MULTIPLY));
            button.setBackground(Theme.createRadSelectorDrawable(selected ? Theme.multAlpha(selectedColor, 40 / 255f) : 0, selectorColor, 7, 7));
            button.setOnClickListener(v -> {
                options.dismiss();
                if (selected) {
                    return;
                }
                listener.onIconSelected(icon);
            });
            gridLayout.addView(button, LayoutHelper.createFrame(48, 48, Gravity.CENTER, 1, 1, 1, 1));
        }
        options.addView(gridLayout, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 4));
        options.show();
    }
}
