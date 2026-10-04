package com.exteragram.messenger.ai;

import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;
import com.exteragram.messenger.ai.network.backend.OnDeviceAvailability;
import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class AiController {

    public static final int ERROR_ON_DEVICE_UNAVAILABLE = -1;
    public static final int ERROR_ON_DEVICE_NOT_DOWNLOADED = -2;
    public static final int ERROR_ON_DEVICE_DOWNLOADING = -3;
    public static final int ERROR_ON_DEVICE_TOO_LONG = -4;
    public static final int ERROR_ON_DEVICE_STOPPED = -5;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 160;

    private final List<Role> roles = new ArrayList<>();
    private final List<Service> services = new ArrayList<>();

    private static class SingletonHolder {
        private static final AiController INSTANCE = new AiController();
    }

    public static AiController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public AiController() {
        loadRoles();
        loadServices();
    }

    public static boolean isOnDeviceError(int code) {
        return code <= ERROR_ON_DEVICE_UNAVAILABLE && code >= ERROR_ON_DEVICE_STOPPED;
    }

    public static void clearHistory(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider, boolean ask) {
        clearHistory(fragment, resourcesProvider, ask, null);
    }

    public static void clearHistory(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider, boolean ask, Runnable onCleared) {
        if (fragment == null) {
            return;
        }
        if (ask) {
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
            builder.setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.ClearConversationHistoryInfo)));
            builder.setTitle(LocaleController.getString(R.string.ClearHistory));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.setPositiveButton(LocaleController.getString(R.string.ClearButton), (dialog, which) -> doClearHistory(fragment, onCleared));
            AlertDialog dialog = builder.create();
            fragment.showDialog(dialog);
            TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button != null) {
                button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
            return;
        }
        doClearHistory(fragment, onCleared);
    }

    private static void doClearHistory(BaseFragment fragment, Runnable onCleared) {
        AiConfig.clearConversationHistory();
        if (onCleared != null) {
            onCleared.run();
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.HistoryCleared)).show();
    }

    public static void showErrorBulletin(ViewGroup container, Theme.ResourcesProvider resourcesProvider, int code, String message, Runnable onChangeModel) {
        showErrorBulletin(null, container, resourcesProvider, code, message, onChangeModel);
    }

    public static void showErrorBulletin(BaseFragment fragment, int code, String message) {
        showErrorBulletin(fragment, null, null, code, message, null);
    }

    private static void showErrorBulletin(BaseFragment fragment, ViewGroup container, Theme.ResourcesProvider resourcesProvider, int code, String message, Runnable onChangeModel) {
        int title;
        int info;
        int sticker = 3;
        boolean showRawMessage = false;
        switch (code) {
            case ERROR_ON_DEVICE_STOPPED:
                title = R.string.AIErrorOnDeviceStopped;
                info = R.string.AIErrorOnDeviceStoppedInfo;
                break;
            case ERROR_ON_DEVICE_TOO_LONG:
                title = R.string.AIErrorOnDeviceTooLong;
                info = R.string.AIErrorOnDeviceTooLongInfo;
                sticker = 2;
                break;
            case ERROR_ON_DEVICE_DOWNLOADING:
                title = R.string.AIErrorOnDeviceDownloading;
                info = R.string.AIErrorOnDeviceDownloadingInfo;
                sticker = 5;
                break;
            case ERROR_ON_DEVICE_NOT_DOWNLOADED:
                title = R.string.AIErrorOnDeviceDownload;
                info = R.string.AIErrorOnDeviceDownloadInfo;
                sticker = 8;
                break;
            case ERROR_ON_DEVICE_UNAVAILABLE:
                title = R.string.AIErrorOnDevice;
                info = R.string.AIErrorOnDeviceInfo;
                break;
            case 204:
                title = R.string.AIErrorEmpty;
                info = R.string.AIErrorEmptyInfo;
                sticker = 8;
                break;
            case 400:
                title = R.string.AIError400;
                info = R.string.AIError400Info;
                sticker = 2;
                break;
            case 401:
                title = R.string.AIError401;
                info = R.string.AIError401Info;
                break;
            case 402:
                title = R.string.AIError402;
                info = R.string.AIError402Info;
                sticker = 2;
                break;
            case 403:
                title = R.string.AIError403;
                info = R.string.AIError403Info;
                sticker = 9;
                break;
            case 408:
                title = R.string.AIError408;
                info = R.string.AIError408Info;
                sticker = 2;
                break;
            case 429:
                title = R.string.AIError429;
                info = R.string.AIError429Info;
                sticker = 6;
                break;
            case 502:
                title = R.string.AIError502;
                info = R.string.AIError502Info;
                break;
            case 503:
                title = R.string.AIError503;
                info = R.string.AIError503Info;
                break;
            default:
                title = R.string.AIError;
                info = R.string.AIErrorInfo;
                showRawMessage = true;
                break;
        }

        String subtitle = showRawMessage || code == 400 || code == 403 ? trimErrorMessage(message) : null;
        if (showRawMessage && subtitle != null) {
            title = R.string.UnknownError;
        }
        if (subtitle == null) {
            subtitle = LocaleController.getString(info);
        }

        BulletinFactory factory;
        if (container != null) {
            factory = BulletinFactory.of((FrameLayout) container, resourcesProvider);
        } else if (fragment != null) {
            factory = BulletinFactory.of(fragment);
        } else {
            factory = BulletinFactory.global();
        }
        Context context = null;
        if (container != null) {
            context = container.getContext();
        } else if (fragment != null) {
            context = fragment.getContext();
        }

        final int finalTitle = title;
        final int finalSticker = sticker;
        final CharSequence finalSubtitle = subtitle;
        final Context finalContext = context;
        AndroidUtilities.runOnUIThread(() -> {
            if (onChangeModel == null || finalContext == null || !isOnDeviceError(code)) {
                factory.createSimpleBulletin(LocaleController.getString(finalTitle), finalSubtitle, finalSticker).show();
                return;
            }
            Bulletin.TwoLineLottieLayout layout = new Bulletin.TwoLineLottieLayout(finalContext, resourcesProvider);
            layout.setSticker(finalSticker);
            layout.titleTextView.setText(LocaleController.getString(finalTitle));
            layout.subtitleTextView.setText(finalSubtitle);
            layout.setButton(new Bulletin.UndoButton(finalContext, true, resourcesProvider)
                    .setText(LocaleController.getString(R.string.AIChangeModel))
                    .setUndoAction(onChangeModel));
            factory.create(layout, Bulletin.DURATION_PROLONG).show();
        });
    }

    private static String trimErrorMessage(String message) {
        if (TextUtils.isEmpty(message)) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_MESSAGE_LENGTH).trim() + "…";
    }

    public static boolean canUseAI() {
        Service selected = getInstance().getSelected();
        if (selected.isOnDevice()) {
            return OnDeviceAvailability.isReady();
        }
        return !TextUtils.isEmpty(selected.getKey());
    }

    public void loadRoles() {
        roles.clear();
        roles.addAll(AiConfig.getRoles());
        roles.removeIf(role -> role == null || role.getName() == null || role.getPrompt() == null);
    }

    public List<Role> getRoles() {
        return Collections.unmodifiableList(roles);
    }

    public List<Role> getSuggestedRoles() {
        return Arrays.stream(Suggestions.values()).map(Suggestions::getRole).collect(Collectors.toList());
    }

    public boolean isCustomRole(Role role) {
        return role != null && roles.contains(role);
    }

    public boolean isSuggestedRole(Role role) {
        return role != null && getSuggestedRoles().contains(role);
    }

    public Role getSelectedRole() {
        for (Role role : roles) {
            if (role.isSelected()) {
                return role;
            }
        }
        for (Role role : getSuggestedRoles()) {
            if (role.isSelected()) {
                return role;
            }
        }
        return Suggestions.ASSISTANT.getRole();
    }

    public boolean addRole(Role role) {
        if (isSuggestedRole(role) || isCustomRole(role)) {
            return false;
        }
        roles.add(0, role);
        saveRoles();
        return true;
    }

    public boolean removeRole(Role role) {
        if (role == null) {
            return false;
        }
        boolean removed = roles.remove(role);
        if (removed) {
            saveRoles();
        }
        return removed;
    }

    public boolean updateRole(Role oldRole, Role newRole) {
        int index = roles.indexOf(oldRole);
        if (index == -1) {
            return false;
        }
        if (isSuggestedRole(newRole) && !oldRole.equals(newRole)) {
            return false;
        }
        roles.set(index, newRole);
        saveRoles();
        return true;
    }

    public void saveRoles() {
        AiConfig.saveRoles(new ArrayList<>(roles));
    }

    public void loadServices() {
        services.clear();
        services.addAll(AiConfig.getServices());
        services.removeIf(Service::isOnDevice);
        if (OnDeviceAvailability.isSupported()) {
            String modelName = OnDeviceAvailability.getModelName();
            Service onDevice = AiConfig.ON_DEVICE_SERVICE;
            onDevice.setModel(TextUtils.isEmpty(modelName) ? "Gemini Nano" : modelName);
            onDevice.setReasoningEnabled(OnDeviceAvailability.isThinkingSupported() && AiConfig.getOnDeviceReasoning());
            services.add(onDevice);
        }
        if (!OnDeviceAvailability.isReady() && AiConfig.ON_DEVICE_SERVICE.getId().equals(AiConfig.getSelectedServiceId())) {
            AiConfig.setSelectedServiceId(null);
        }
    }

    public List<Service> getAll() {
        return Collections.unmodifiableList(services);
    }

    public boolean isServicesEmpty() {
        return services.isEmpty();
    }

    public void addService(Service service) {
        if (service == null || service.isOnDevice() || services.contains(service)) {
            return;
        }
        services.add(service);
        saveServices();
    }

    public void updateService(Service oldService, Service newService) {
        if (oldService == null || oldService.isOnDevice() || newService == null || newService.isOnDevice()) {
            return;
        }
        int index = services.indexOf(oldService);
        if (index == -1) {
            return;
        }
        newService.setId(oldService.getId());
        services.set(index, newService);
        saveServices();
    }

    public boolean removeService(Service service) {
        if (service == null || service.isOnDevice()) {
            return false;
        }
        boolean removed = services.remove(service);
        if (removed) {
            saveServices();
        }
        return removed;
    }

    public Service getSelected() {
        for (Service service : services) {
            if (service.isSelected()) {
                return service;
            }
        }
        for (Service service : services) {
            if (!service.isOnDevice()) {
                return service;
            }
        }
        return AiConfig.DEFAULT_SERVICE;
    }

    public void saveServices() {
        services.sort(Comparator.comparing(Service::getModel, Comparator.nullsLast(Comparator.naturalOrder())));
        ArrayList<Service> toSave = new ArrayList<>();
        for (Service service : services) {
            if (!service.isOnDevice()) {
                toSave.add(service);
            }
        }
        AiConfig.saveServices(toSave);
    }

    public static boolean canSendImage(MessageObject messageObject) {
        return messageObject != null && canSendImage(ChatUtils.getInstance().getPathToMessage(messageObject));
    }

    public static boolean canSendImage(String path) {
        if (path == null) {
            return false;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".heif");
    }
}
