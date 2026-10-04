package com.exteragram.messenger.icons

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import com.exteragram.messenger.icons.ui.picker.IconObserver
import org.telegram.messenger.FileLog

@Suppress("DEPRECATION")
class ExteraResources(
    private val original: Resources,
) : Resources(original.assets, original.displayMetrics, original.configuration) {

    val sourceAssets: AssetManager = original.assets

    init {
        IconManager.initialize()
    }

    override fun getDrawableForDensity(id: Int, density: Int, theme: Resources.Theme?): Drawable? {
        IconObserver.log(id)
        IconManager.getDrawable(id, density, theme)?.let { return it }
        var resId = id
        try {
            resId = IconManager.getIcon(id)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        return original.getDrawableForDensity(resId, density, theme)
    }

    fun getOriginalDrawable(id: Int): Drawable? {
        return try {
            original.getDrawable(id, null)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }
}
