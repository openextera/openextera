package com.exteragram.messenger.preferences;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.net.Uri;
import android.os.CountDownTimer;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.api.db.DatabaseHelper;
import com.exteragram.messenger.api.dto.BoostySubscriberDTO;
import com.exteragram.messenger.backup.PreferencesUtils;
import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.components.BoostyBottomSheet;
import com.exteragram.messenger.components.SupporterBottomSheet;
import com.exteragram.messenger.export.ui.ExportActivity;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.exteragram.messenger.utils.system.VibratorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LinkifyPort;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class OtherPreferencesActivity extends BasePreferencesActivity {

    private List<Donate> donates = Collections.emptyList();
    private final ArrayList<BoostySubscriberDTO> subscribers = new ArrayList<>();
    private final IconInfo defaultDonateIcon = new IconInfo(R.drawable.msg_payment_card, 0);

    public enum OtherItem {
        CRASHLYTICS,
        ANALYTICS,
        EXPORT_SETTINGS,
        EXPORT_DATA,
        RESET_SETTINGS,
        DELETE_ACCOUNT,
        DONATE;

        public int getId() {
            return ordinal() + 1;
        }
    }

    public static List<Donate> getDonates() {
        Set<String> values = RemoteUtils.getStringSetConfigValue("donates", Collections.emptySet());
        ArrayList<Donate> result = new ArrayList<>();
        for (String value : values) {
            String[] parts = value.split("#", -1);
            if (parts.length == 2) {
                result.add(new Donate(parts[0], parts[1]));
            }
        }
        return result;
    }

    @Override
    public View createView(Context context) {
        DatabaseHelper.getBoostySubscribers(subscribers::addAll);
        return super.createView(context);
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.LocalOther);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        donates = getDonates();
        boolean dark = Theme.isCurrentThemeDark();
        Map<String, IconInfo> icons = new LinkedHashMap<>();
        icons.put("mastercard", new IconInfo(R.drawable.mastercard_icon, dark ? Color.WHITE : Color.BLACK));
        icons.put("tonkeeper", new IconInfo(R.drawable.ton_icon, dark ? 0xFF27364D : 0xFF10161F));
        icons.put("space", new IconInfo(R.drawable.ton_space_icon, 0xFF30A9F6));
        icons.put("boosty", new IconInfo(R.drawable.boosty_icon, dark ? 0xFFEEEEEE : 0xFF242B2C));
        addDonateSection(items, LocaleController.getString(R.string.Support), donates, icons, 0);

        // Firebase is not used in OpenExtera: these switches only keep their preference values.
        items.add(UItem.asHeader("Google"));
        items.add(UItem.asCheck(OtherItem.CRASHLYTICS.getId(), "Crashlytics", R.drawable.msg_report).setChecked(ExteraConfig.getUseGoogleCrashlytics()).setSearchable(this).setLinkAlias("crashlytics", this));
        items.add(UItem.asCheck(OtherItem.ANALYTICS.getId(), "Analytics", R.drawable.msg_data).setChecked(ExteraConfig.getUseGoogleAnalytics()).setSearchable(this).setLinkAlias("analytics", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.AnalyticsInfo)));

        items.add(UItem.asButton(OtherItem.EXPORT_SETTINGS.getId(), R.drawable.msg_settings, LocaleController.getString(R.string.ExportSettings)).setSearchable(this).setLinkAlias("exportSettings", this));
        // lite 12.10.6 evaluates a plugin predicate here that R8 folded away (plugins are stubbed in lite)
        if (BadgesController.INSTANCE.isDeveloper()) {
            items.add(UItem.asButton(OtherItem.EXPORT_DATA.getId(), R.drawable.msg_archive, LocaleController.getString(R.string.ExportData)).setSearchable(this).setLinkAlias("exportData", this));
        }
        items.add(UItem.asButton(OtherItem.RESET_SETTINGS.getId(), R.drawable.msg_reset, LocaleController.getString(R.string.ResetSettings)).setSearchable(this).setLinkAlias("resetSettings", this));
        items.add(UItem.asButton(OtherItem.DELETE_ACCOUNT.getId(), R.drawable.msg_clearcache, LocaleController.getString(R.string.DeleteAccount)).red().setSearchable(this).setLinkAlias("deleteAccount", this));
        items.add(UItem.asShadow());
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        int donateId = OtherItem.DONATE.getId();
        if (item.id >= donateId && item.id < donateId + donates.size()) {
            return handleDonateLongClick(item, view);
        }
        return super.onLongClick(item, view, position, x, y);
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        int donateId = OtherItem.DONATE.getId();
        if (id >= donateId && id < donateId + donates.size()) {
            Donate donate = getDonate(id);
            if (donate != null) {
                handleDonateClick(donate);
            }
            return;
        }
        if (id <= 0 || id >= donateId) {
            return;
        }
        switch (OtherItem.values()[id - 1]) {
            case CRASHLYTICS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setUseGoogleCrashlytics);
                break;
            case ANALYTICS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setUseGoogleAnalytics);
                break;
            case RESET_SETTINGS:
                handleResetSettingsClick();
                break;
            case DELETE_ACCOUNT:
                handleDeleteAccountClick();
                break;
            case EXPORT_SETTINGS:
                PreferencesUtils.getInstance().exportSettings(this);
                break;
            case EXPORT_DATA:
                presentFragment(new ExportActivity(null));
                break;
        }
    }

    private void addDonateSection(ArrayList<UItem> items, String header, List<Donate> list, Map<String, IconInfo> icons, int idOffset) {
        if (list == null || list.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(header));
        for (int i = 0; i < list.size(); i++) {
            Donate donate = list.get(i);
            String name = donate.name().toLowerCase(Locale.ROOT);
            IconInfo iconInfo = null;
            for (Map.Entry<String, IconInfo> entry : icons.entrySet()) {
                if (name.contains(entry.getKey())) {
                    iconInfo = entry.getValue();
                    break;
                }
            }
            if (iconInfo == null) {
                iconInfo = defaultDonateIcon;
            }
            UItem item = UItem.asButton(OtherItem.DONATE.getId() + idOffset + i, donate.name()).setSearchable(this);
            if (iconInfo.iconColor() == 0) {
                item.setIcon(iconInfo.iconResId());
            } else {
                item.setColorfulIcon(iconInfo.iconResId(), iconInfo.iconColor());
            }
            items.add(item);
        }
        items.add(UItem.asShadow(AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.GetBadgeInfo), () -> SupporterBottomSheet.showAlert(this, null))));
    }

    private Donate getDonate(int id) {
        int index = id - OtherItem.DONATE.getId();
        if (index < 0 || index >= donates.size()) {
            return null;
        }
        return donates.get(index);
    }

    private void handleResetSettingsClick() {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.ResetPreferencesInfo)))
                .setTitle(LocaleController.getString(R.string.ResetSettings))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Reset), (d, which) -> {
                    PreferencesUtils.clearPreferences();
                    parentLayout.rebuildFragments(0);
                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                    LocaleController.getInstance().recreateFormatters();
                    Theme.reloadAllResources(getParentActivity());
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ResetPreferences), getResourceProvider()).show();
                })
                .create();
        showDialog(dialog);
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void handleDeleteAccountClick() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setMessage(LocaleController.getString(R.string.TosDeclineDeleteAccount));
        builder.setTitle(LocaleController.getString(R.string.DeleteAccount));
        builder.setPositiveButton(LocaleController.getString(R.string.Deactivate), (d, which) -> {
            AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progressDialog.setCanCancel(false);
            progressDialog.show();
            Utilities.globalQueue.postRunnable(() -> {
                TL_account.deleteAccount req = new TL_account.deleteAccount();
                req.reason = "ЭКСТЕРАГРАМ";
                getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    try {
                        progressDialog.dismiss();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (response instanceof TLRPC.TL_boolTrue) {
                        getMessagesController().performLogout(0);
                    } else if (error == null || error.code != -1000) {
                        String message = LocaleController.getString(R.string.ErrorOccurred);
                        if (error != null && error.text != null) {
                            message += "\n" + error.text;
                        }
                        AlertDialog.Builder errorBuilder = new AlertDialog.Builder(getParentActivity());
                        errorBuilder.setTitle(LocaleController.getString(R.string.AppName));
                        errorBuilder.setMessage(message);
                        errorBuilder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                        errorBuilder.show();
                    }
                }));
            }, 500);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            View view = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (!(view instanceof TextView)) {
                return;
            }
            TextView button = (TextView) view;
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            button.setEnabled(false);
            CharSequence buttonText = button.getText();
            new CountDownTimer(30000, 100) {
                @Override
                public void onTick(long millisUntilFinished) {
                    button.setText(String.format(Locale.getDefault(), "%s • %d", buttonText, millisUntilFinished / 1000 + 1));
                }

                @Override
                public void onFinish() {
                    button.setText(buttonText);
                    button.setEnabled(true);
                }
            }.start();
        });
        showDialog(dialog);
    }

    private void handleDonateClick(Donate donate) {
        String name = donate.name().toLowerCase(Locale.getDefault());
        if (name.contains("ton")) {
            String url = "ton://transfer/" + donate.details() + "?text=" + UserConfig.getInstance(currentAccount).getClientUserId();
            if (!Browser.isInternalUri(Uri.parse(url), new boolean[]{false})) {
                Browser.openUrl(getParentActivity(), url);
            } else if (AndroidUtilities.addToClipboard(donate.details())) {
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
            return;
        }
        if (name.contains("boosty") && !subscribers.isEmpty()) {
            if (getParentActivity() == null) {
                return;
            }
            showDialog(new BoostyBottomSheet(getParentActivity(), subscribers) {
                @Override
                public void onButtonClick() {
                    Browser.openUrl(getParentActivity(), donate.details());
                }
            });
        } else if (LinkifyPort.WEB_URL.matcher(donate.details()).matches()) {
            Browser.openUrl(getParentActivity(), donate.details());
        } else if (AndroidUtilities.addToClipboard(donate.details())) {
            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }

    private boolean handleDonateLongClick(UItem item, View view) {
        Donate donate = getDonate(item.id);
        if (donate != null && AndroidUtilities.addToClipboard(donate.details())) {
            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
        view.performHapticFeedback(VibratorUtils.getType(HapticFeedbackConstants.KEYBOARD_TAP), HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        return false;
    }

    public static final class IconInfo {
        private final int iconResId;
        private final int iconColor;

        private IconInfo(int iconResId, int iconColor) {
            this.iconResId = iconResId;
            this.iconColor = iconColor;
        }

        public int iconResId() {
            return iconResId;
        }

        public int iconColor() {
            return iconColor;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IconInfo)) {
                return false;
            }
            IconInfo other = (IconInfo) o;
            return iconResId == other.iconResId && iconColor == other.iconColor;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(iconResId) * 31 + Integer.hashCode(iconColor);
        }

        @Override
        public String toString() {
            return "IconInfo(iconResId=" + iconResId + ", iconColor=" + iconColor + ")";
        }
    }

    public static final class Donate {
        private final String name;
        private final String details;

        public Donate(String name, String details) {
            this.name = name;
            this.details = details;
        }

        public String name() {
            return name;
        }

        public String details() {
            return details;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Donate)) {
                return false;
            }
            Donate other = (Donate) o;
            return Objects.equals(name, other.name) && Objects.equals(details, other.details);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + details.hashCode();
        }

        @Override
        public String toString() {
            return "Donate(name=" + name + ", details=" + details + ")";
        }
    }
}
