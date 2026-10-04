package com.exteragram.messenger.icons.ui.components

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.exteragram.messenger.ExteraConfig
import com.exteragram.messenger.components.CheckBoxRow
import com.exteragram.messenger.icons.IconManager
import com.exteragram.messenger.icons.IconPack
import com.exteragram.messenger.utils.text.LocaleUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EffectsTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

class InstallIconPackBottomSheet(
    context: Context,
    private val iconPack: IconPack,
    private val installDelegate: InstallDelegate
) : BottomSheet(context, false) {

    fun interface InstallDelegate {
        fun onInstall(enable: Boolean, isUpdate: Boolean)
    }

    init {
        fixNavigationBar()
        setCustomView(createView(context))
        setOnDismissListener(Runnable {
            AndroidUtilities.runOnUIThread({
                Utilities.globalQueue.postRunnable {
                    iconPack.location?.deleteRecursively()
                }
            }, 1000)
        })
    }

    private fun createView(context: Context): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(16f), 0, 0)
        }

        val previewView = IconPackPreviewView(context).apply {
            setCircularMode(true)
            setRefreshTime(3000)
            setIconPack(iconPack)
        }
        layout.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16))

        val titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            text = iconPack.name
        }
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 0f))

        val lastFragment = LaunchActivity.getSafeLastFragment()
        val installedPack = IconManager.findPackById(iconPack.id)
        val isUpdate = installedPack != null

        val infoView = EffectsTextView(context, resourcesProvider).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = AndroidUtilities.regular()
            movementMethod = AndroidUtilities.LinkMovementMethodMy()
            setLinkTextColor(getThemedColor(Theme.key_dialogTextLink))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText))
        }

        var info = SpannableStringBuilder(LocaleController.getString(R.string.PluginVersion)).append(" ")
        val versionStart = info.length
        if (installedPack != null) {
            val oldVersion = installedPack.version
            info.append(oldVersion).append(" -> ").append(iconPack.version)
            info = LocaleUtils.replaceArrows(context, info, R.drawable.msg_mini_arrow_mediathin)
            info.setSpan(StrikethroughSpan(), versionStart, versionStart + oldVersion.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (iconPack.version.isNotEmpty()) {
            info.append(iconPack.version)
        }
        if (iconPack.author.isNotEmpty()) {
            info.append(" • ").append(LocaleUtils.formatWithUsernames(iconPack.author, lastFragment) { dismiss() })
        }
        infoView.text = info
        layout.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 4f, 24f, 24f))

        val button = ButtonWithCounterView(context, true, resourcesProvider).apply {
            setRound()
            setCount(LocaleController.formatPluralString("IconCount", iconPack.icons.size), false)
            setText(LocaleController.getString(if (isUpdate) R.string.UpdatePack else R.string.InstallPack), false)
        }
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 16, 16))

        val enableCheckBox = if (ExteraConfig.iconPacksLayout.contains(iconPack.id)) {
            null
        } else {
            CheckBoxRow(context, LocaleController.getString(R.string.EnableAfterInstallation), true, resourcesProvider).also {
                layout.addView(it, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8))
            }
        }

        button.setOnClickListener {
            dismiss()
            AndroidUtilities.runOnUIThread({
                installDelegate.onInstall(enableCheckBox?.isChecked == true, isUpdate)
            }, 200)
        }
        return layout
    }
}
