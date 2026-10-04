package com.exteragram.messenger.preferences;

import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.translator.TranslationProviders;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;
import com.exteragram.messenger.utils.text.ZalgoFilter;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.PremiumPreviewFragment;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GeneralPreferencesActivity extends BasePreferencesActivity {

    private static final Pattern SAVE_PATH_PATTERN = Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9._ -]{1,255}$");

    private Integer fiveMinutesAgo;
    private CharSequence[] idOptions;
    private CharSequence[] translationProviders;

    public enum GeneralItem {
        SHOW_TRANSLATE_BUTTON,
        SHOW_TRANSLATE_CHAT_BUTTON,
        TRANSLATION_PROVIDERS,
        TRANSLATION_TARGET_LANGUAGE,
        DO_NOT_TRANSLATE_LANGUAGES,
        DISABLE_NUMBER_ROUNDING,
        FORMAT_TIME_WITH_SECONDS,
        RELATIVE_LAST_SEEN,
        IN_APP_VIBRATION,
        FILTER_ZALGO,
        YANDEX_MAPS,
        DOWNLOAD_SPEED_BOOST,
        UPLOAD_SPEED_BOOST,
        CUSTOM_SAVE_PATH,
        HIDE_PHONE_NUMBER,
        SHOW_ID_AND_DC,
        HIDE_ARCHIVE_FOLDER,
        ARCHIVE_ON_PULL,
        DISABLE_UNARCHIVE_SWIPE,
        DISABLE_NOTIFICATION_DELAY;

        public int getId() {
            return ordinal() + 1;
        }
    }

    @Override
    public void initializeOptionStrings() {
        idOptions = new CharSequence[]{
                LocaleController.getString(R.string.Hide),
                "Telegram API",
                "Bot API"
        };
        translationProviders = TranslationProviders.names();
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.General);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.TranslateMessages)));
        items.add(UItem.asCheck(GeneralItem.SHOW_TRANSLATE_BUTTON.getId(), LocaleController.getString(R.string.ShowTranslateButton)).setChecked(getContextTranslateValue()).setSearchable(this).setLinkAlias("showTranslateButton", this));
        items.add(UItem.asCheck(GeneralItem.SHOW_TRANSLATE_CHAT_BUTTON.getId(), LocaleController.getString(R.string.ShowTranslateChatButton)).setCheckBoxIcon(isChatTranslateLocked() ? R.drawable.permission_locked : 0).setChecked(getChatTranslateValue()).setSearchable(this).setLinkAlias("showTranslateChatButton", this));
        items.add(UItem.asButton(GeneralItem.TRANSLATION_PROVIDERS.getId(), LocaleController.getString(R.string.TranslationProvider), translationProviders[ExteraConfig.getTranslationProvider()]).setSearchable(this).setLinkAlias("translationProvider", this));
        items.add(UItem.asButton(GeneralItem.TRANSLATION_TARGET_LANGUAGE.getId(), LocaleController.getString(R.string.TranslationTarget), ExteraConfig.getCurrentLangName()).setSearchable(this).setLinkAlias("translationTargetLanguage", this));
        items.add(UItem.asButton(GeneralItem.DO_NOT_TRANSLATE_LANGUAGES.getId(), LocaleController.getString(R.string.DoNotTranslate), getDoNotTranslateValue()).setSearchable(this).setLinkAlias("doNotTranslateLanguages", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.TranslateMessagesInfo1)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        items.add(UItem.asCheck(GeneralItem.DISABLE_NUMBER_ROUNDING.getId(), LocaleController.getString(R.string.DisableNumberRounding), "1.23K -> 1,234", false).setChecked(ExteraConfig.getDisableNumberRounding()).setSearchable(this).setLinkAlias("disableNumberRounding", this));
        items.add(UItem.asCheck(GeneralItem.FORMAT_TIME_WITH_SECONDS.getId(), LocaleController.getString(R.string.FormatTimeWithSeconds), "12:34 -> 12:34:56", false).setChecked(ExteraConfig.getFormatTimeWithSeconds()).setSearchable(this).setLinkAlias("formatTimeWithSeconds", this));
        items.add(UItem.asCheck(GeneralItem.IN_APP_VIBRATION.getId(), LocaleController.getString(R.string.InAppVibration)).setChecked(ExteraConfig.getInAppVibration()).setSearchable(this).setLinkAlias("inAppVibration", this));
        items.add(UItem.asCheck(GeneralItem.DISABLE_NOTIFICATION_DELAY.getId(), LocaleController.getString(R.string.DisableNotificationDelay)).setChecked(ExteraConfig.getDisableNotificationDelay()).setSearchable(this).setLinkAlias("disableNotificationDelay", this));
        items.add(UItem.asCheck(GeneralItem.FILTER_ZALGO.getId(), LocaleController.getString(R.string.FilterZalgo)).setChecked(ExteraConfig.getFilterZalgo()).setSearchable(this).setLinkAlias("filterZalgo", this));
        items.add(UItem.asShadow(LocaleController.formatString(R.string.FilterZalgoInfo, ZalgoFilter.filter("Z\u0337\u034c\u034da\u0338\u0304\u031cl\u0338\u0302\u031eg\u0337\u034d\u031do\u0336\u0313\u0329"))));

        if (ApplicationLoader.applicationLoaderInstance.allowToUseYandexMaps()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.Maps)));
            items.add(UItem.asCheck(GeneralItem.YANDEX_MAPS.getId(), LocaleController.getString(R.string.UseYandexMaps)).setChecked(ExteraConfig.getUseYandexMaps()).setSearchable(this).setLinkAlias("useYandexMaps", this));
            items.add(UItem.asShadow(LocaleUtils.formatWithHtmlURLs(LocaleUtils.fromHtml(LocaleController.getString(R.string.TermsOfUseYandexMaps)))));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.DownloadSpeedBoost)));
        items.add(UItem.asSlideView(GeneralItem.DOWNLOAD_SPEED_BOOST.getId(), new String[]{
                LocaleController.getString(R.string.BlurOff),
                LocaleController.getString(R.string.SpeedFast),
                LocaleController.getString(R.string.Ultra)
        }, ExteraConfig.getDownloadSpeedBoost(), ExteraConfig::setDownloadSpeedBoost).setLinkAlias("downloadSpeedBoost", this));
        items.add(UItem.asCheck(GeneralItem.UPLOAD_SPEED_BOOST.getId(), LocaleController.getString(R.string.UploadSpeedBoost)).setChecked(ExteraConfig.getUploadSpeedBoost()).setSearchable(this).setLinkAlias("uploadSpeedBoost", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.SpeedBoostInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.StorageSettings)));
        items.add(UItem.asButton(GeneralItem.CUSTOM_SAVE_PATH.getId(), LocaleController.getString(R.string.CustomSavePath), getCustomSavePathDisplayValue()).setSearchable(this).setLinkAlias("customSavePath", this));
        items.add(UItem.asShadow(getCustomSavePathInfo()));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Profile)));
        items.add(UItem.asCheck(GeneralItem.RELATIVE_LAST_SEEN.getId(), LocaleController.getString(R.string.RelativeLastSeen), LocaleController.formatDateOnline(getFiveMinutesAgo(), null, new boolean[1]), false).setChecked(ExteraConfig.getRelativeLastSeen()).setSearchable(this).setLinkAlias("relativeLastSeen", this));
        items.add(UItem.asCheck(GeneralItem.HIDE_PHONE_NUMBER.getId(), LocaleController.getString(R.string.HidePhoneNumber)).setChecked(ExteraConfig.getHidePhoneNumber()).setSearchable(this).setLinkAlias("hidePhoneNumber", this));
        items.add(UItem.asButton(GeneralItem.SHOW_ID_AND_DC.getId(), LocaleController.getString(R.string.ShowIdAndDc), idOptions[ExteraConfig.getShowIdAndDc()]).setSearchable(this).setLinkAlias("showIdAndDc", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ShowIdAndDcInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ArchivedChats)));
        items.add(UItem.asCheck(GeneralItem.HIDE_ARCHIVE_FOLDER.getId(), LocaleController.getString(R.string.HideArchiveFolder)).setChecked(ExteraConfig.getHideArchiveFolder()).setSearchable(this).setLinkAlias("hideArchiveFolder", this));
        if (!ExteraConfig.getHideArchiveFolder()) {
            items.add(UItem.asCheck(GeneralItem.ARCHIVE_ON_PULL.getId(), LocaleController.getString(R.string.ArchiveOnPull)).setChecked(ExteraConfig.getArchiveOnPull()).setSearchable(this).setLinkAlias("archiveOnPull", this));
        }
        items.add(UItem.asCheck(GeneralItem.DISABLE_UNARCHIVE_SWIPE.getId(), LocaleController.getString(R.string.DisableUnarchiveSwipe)).setChecked(ExteraConfig.getDisableUnarchiveSwipe()).setSearchable(this).setLinkAlias("disableUnarchiveSwipe", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.DisableUnarchiveSwipeInfo)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id <= 0 || item.id > GeneralItem.values().length) {
            return;
        }
        switch (GeneralItem.values()[item.id - 1]) {
            case SHOW_TRANSLATE_BUTTON:
                toggleBooleanSettingAndRefresh(item, value -> getMessagesController().getTranslateController().setContextTranslateEnabled(value));
                handleContextTranslateClick();
                break;
            case SHOW_TRANSLATE_CHAT_BUTTON:
                handleChatTranslateClick(item);
                break;
            case TRANSLATION_PROVIDERS:
                showListDialog(item, translationProviders, LocaleController.getString(R.string.TranslationProvider), ExteraConfig.getTranslationProvider(), which -> {
                    ExteraConfig.setTranslationProvider(which);
                    TranslatorUtils.ensureTargetLanguageCompatibleWithProvider();
                    parentLayout.rebuildFragments(0);
                });
                break;
            case TRANSLATION_TARGET_LANGUAGE:
                presentFragment(new RestrictedLanguagesSelectActivity(1));
                break;
            case DO_NOT_TRANSLATE_LANGUAGES:
                presentFragment(new RestrictedLanguagesSelectActivity());
                break;
            case DISABLE_NUMBER_ROUNDING:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setDisableNumberRounding);
                break;
            case FORMAT_TIME_WITH_SECONDS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setFormatTimeWithSeconds);
                handleFormatTimeWithSecondsClick();
                break;
            case RELATIVE_LAST_SEEN:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setRelativeLastSeen);
                break;
            case IN_APP_VIBRATION:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setInAppVibration);
                break;
            case DISABLE_NOTIFICATION_DELAY:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setDisableNotificationDelay);
                break;
            case FILTER_ZALGO:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setFilterZalgo);
                break;
            case YANDEX_MAPS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setUseYandexMaps);
                ApplicationLoader.updateMapsProvider();
                break;
            case UPLOAD_SPEED_BOOST:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setUploadSpeedBoost);
                break;
            case CUSTOM_SAVE_PATH:
                showCustomSavePathDialog();
                break;
            case HIDE_PHONE_NUMBER:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHidePhoneNumber);
                handleHidePhoneNumberClick();
                break;
            case SHOW_ID_AND_DC:
                showListDialog(item, idOptions, LocaleController.getString(R.string.ShowIdAndDc), ExteraConfig.getShowIdAndDc(), which -> {
                    ExteraConfig.setShowIdAndDc(which);
                    parentLayout.rebuildFragments(0);
                });
                break;
            case HIDE_ARCHIVE_FOLDER:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideArchiveFolder);
                MessagesController.getInstance(currentAccount).checkArchiveFolder();
                break;
            case ARCHIVE_ON_PULL:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setArchiveOnPull);
                break;
            case DISABLE_UNARCHIVE_SWIPE:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setDisableUnarchiveSwipe);
                break;
        }
    }

    private int getFiveMinutesAgo() {
        if (fiveMinutesAgo == null) {
            fiveMinutesAgo = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - 300;
        }
        return fiveMinutesAgo;
    }

    private CharSequence getDoNotTranslateValue() {
        boolean[] accusative = new boolean[1];
        return RestrictedLanguagesSelectActivity.getRestrictedLanguages().stream()
                .map(lang -> TranslateAlert2.capitalFirst(TranslateAlert2.languageName(lang, accusative)))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    private boolean getContextTranslateValue() {
        return getMessagesController().getTranslateController().isContextTranslateEnabled();
    }

    private void handleContextTranslateClick() {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateSearchSettings);
        parentLayout.rebuildFragments(0);
    }

    private boolean getChatTranslateValue() {
        return getMessagesController().getTranslateController().isChatTranslateEnabled();
    }

    private boolean isChatTranslateLocked() {
        return !getUserConfig().isPremium() && !TranslatorUtils.isAlternativeProvider();
    }

    private void handleChatTranslateClick(UItem item) {
        if (!item.checked && isChatTranslateLocked()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.msg_translate, LocaleController.getString(R.string.ShowTranslateChatButtonLocked), LocaleController.getString(R.string.MoreInfo),
                    () -> showDialog(new PremiumFeatureBottomSheet(this, PremiumPreviewFragment.PREMIUM_FEATURE_TRANSLATIONS, false))).show();
            return;
        }
        toggleBooleanSettingAndRefresh(item, value -> getMessagesController().getTranslateController().setChatTranslateEnabled(value));
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateSearchSettings);
        parentLayout.rebuildFragments(0);
    }

    private void handleFormatTimeWithSecondsClick() {
        LocaleController.getInstance().recreateFormatters();
        parentLayout.rebuildFragments(0);
    }

    private void handleHidePhoneNumberClick() {
        getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        parentLayout.rebuildFragments(0);
    }

    private String getCustomSavePathDisplayValue() {
        String path = ExteraConfig.getCustomSavePath();
        return TextUtils.isEmpty(path) ? LocaleController.getString(R.string.CustomSavePathDefault) : path;
    }

    private String getCustomSavePathInfo() {
        String path = ExteraConfig.getCustomSavePath();
        if (TextUtils.isEmpty(path)) {
            return LocaleController.getString(R.string.CustomSavePathInfo);
        }
        return LocaleController.formatString(R.string.CustomSavePathInfoFolder, path);
    }

    private void showCustomSavePathDialog() {
        if (getContext() == null) {
            return;
        }
        EditTextBoldCursor editText = new EditTextBoldCursor(getContext());
        editText.lineYFix = true;
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setText(ExteraConfig.getCustomSavePath());
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        editText.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, resourceProvider));
        editText.setHintText(LocaleController.getString(R.string.CustomSavePathHint));
        editText.setFocusable(true);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setBackground(null);
        editText.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourceProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourceProvider)
        );
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        editText.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));

        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 10));

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), resourceProvider);
        builder.setTitle(LocaleController.getString(R.string.CustomSavePath));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(AndroidUtilities.dp(292));
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (dialog, which) -> {
            String path = editText.getText() != null ? editText.getText().toString().trim() : "";
            if (!TextUtils.isEmpty(path) && !SAVE_PATH_PATTERN.matcher(path).matches()) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            ExteraConfig.setCustomSavePath(path);
            listView.adapter.update(true);
            dialog.dismiss();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> AndroidUtilities.hideKeyboard(editText));
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            editText.setSelection(editText.length());
            AndroidUtilities.showKeyboard(editText);
        });
        dialog.setDismissDialogByButtons(false);
        showDialog(dialog);
    }
}
