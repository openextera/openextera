package com.exteragram.messenger.notifications

import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationsController
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig

object AccountNotifications {

    fun isEnabled(account: Int): Boolean =
        MessagesController.getGlobalNotificationsSettings().getBoolean(key(account), true)

    fun setEnabled(account: Int, enabled: Boolean) {
        MessagesController.getGlobalNotificationsSettings().edit().putBoolean(key(account), enabled).apply()
        apply(account)
    }

    @JvmStatic
    fun shouldShow(account: Int): Boolean {
        if (account == UserConfig.selectedAccount) {
            return true
        }
        return SharedConfig.showNotificationsForAllAccounts && isEnabled(account)
    }

    @JvmStatic
    fun applyAll() {
        getAccounts().forEach { apply(it) }
    }

    @JvmStatic
    fun getAccounts(): List<Int> =
        (0 until UserConfig.MAX_ACCOUNT_COUNT).filter { UserConfig.getInstance(it).isClientActivated }

    @JvmStatic
    fun getEnabledCount(): Int =
        getAccounts().count { it == UserConfig.selectedAccount || isEnabled(it) }

    private fun apply(account: Int) {
        val controller = NotificationsController.getInstance(account)
        if (shouldShow(account)) {
            controller.showNotifications()
        } else {
            controller.hideNotifications()
        }
    }

    private fun key(account: Int): String = "accountNotifications_" + UserConfig.getInstance(account).clientUserId
}
