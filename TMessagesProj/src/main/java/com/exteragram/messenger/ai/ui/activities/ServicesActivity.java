package com.exteragram.messenger.ai.ui.activities;

import android.content.Intent;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.network.backend.OnDeviceAvailability;
import com.exteragram.messenger.ai.ui.OnDeviceModelDialogs;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

public class ServicesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_NEW_SERVICE = 1;
    private static final int ID_SERVICE_START = 100;
    private static final long STATUS_POLL_INTERVAL = 5000;

    private Drawable newServiceCircle;
    private Drawable newServicePlus;
    private CombinedDrawable newServiceDrawable;

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            if (getContext() == null) {
                return;
            }
            OnDeviceAvailability.refresh(ServicesActivity.this::reloadServices);
            if (OnDeviceAvailability.isDownloadInProgress()) {
                AndroidUtilities.runOnUIThread(this, STATUS_POLL_INTERVAL);
            }
        }
    };

    @Override
    public boolean needHideTitle() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.servicesUpdated);
        OnDeviceAvailability.refresh(this::reloadServices);
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        OnDeviceAvailability.refresh(this::reloadServices);
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
        AndroidUtilities.runOnUIThread(statusPoll, STATUS_POLL_INTERVAL);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
        getNotificationCenter().removeObserver(this, NotificationCenter.servicesUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.servicesUpdated) {
            reloadServices();
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Services);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.ServicesInfo), "exteraGramPlaceholders", "🔑"));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Services)));
        List<Service> services = AiController.getInstance().getAll();
        for (int i = 0; i < services.size(); i++) {
            Service service = services.get(i);
            CharSequence title = service.isOnDevice() ? applyOnDeviceBadge(service.getModel()) : service.getModel();
            CharSequence subtitle = service.isOnDevice() ? LocaleController.getString(R.string.AIOnDevice) : service.getUrl();
            UItem item = UItem.asRadio2(ID_SERVICE_START + i, title, subtitle).setChecked(service.isSelected());
            item.object = service;
            items.add(item);
        }
        UItem newService = UItem.asButton(ID_NEW_SERVICE, getNewServiceDrawable(), LocaleController.getString(R.string.NewService)).accent();
        newService.pad = 61;
        items.add(newService);
    }

    private CombinedDrawable getNewServiceDrawable() {
        if (newServiceCircle == null) {
            newServiceCircle = getContext().getResources().getDrawable(R.drawable.poll_add_circle).mutate();
            newServicePlus = getContext().getResources().getDrawable(R.drawable.poll_add_plus).mutate();
            newServiceDrawable = new CombinedDrawable(newServiceCircle, newServicePlus) {
                {
                    translateX = AndroidUtilities.dp(2);
                }

                @Override
                public void setColorFilter(ColorFilter colorFilter) {
                }
            };
        }
        newServiceCircle.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_switchTrackChecked), PorterDuff.Mode.MULTIPLY));
        newServicePlus.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_checkboxCheck), PorterDuff.Mode.MULTIPLY));
        return newServiceDrawable;
    }

    private CharSequence applyOnDeviceBadge(CharSequence title) {
        if (OnDeviceAvailability.isDownloadInProgress()) {
            int percent = OnDeviceAvailability.getDownloadPercent();
            String badge = percent >= 0 ? percent + "%" : LocaleController.getString(R.string.AIOnDeviceBadgeDownloading);
            return LocaleUtils.applyBadge(title, badge, Theme.key_chats_unreadCounterMuted, Theme.key_chats_unreadCounterText);
        }
        if (OnDeviceAvailability.needsDownload()) {
            return LocaleUtils.applyBadge(title, LocaleController.getString(R.string.AIOnDeviceBadgeDownload), Theme.key_chats_unreadCounterMuted, Theme.key_chats_unreadCounterText);
        }
        if (OnDeviceAvailability.isReady()) {
            return LocaleUtils.applyBadge(title, LocaleController.getString(R.string.AIOnDeviceBadgeReady), Theme.key_featuredStickers_addButton, Theme.key_featuredStickers_buttonText);
        }
        return LocaleUtils.applyBadge(title, LocaleController.getString(R.string.AIOnDeviceBadgeUnavailable), Theme.key_chats_unreadCounterMuted, Theme.key_chats_unreadCounterText);
    }

    private void reloadServices() {
        AiController.getInstance().loadServices();
        if (getContext() == null || listView == null || listView.adapter == null) {
            return;
        }
        listView.adapter.update(true);
    }

    private void onDeviceUnavailable(Service service) {
        if (getContext() == null) {
            return;
        }
        reloadServices();
        if (OnDeviceAvailability.isReady()) {
            if (!service.isSelected()) {
                AiConfig.setSelectedServices(service);
                reloadServices();
            }
            return;
        }
        if (OnDeviceAvailability.needsDownload()) {
            OnDeviceModelDialogs.showDownloadDialog(this);
        } else {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.AIErrorOnDeviceInfo)).show();
        }
    }

    private void showOnDeviceOptions(View view) {
        ItemOptions options = ItemOptions.makeOptions(this, view);
        if (OnDeviceAvailability.isThinkingSupported()) {
            options.addChecked(AiConfig.getOnDeviceReasoning(), R.drawable.msg_discuss, LocaleController.getString(R.string.AIReasoning), () -> {
                AiConfig.setOnDeviceReasoning(!AiConfig.getOnDeviceReasoning());
                reloadServices();
            });
        }
        options.addChecked(AiConfig.getOnDevicePreviewModel(), R.drawable.msg_fave, LocaleController.getString(R.string.AIOnDevicePreviewModel),
                () -> changeModelConfig(() -> AiConfig.setOnDevicePreviewModel(!AiConfig.getOnDevicePreviewModel())));
        options.addChecked(AiConfig.getOnDeviceFastModel(), R.drawable.msg_speed, LocaleController.getString(R.string.AIOnDeviceFastModel),
                () -> changeModelConfig(() -> AiConfig.setOnDeviceFastModel(!AiConfig.getOnDeviceFastModel())));
        options.addGap();
        options.add(R.drawable.msg_reset, LocaleController.getString(R.string.AIOnDeviceRefresh), () -> OnDeviceAvailability.refresh(this::reloadServices))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.AIOnDeviceManage), this::openAiCoreSettings)
                .setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)
                .setScrimViewBackground(listView.getClipBackground(view))
                .show();
    }

    private void changeModelConfig(Runnable change) {
        change.run();
        OnDeviceAvailability.onModelConfigChanged(this::reloadServices);
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
        AndroidUtilities.runOnUIThread(statusPoll, STATUS_POLL_INTERVAL);
    }

    private void openAiCoreSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:com.google.android.aicore"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.AIOnDeviceManageUnavailable)).show();
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_SERVICE_START && item.object instanceof Service) {
            Service service = (Service) item.object;
            if (service.isOnDevice() && !OnDeviceAvailability.isReady()) {
                if (OnDeviceAvailability.isDownloadInProgress()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_download, LocaleController.getString(R.string.AIErrorOnDeviceDownloading), LocaleController.getString(R.string.AIOnDeviceManage), this::openAiCoreSettings).show();
                } else if (OnDeviceAvailability.needsDownload()) {
                    OnDeviceModelDialogs.showDownloadDialog(this);
                } else {
                    OnDeviceAvailability.refresh(() -> onDeviceUnavailable(service));
                }
                return;
            }
            if (!service.isSelected()) {
                AiConfig.setSelectedServices(service);
                if (listView.adapter != null) {
                    listView.adapter.update(true);
                }
            }
            return;
        }
        if (item.id == ID_NEW_SERVICE) {
            presentFragment(new EditServiceActivity());
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item == null || !(item.object instanceof Service)) {
            return false;
        }
        Service service = (Service) item.object;
        if (service.isOnDevice()) {
            showOnDeviceOptions(view);
            return true;
        }
        ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> presentFragment(new EditServiceActivity(service)))
                .add(R.drawable.msg_copy, LocaleController.getString(R.string.Copy), () -> {
                    if (AndroidUtilities.addToClipboard(service.getUrl() + "\n" + service.getModel() + "\n" + service.getKey())) {
                        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                    }
                })
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true, () -> confirmDeleteService(service))
                .setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)
                .setScrimViewBackground(listView.getClipBackground(view))
                .show();
        return true;
    }

    private void confirmDeleteService(Service service) {
        if (service == null || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.Delete));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteServiceInfo, service.getShortModel())));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> deleteService(service));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteService(Service service) {
        boolean wasSelected = service != null && service.isSelected();
        if (service == null || !AiController.getInstance().removeService(service)) {
            return;
        }
        if (wasSelected) {
            AiConfig.clearSelectedService();
            if (!AiController.getInstance().isServicesEmpty()) {
                AiConfig.setSelectedServices(AiController.getInstance().getAll().get(0));
            }
        }
        getNotificationCenter().postNotificationName(NotificationCenter.servicesUpdated);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
