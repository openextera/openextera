package com.exteragram.messenger.icons.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.icons.BaseIconPacks;
import com.exteragram.messenger.icons.IconManager;
import com.exteragram.messenger.icons.IconPack;
import com.exteragram.messenger.icons.ui.components.IconPackCell;
import com.exteragram.messenger.icons.ui.components.NewIconPackBottomSheet;
import com.exteragram.messenger.icons.ui.picker.IconPickerController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;

public class IconPacksActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final String BASE_PREFIX = "base.";
    private static final String[] BASE_PACKS = {"base.default", "base.solar", "base.remix"};

    private FragmentFloatingButton floatingButton;
    private Runnable reorderRunnable;
    private boolean scrollUpdated;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.iconPackUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.iconPackUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.iconPackUpdated) {
            updateAdapter();
        }
    }

    private void updateAdapter() {
        if (fragmentView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        floatingButton.setTranslationY(-bottom);
        super.onInsets(left, top, right, bottom);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);

        floatingButton = new FragmentFloatingButton(context, resourceProvider);
        floatingButton.imageView.setImageResource(R.drawable.msg_add);
        floatingButton.setContentDescription(LocaleController.getString(R.string.Add));
        floatingButton.setOnClickListener(v -> new NewIconPackBottomSheet(this, getContext()).show());
        if (view instanceof FrameLayout) {
            ((FrameLayout) view).addView(floatingButton, FragmentFloatingButton.createDefaultLayoutParams());
        }

        if (listView != null) {
            listView.allowReorder(true);
            listView.setReorderHandleOnly(true);
            listView.listenReorder(this::updateConfigFromReorder);
            listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    if (dy != 0 && scrollUpdated) {
                        floatingButton.setButtonVisible(dy < 0, true);
                    }
                    scrollUpdated = true;
                }
            });
        }
        return view;
    }

    private void updateConfigFromReorder(int sectionId, ArrayList<UItem> items) {
        ArrayList<String> ids = new ArrayList<>();
        for (UItem item : items) {
            if (item.object instanceof IconPack) {
                ids.add(((IconPack) item.object).getId());
            }
        }

        boolean enabledSection = countExistingPacks(ExteraConfig.getIconPacksLayout()) > 1 && sectionId == 0;
        if (enabledSection) {
            String basePack = null;
            for (String id : ExteraConfig.getIconPacksLayout()) {
                if (id.startsWith(BASE_PREFIX)) {
                    basePack = id;
                    break;
                }
            }
            ExteraConfig.getIconPacksLayout().clear();
            ExteraConfig.getIconPacksLayout().addAll(ids);
            if (basePack != null) {
                ExteraConfig.getIconPacksLayout().add(basePack);
            }
        } else {
            ExteraConfig.getIconPacksHidden().clear();
            ExteraConfig.getIconPacksHidden().addAll(ids);
        }
        ExteraConfig.saveIconPacksLayout();

        if (enabledSection) {
            if (reorderRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(reorderRunnable);
            }
            reorderRunnable = () -> IconManager.INSTANCE.initialize(true);
            AndroidUtilities.runOnUIThread(reorderRunnable, 500);
        }
        updateAdapter();
    }

    private int countExistingPacks(ArrayList<String> ids) {
        int count = 0;
        for (String id : ids) {
            if (!id.startsWith(BASE_PREFIX) && getPackById(id) != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.IconPacks);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String activeBasePack = "base.default";
        for (String id : ExteraConfig.getIconPacksLayout()) {
            if (id.startsWith(BASE_PREFIX)) {
                activeBasePack = id;
                break;
            }
        }

        adapter.whiteSectionStart();
        items.add(UItem.asHeader(LocaleController.getString(R.string.BasePacks)));
        for (String id : BASE_PACKS) {
            IconPack pack = getPackById(id);
            if (pack != null) {
                UItem item = IconPackCell.Factory.asIconPackCell(pack);
                item.checked = id.equals(activeBasePack);
                items.add(item);
            }
        }
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(LocaleController.getString(R.string.BaseIconPackInfo)));

        int enabledCount = countExistingPacks(ExteraConfig.getIconPacksLayout());
        if (enabledCount > 0) {
            adapter.whiteSectionStart();
            items.add(UItem.asHeader(LocaleController.getString(R.string.EnabledPacks)));
            addPackItems(items, adapter, ExteraConfig.getIconPacksLayout(), enabledCount > 1);
            adapter.whiteSectionEnd();
        }

        int hiddenCount = countExistingPacks(ExteraConfig.getIconPacksHidden());
        if (hiddenCount > 0) {
            if (enabledCount > 0) {
                items.add(UItem.asShadow());
            }
            adapter.whiteSectionStart();
            items.add(UItem.asHeader(LocaleController.getString(R.string.AllPacks)));
            addPackItems(items, adapter, ExteraConfig.getIconPacksHidden(), hiddenCount > 1);
            adapter.whiteSectionEnd();
        }

        if (enabledCount > 0 || hiddenCount > 0) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.IconPacksHint)));
        }
    }

    private void addPackItems(ArrayList<UItem> items, UniversalAdapter adapter, ArrayList<String> ids, boolean reorder) {
        if (reorder) {
            adapter.reorderSectionStart();
        }
        for (String id : ids) {
            if (id.startsWith(BASE_PREFIX)) {
                continue;
            }
            IconPack pack = getPackById(id);
            if (pack != null) {
                items.add(IconPackCell.Factory.asIconPackCell(pack).setReordering(reorder));
            }
        }
        if (reorder) {
            adapter.reorderSectionEnd();
        }
    }

    private IconPack getPackById(String id) {
        if (id.startsWith(BASE_PREFIX)) {
            return BaseIconPacks.INSTANCE.getBasePack(id);
        }
        return IconManager.INSTANCE.findPackById(id);
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof IconPack)) {
            return;
        }
        String id = ((IconPack) item.object).getId();
        if (id.startsWith(BASE_PREFIX)) {
            ExteraConfig.getIconPacksLayout().removeIf(packId -> packId.startsWith(BASE_PREFIX));
            ExteraConfig.getIconPacksLayout().add(id);
        } else if (ExteraConfig.getIconPacksLayout().contains(id)) {
            ExteraConfig.getIconPacksLayout().remove(id);
            if (!ExteraConfig.getIconPacksHidden().contains(id)) {
                ExteraConfig.getIconPacksHidden().add(0, id);
            }
        } else if (ExteraConfig.getIconPacksHidden().contains(id)) {
            ExteraConfig.getIconPacksHidden().remove(id);
            ExteraConfig.getIconPacksLayout().add(0, id);
        }
        ExteraConfig.saveIconPacksLayout();
        IconManager.INSTANCE.initialize(true);
        listView.adapter.update(true);
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof IconPack) {
            IconPack iconPack = (IconPack) item.object;
            String id = iconPack.getId();
            if (id.startsWith(BASE_PREFIX)) {
                return false;
            }
            ItemOptions.makeOptions(this, view)
                .addIf(ExteraConfig.getIconPacksLayout().contains(id), R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> editPack(iconPack))
                .add(R.drawable.msg_share, LocaleController.getString(R.string.ShareFile), () -> sharePack(iconPack))
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> showDeleteAlert(iconPack))
                .setScrimViewBackground(listView.getClipBackground(view))
                .show();
            return true;
        }
        return super.onLongClick(item, view, position, x, y);
    }

    private void editPack(IconPack iconPack) {
        ExteraConfig.setEditingIconPackId(iconPack.getId());
        IconPickerController.setActive((LaunchActivity) getParentActivity(), true);
        presentFragment(new IconPacksEditorActivity(iconPack));
    }

    private void sharePack(IconPack iconPack) {
        Utilities.globalQueue.postRunnable(() -> {
            File file = IconManager.INSTANCE.bundlePackBlocking(iconPack.getId());
            if (file != null) {
                AndroidUtilities.runOnUIThread(() -> shareFile(file));
            }
        });
    }

    private void shareFile(File file) {
        if (getParentActivity() == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getParentActivity().startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)));
    }

    private void showDeleteAlert(IconPack iconPack) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(LocaleController.getString(R.string.DeletePack))
            .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeletePackInfo, iconPack.getName())))
            .setPositiveButton(LocaleController.getString(R.string.Delete), (d, which) -> IconManager.INSTANCE.deletePack(iconPack.getId()))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        dialog.show();
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }
}
