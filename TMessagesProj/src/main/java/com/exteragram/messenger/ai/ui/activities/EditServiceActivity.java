package com.exteragram.messenger.ai.ui.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.WindowInsetsCompat;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.data.Suggestions;
import com.exteragram.messenger.ai.network.Client;
import com.exteragram.messenger.ai.network.GenerationCallback;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

public class EditServiceActivity extends BasePreferencesActivity {

    private static final int ID_PRESET_START = 100;
    private static final int ID_PASTE = 200;
    private static final int ID_SAVE = 201;
    private static final int ID_DELETE = 202;
    private static final int ID_REASONING = 203;

    private static final ServicePreset[] SERVICE_PRESETS = {
            new ServicePreset("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.5-flash"),
            new ServicePreset("OpenAI", "https://api.openai.com/v1", "gpt-5.4-mini"),
            new ServicePreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-5.4-mini"),
            new ServicePreset("Perplexity", "https://api.perplexity.ai/v1/agent", "perplexity/glm-5.3-flash"),
            new ServicePreset(null, null, null)
    };

    private final Service currentService;
    private final ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = this::updateClipboardState;
    private ClipboardManager clipboardManager;

    private EditTextCell urlCell;
    private EditTextCell modelCell;
    private EditTextCell keyCell;

    private String initialUrl;
    private String initialModel;
    private String initialKey;
    private boolean initialReasoningEnabled;
    private boolean reasoningEnabled;
    private boolean hasChanges;
    private int selectedPresetIndex;
    private boolean forceCustomPreset;
    private boolean updatingFields;

    private ParsedServiceInput pasteInput;
    private String pasteString;

    private boolean isTesting;
    private Client testingClient;
    private String testingRequestId;
    private AlertDialog testingProgressDialog;

    private int keyboardInset;
    private int shiftDp = -4;

    public EditServiceActivity() {
        this(null);
    }

    public EditServiceActivity(Service service) {
        currentService = service;
    }

    @Override
    public WindowInsetsCompat onInsetsInternal(View view, WindowInsetsCompat insets) {
        int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.statusBars()).bottom;
        keyboardInset = Math.max(0, ime - bars);
        return super.onInsetsInternal(view, insets);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom + keyboardInset);
        if (keyboardInset <= 0 || listView == null) {
            return;
        }
        listView.post(() -> {
            View focused = listView.findFocus();
            if (focused != null) {
                focused.requestRectangleOnScreen(new Rect(0, 0, focused.getWidth(), focused.getHeight()), false);
            }
        });
    }

    @Override
    public View createView(Context context) {
        clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        createFields(context);
        initializeState();
        updateClipboardState(context, false);
        View view = super.createView(context);
        actionBar.setAllowOverlayTitle(true);
        return view;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(currentService != null ? R.string.EditService : R.string.NewService);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ServiceProvider)));
        for (int i = 0; i < SERVICE_PRESETS.length; i++) {
            items.add(UItem.asRadio(ID_PRESET_START + i, getPresetTitle(i)).setChecked(i == selectedPresetIndex).setEnabled(!isTesting));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.ServicesInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ServiceInfo)));
        items.add(UItem.asCustom(urlCell));
        items.add(UItem.asCustom(modelCell));
        items.add(UItem.asCustom(keyCell));
        String reasoningTitle = LocaleController.getString(R.string.AIReasoning);
        items.add(UItem.asCheck(ID_REASONING, SettingsRegistry.markAsNewFeature("AI-Service-Reasoning") ? LocaleUtils.applyNewSpan(reasoningTitle) : reasoningTitle, LocaleController.getString(R.string.AIReasoningInfo), true)
                .setChecked(reasoningEnabled).setEnabled(!isTesting));
        items.add(UItem.asShadow());

        boolean hasButtons = false;
        if (pasteInput != null) {
            items.add(UItem.asButton(ID_PASTE, R.drawable.msg_copy, LocaleController.getString(pasteInput.hasServiceFields() ? R.string.ServicePasteService : R.string.ServicePasteKey))
                    .accent().setEnabled(!isTesting));
            hasButtons = true;
        }
        if (hasChanges) {
            items.add(createSaveItem());
            hasButtons = true;
        }
        if (currentService != null) {
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.Delete)).red().setEnabled(!isTesting));
            hasButtons = true;
        }
        if (hasButtons) {
            items.add(UItem.asShadow());
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (isTesting) {
            return;
        }
        int id = item.id;
        if (id >= ID_PRESET_START && id < ID_PRESET_START + SERVICE_PRESETS.length) {
            applyPreset(id - ID_PRESET_START);
        } else if (id == ID_PASTE) {
            ParsedServiceInput input = pasteInput != null ? pasteInput : parseServiceInput(pasteString);
            if (input != null) {
                applyParsedServiceInput(input);
            }
        } else if (id == ID_SAVE) {
            saveConfig();
        } else if (id == ID_DELETE) {
            confirmDeleteService();
        } else if (id == ID_REASONING) {
            toggleBooleanSettingAndRefresh(item, enabled -> {
                reasoningEnabled = enabled;
                updateFormState();
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
            clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        }
        updateClipboardState();
        if (!MessagesController.getGlobalMainSettings().getBoolean("view_animations", true) && keyCell != null) {
            keyCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(keyCell.editText);
        }
    }

    @Override
    public void onPause() {
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
        }
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        stopTestingRequest();
        hideTestingProgressDialog();
        testingRequestId = null;
        testingClient = null;
        super.onFragmentDestroy();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && keyCell != null) {
            keyCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(keyCell.editText);
        }
    }

    private void createFields(Context context) {
        urlCell = new EditTextCell(context, LocaleController.getString(R.string.ServiceURL), false, false, 128, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                updateFormState();
            }
        };
        int urlInputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        urlCell.editText.setInputType(urlInputType);
        urlCell.editText.setRawInputType(urlInputType);
        urlCell.editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NEXT);
        urlCell.editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            modelCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(modelCell.editText);
            return true;
        });

        modelCell = new EditTextCell(context, LocaleController.getString(R.string.ServiceModel), false, false, 64, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                updateFormState();
            }
        };
        int modelInputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        modelCell.editText.setInputType(modelInputType);
        modelCell.editText.setRawInputType(modelInputType);
        modelCell.editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NEXT);
        modelCell.editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            keyCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(keyCell.editText);
            return true;
        });

        keyCell = new EditTextCell(context, LocaleController.getString(R.string.ServiceKey), false, false, 256, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                updateFormState();
            }
        };
        int keyInputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        keyCell.editText.setInputType(keyInputType);
        keyCell.editText.setRawInputType(keyInputType);
        keyCell.editText.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        keyCell.editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            saveConfig();
            return true;
        });
    }

    private void initializeState() {
        Service source = currentService != null ? currentService : AiConfig.DEFAULT_SERVICE;
        initialUrl = source.getUrl();
        initialModel = source.getModel();
        initialKey = source.getKey();
        initialReasoningEnabled = source.isReasoningEnabled();
        reasoningEnabled = initialReasoningEnabled;
        urlCell.setText(safeString(initialUrl));
        modelCell.setText(safeString(initialModel));
        keyCell.setText(safeString(initialKey));
        hasChanges = false;
        selectedPresetIndex = findPresetIndex(initialUrl, initialModel);
        forceCustomPreset = selectedPresetIndex == getCustomPresetIndex();
    }

    private void updateFormState() {
        if (updatingFields || urlCell == null || modelCell == null || keyCell == null) {
            return;
        }
        boolean hadChanges = hasChanges;
        int oldPresetIndex = selectedPresetIndex;
        boolean hadPaste = pasteInput != null;
        boolean hadPasteService = pasteInput != null && pasteInput.hasServiceFields();

        hasChanges = !TextUtils.equals(getFieldText(urlCell), safeString(initialUrl))
                || !TextUtils.equals(getFieldText(modelCell), safeString(initialModel))
                || !TextUtils.equals(getFieldText(keyCell), safeString(initialKey))
                || reasoningEnabled != initialReasoningEnabled;
        selectedPresetIndex = forceCustomPreset ? getCustomPresetIndex() : findPresetIndex(getFieldText(urlCell), getFieldText(modelCell));
        updateClipboardState(false);

        boolean hasPaste = pasteInput != null;
        boolean hasPasteService = pasteInput != null && pasteInput.hasServiceFields();
        if (listView == null || listView.adapter == null) {
            return;
        }
        if (hadChanges != hasChanges || oldPresetIndex != selectedPresetIndex || hadPaste != hasPaste || hadPasteService != hasPasteService) {
            listView.adapter.update(true);
        }
    }

    private void setTesting(boolean testing) {
        isTesting = testing;
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private UItem createSaveItem() {
        return UItem.asButton(ID_SAVE, R.drawable.ic_ab_done, LocaleController.getString(R.string.ServiceTestAndSave)).accent().setEnabled(!isTesting);
    }

    private void applyPreset(int index) {
        ServicePreset preset = SERVICE_PRESETS[index];
        updatingFields = true;
        if (preset.url == null || preset.model == null) {
            forceCustomPreset = true;
            urlCell.setText("");
            modelCell.setText("");
            updatingFields = false;
            updateFormState();
            urlCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(urlCell.editText);
            return;
        }
        forceCustomPreset = false;
        urlCell.setText(preset.url);
        modelCell.setText(preset.model);
        updatingFields = false;
        updateFormState();
        keyCell.editText.requestFocus();
        AndroidUtilities.showKeyboard(keyCell.editText);
    }

    private String getPresetTitle(int index) {
        ServicePreset preset = SERVICE_PRESETS[index];
        return preset.name != null ? preset.name : LocaleController.getString(R.string.ServiceProviderCustom);
    }

    private int findPresetIndex(String url, String model) {
        for (int i = 0; i < SERVICE_PRESETS.length - 1; i++) {
            ServicePreset preset = SERVICE_PRESETS[i];
            if (TextUtils.equals(url, preset.url) && TextUtils.equals(model, preset.model)) {
                return i;
            }
        }
        return getCustomPresetIndex();
    }

    private int getCustomPresetIndex() {
        return SERVICE_PRESETS.length - 1;
    }

    private void saveConfig() {
        if (isTesting || !validateFields()) {
            return;
        }
        Service service = new Service(getFieldText(urlCell), getFieldText(modelCell), getFieldText(keyCell), reasoningEnabled);
        Service existing = findExistingService(service);
        boolean reasoningChanged = currentService != null && currentService.isReasoningEnabled() != reasoningEnabled;
        if (existing != null && !Objects.equals(existing, currentService)) {
            if (currentService != null) {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ServiceDuplicate)).show();
            } else {
                useExistingService(existing);
            }
            return;
        }
        if (existing != null && !reasoningChanged) {
            applySelection(existing);
        } else {
            testAndSave(service);
        }
    }

    private void testAndSave(Service service) {
        testingClient = new Client.Builder()
                .serviceOverride(service)
                .roleOverride(Suggestions.ASSISTANT.getRole())
                .build();
        showTestingProgressDialog();
        setTesting(true);
        testingRequestId = testingClient.getResponse("Say 'hi'.", new GenerationCallback() {
            @Override
            public void onChunk(String chunk) {
            }

            @Override
            public void onResponse(String response) {
                AndroidUtilities.runOnUIThread(() -> {
                    clearTestingState();
                    if (currentService != null) {
                        AiController.getInstance().updateService(currentService, service);
                    } else {
                        AiController.getInstance().addService(service);
                    }
                    applySelection(service);
                });
            }

            @Override
            public void onError(int code, String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    clearTestingState();
                    AiController.showErrorBulletin(EditServiceActivity.this, code, message);
                    showFieldError(getFieldForError(code, message));
                });
            }
        });
    }

    private void useExistingService(Service service) {
        if (service.isReasoningEnabled() != reasoningEnabled) {
            service.setReasoningEnabled(reasoningEnabled);
            AiController.getInstance().saveServices();
        }
        applySelection(service);
        BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.ServiceAlreadyExists), LocaleController.getString(R.string.ServiceAlreadyExistsInfo)).show();
    }

    private void applySelection(Service service) {
        AiConfig.setSelectedServices(service);
        getNotificationCenter().postNotificationName(NotificationCenter.servicesUpdated);
        finishFragment();
    }

    private Service findExistingService(Service service) {
        for (Service existing : AiController.getInstance().getAll()) {
            if (existing.equals(service)) {
                return existing;
            }
        }
        return null;
    }

    private boolean validateFields() {
        if (!isValidServiceUrl(getFieldText(urlCell))) {
            showFieldError(urlCell);
            return false;
        }
        if (TextUtils.isEmpty(getFieldText(modelCell))) {
            showFieldError(modelCell);
            return false;
        }
        if (TextUtils.isEmpty(getFieldText(keyCell))) {
            showFieldError(keyCell);
            return false;
        }
        return true;
    }

    private boolean isValidServiceUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && !TextUtils.isEmpty(uri.getHost());
    }

    private EditTextCell getFieldForError(int code, String message) {
        switch (code) {
            case 400:
                return modelCell;
            case 401:
            case 403:
                return keyCell;
            case 404:
                return message != null && message.toLowerCase(Locale.ROOT).contains("model") ? modelCell : urlCell;
            default:
                return null;
        }
    }

    private void showFieldError(EditTextCell cell) {
        if (cell == null) {
            return;
        }
        BotWebViewVibrationEffect.APP_ERROR.vibrate();
        shiftDp = -shiftDp;
        AndroidUtilities.shakeViewSpring(cell, shiftDp);
        cell.editText.requestFocus();
        AndroidUtilities.showKeyboard(cell.editText);
    }

    private void applyParsedServiceInput(ParsedServiceInput input) {
        updatingFields = true;
        if (!TextUtils.isEmpty(input.url)) {
            urlCell.setText(input.url);
        }
        if (!TextUtils.isEmpty(input.model)) {
            modelCell.setText(input.model);
        }
        if (!TextUtils.isEmpty(input.key)) {
            keyCell.setText(input.key);
        }
        forceCustomPreset = false;
        updatingFields = false;
        updateFormState();
    }

    private void showTestingProgressDialog() {
        if (getParentActivity() == null) {
            return;
        }
        testingProgressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        testingProgressDialog.setOnCancelListener(dialog -> cancelTesting());
        showDialog(testingProgressDialog);
    }

    private void cancelTesting() {
        stopTestingRequest();
        clearTestingState();
    }

    private void stopTestingRequest() {
        if (testingClient != null && !TextUtils.isEmpty(testingRequestId)) {
            testingClient.stopRequest(testingRequestId);
        }
    }

    private void hideTestingProgressDialog() {
        if (testingProgressDialog == null) {
            return;
        }
        try {
            testingProgressDialog.dismiss();
        } catch (Exception ignore) {
        }
        testingProgressDialog = null;
    }

    private void clearTestingState() {
        hideTestingProgressDialog();
        setTesting(false);
        testingRequestId = null;
        testingClient = null;
    }

    private ParsedServiceInput parseServiceInput(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (TextUtils.isEmpty(trimmed)) {
            return null;
        }
        ArrayList<String> lines = new ArrayList<>();
        for (String line : trimmed.split("\\r?\\n")) {
            line = line.trim();
            if (!TextUtils.isEmpty(line)) {
                lines.add(line);
            }
        }
        for (int i = 0; i <= lines.size() - 3; i++) {
            if (isLikelyService(lines.get(i), lines.get(i + 1), lines.get(i + 2))) {
                return new ParsedServiceInput(lines.get(i), lines.get(i + 1), lines.get(i + 2));
            }
        }
        if (lines.size() == 1 && isLikelyApiKey(trimmed)) {
            return new ParsedServiceInput(null, null, trimmed);
        }
        return null;
    }

    private boolean isLikelyService(String url, String model, String key) {
        return isLikelyServiceUrl(url) && isLikelyModel(model) && isLikelyApiKey(key);
    }

    private boolean isLikelyServiceUrl(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private boolean isLikelyModel(String model) {
        return !TextUtils.isEmpty(model) && model.length() <= 128 && !model.matches(".*\\s+.*");
    }

    private boolean isLikelyApiKey(String key) {
        return !TextUtils.isEmpty(key) && key.length() >= 20 && key.length() <= 512 && key.matches("[A-Za-z0-9_.\\-]+");
    }

    private void updateClipboardState() {
        updateClipboardState(true);
    }

    private void updateClipboardState(boolean notify) {
        updateClipboardState(fragmentView != null ? fragmentView.getContext() : getContext(), notify);
    }

    private void updateClipboardState(Context context, boolean notify) {
        if (clipboardManager == null || context == null || urlCell == null || modelCell == null || keyCell == null) {
            return;
        }
        boolean hadPaste = pasteInput != null;
        boolean hadPasteService = pasteInput != null && pasteInput.hasServiceFields();
        String clipboardText = readClipboardText(context);
        ParsedServiceInput input = parseServiceInput(clipboardText);
        if (input != null && input.differsFrom(getFieldText(urlCell), getFieldText(modelCell), getFieldText(keyCell))) {
            pasteInput = input;
            pasteString = clipboardText;
        } else {
            pasteInput = null;
            pasteString = null;
        }
        boolean hasPaste = pasteInput != null;
        boolean hasPasteService = pasteInput != null && pasteInput.hasServiceFields();
        if (!notify || listView == null || listView.adapter == null) {
            return;
        }
        if (hadPaste != hasPaste || hadPasteService != hasPasteService) {
            listView.adapter.update(true);
        }
    }

    private String readClipboardText(Context context) {
        ClipData clip = clipboardManager.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            try {
                CharSequence text = clip.getItemAt(0).coerceToText(context);
                if (text != null) {
                    return text.toString();
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private void confirmDeleteService() {
        if (currentService == null || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.Delete));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.DeleteServiceInfo, currentService.getShortModel())));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> deleteCurrentService());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void deleteCurrentService() {
        boolean wasSelected = currentService != null && currentService.isSelected();
        if (currentService == null || !AiController.getInstance().removeService(currentService)) {
            return;
        }
        if (wasSelected) {
            AiConfig.clearSelectedService();
            if (!AiController.getInstance().isServicesEmpty()) {
                AiConfig.setSelectedServices(AiController.getInstance().getAll().get(0));
            }
        }
        getNotificationCenter().postNotificationName(NotificationCenter.servicesUpdated);
        finishFragment();
    }

    private String getFieldText(EditTextCell cell) {
        return cell == null || cell.getText() == null ? "" : cell.getText().toString().trim();
    }

    private String safeString(String value) {
        return value != null ? value : "";
    }

    public static final class ServicePreset {

        private final String name;
        private final String url;
        private final String model;

        private ServicePreset(String name, String url, String model) {
            this.name = name;
            this.url = url;
            this.model = model;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ServicePreset)) {
                return false;
            }
            ServicePreset that = (ServicePreset) o;
            return Objects.equals(name, that.name) && Objects.equals(url, that.url) && Objects.equals(model, that.model);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, url, model);
        }

        @NonNull
        @Override
        public String toString() {
            return "ServicePreset[name=" + name + ", url=" + url + ", model=" + model + "]";
        }
    }

    public static final class ParsedServiceInput {

        private final String url;
        private final String model;
        private final String key;

        private ParsedServiceInput(String url, String model, String key) {
            this.url = url;
            this.model = model;
            this.key = key;
        }

        public boolean hasServiceFields() {
            return !TextUtils.isEmpty(url) || !TextUtils.isEmpty(model);
        }

        public boolean differsFrom(String currentUrl, String currentModel, String currentKey) {
            if (!TextUtils.isEmpty(url) && !TextUtils.equals(url, currentUrl)) {
                return true;
            }
            if (!TextUtils.isEmpty(model) && !TextUtils.equals(model, currentModel)) {
                return true;
            }
            return !TextUtils.isEmpty(key) && !TextUtils.equals(key, currentKey);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ParsedServiceInput)) {
                return false;
            }
            ParsedServiceInput that = (ParsedServiceInput) o;
            return Objects.equals(url, that.url) && Objects.equals(model, that.model) && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, model, key);
        }

        @NonNull
        @Override
        public String toString() {
            return "ParsedServiceInput[url=" + url + ", model=" + model + "]";
        }
    }
}
