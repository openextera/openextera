package com.exteragram.messenger.notifications

import android.view.View
import com.exteragram.messenger.preferences.BasePreferencesActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BotWebViewVibrationEffect
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AccountNotificationsActivity : BasePreferencesActivity() {

    companion object {
        private const val ACCOUNT_ID_OFFSET = 1000
    }

    private var shiftDp = -3f

    override fun getTitle(): String = LocaleController.getString(R.string.AccountNotifications)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ShowNotificationsFor)))
        for (account in AccountNotifications.getAccounts()) {
            items.add(
                UItem.asUserCheckbox(ACCOUNT_ID_OFFSET + account, UserConfig.getInstance(account).currentUser)
                    .setChecked(account == UserConfig.selectedAccount || AccountNotifications.isEnabled(account))
            )
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.AccountNotificationsInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.viewType != UniversalAdapter.VIEW_TYPE_USER_CHECKBOX) {
            return
        }
        val account = item.id - ACCOUNT_ID_OFFSET
        if (account == UserConfig.selectedAccount) {
            shiftDp = -shiftDp
            AndroidUtilities.shakeViewSpring(view, shiftDp)
            BotWebViewVibrationEffect.APP_ERROR.vibrate()
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.AccountNotificationsCurrent)).show()
            return
        }
        toggleBooleanSettingAndRefresh(item) { AccountNotifications.setEnabled(account, it) }
    }
}
