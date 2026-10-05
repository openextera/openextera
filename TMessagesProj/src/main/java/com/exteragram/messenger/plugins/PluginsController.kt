package com.exteragram.messenger.plugins

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import com.exteragram.messenger.plugins.hooks.PluginsHooks
import com.exteragram.messenger.plugins.utils.MenuContextBuilder
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Lite build: the plugin engine is not available, every entry point is a no-op stub.
 */
class PluginsController private constructor() : PluginsHooks {

    val plugins = ConcurrentHashMap<String, Any>()
    val preferences: SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", Context.MODE_PRIVATE)

    val isInitialized: Boolean
        get() = false

    fun init(safeMode: Boolean, onInitialized: Runnable?) {
        onInitialized?.run()
    }

    fun restart() {
    }

    fun restart(safeMode: Boolean) {
    }

    fun loadPluginSettings() {
    }

    fun executeOnAppEvent(event: String?) {
    }

    fun showInstallDialog(fragment: BaseFragment?, messageObject: MessageObject?) {
        showNotSupported(fragment)
    }

    fun showInstallDialog(fragment: BaseFragment?, path: String?, fromFile: Boolean) {
        showNotSupported(fragment)
    }

    private fun showNotSupported(fragment: BaseFragment?) {
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PluginsNotSupported)).show()
    }

    fun getMenuItemsForLocation(location: String?, contextBuilder: MenuContextBuilder?): List<Any> = emptyList()

    fun getMenuItemsForLocation(location: String?, context: Map<String, Any?>?): List<Any> = emptyList()

    override fun executePreRequestHook(requestName: String?, account: Int, request: TLObject?): TLObject? = request

    override fun executePostRequestHook(requestName: String?, account: Int, response: TLObject?, error: TLRPC.TL_error?): PluginsHooks.PostRequestResult =
        PluginsHooks.PostRequestResult(response, error)

    override fun executeUpdateHook(updateName: String?, account: Int, update: TLRPC.Update?): TLRPC.Update? = update

    override fun executeUpdatesHook(updatesName: String?, account: Int, updates: TLRPC.Updates?): TLRPC.Updates? = updates

    override fun executeSendMessageHook(account: Int, params: SendMessagesHelper.SendMessageParams?): SendMessagesHelper.SendMessageParams? = params

    companion object {
        private val sharedInstance by lazy { PluginsController() }

        @JvmStatic
        fun getInstance(): PluginsController = sharedInstance

        @JvmStatic
        fun isPluginEngineSupported(): Boolean = false

        @JvmStatic
        fun applyArtOpts() {
        }

        @JvmStatic
        fun isPlugin(messageObject: MessageObject?): Boolean =
            messageObject?.documentName?.lowercase(Locale.ROOT)?.endsWith(".plugin") == true

        @JvmStatic
        fun isPlugin(file: File?, messageObject: MessageObject?): Boolean =
            file?.name?.lowercase(Locale.ROOT)?.endsWith(".plugin") == true

        @JvmStatic
        fun getFileIconId(fileName: String?): Int = -1

        @JvmStatic
        fun isPluginFileIcon(iconId: Int): Boolean = false

        @JvmStatic
        fun getPluginFileIconDrawable(iconId: Int): Drawable? = null

        @JvmStatic
        fun openPluginSettings(pluginId: String?, linkAlias: String?) {
        }
    }
}
