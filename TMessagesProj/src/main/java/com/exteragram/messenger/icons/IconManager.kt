package com.exteragram.messenger.icons

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.LongSparseArray
import android.util.SparseArray
import android.util.SparseIntArray
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.collection.LruCache
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import com.caverock.androidsvg.SVG
import com.exteragram.messenger.ExteraConfig
import com.exteragram.messenger.IconPackType
import com.exteragram.messenger.export.output.FileManager
import com.exteragram.messenger.icons.ui.components.InstallIconPackBottomSheet
import com.exteragram.messenger.icons.ui.components.ReplaceIconBottomSheet
import com.exteragram.messenger.icons.ui.picker.IconPickerController
import com.exteragram.messenger.utils.chats.ChatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.simplifiles.SimpliFiles
import org.simplifiles.files.OverwritePolicy
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

object IconManager {

    private const val ICON_PICKER_REQUEST_CODE = 43
    private const val INITIALIZATION_TIMEOUT_MS = 1000L
    private const val PREWARM_TIME_BUDGET_MS = 150L
    private const val PREWARM_MAX_ICONS = 256
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    private const val MIN_DECODE_PIXELS = 1024L * 1024
    private const val MAX_CUSTOM_ICON_NAME_LENGTH = 64
    private const val NOTIFICATION_ICON = "notification"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutationDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val prewarmDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val blacklistedIcons = setOf(
        "system", "smiles_popup", "camera_btn", "cancel_big", "chats_archive_box", "chats_archive_arrow",
        "chats_archive_muted", "chats_archive_pin", "chats_widget_preview", "circle_big", "contacts_widget_preview",
        "etg_splash", "ev_minus", "ev_plus", "filled_chatlink_large", "field_carret_empty", "dino_pic", "circle",
        "widgets_light_badgebg", "greydivider", "greydivider_bottom", "greydivider_top", "groups_limit1", "ic_ab_new",
        "ic_foreground", "ic_foreground_monet", "ic_foreground_solid", "ic_player", "ic_reply_icon",
        "icon_background_clip", "icon_background_clip_round", "icon_plane", "icplaceholder", "large_ads_info",
        "large_away", "large_greeting", "large_log_actions", "large_monetize", "large_quickreplies",
        "list_selector_ex", "livepin", "load_big", "location_empty", "login_arrow1", "login_phone1", "logo_middle",
        "map_pin", "map_pin2", "map_pin3", "map_pin_circle", "map_pin_cone2", "map_pin_photo", "msg_media_gallery",
        "music_empty", "no_passport", "no_password", "nophotos", "nophotos3", "paint_elliptical_brush",
        "paint_neon_brush", "paint_radial_brush", "phone_activate", "photo_placeholder_in", "photo_tooltip2",
        "photoview_placeholder", "screencast_big", "scrollbar_vertical_thumb", "scrollbar_vertical_thumb_inset",
        "newmsg_divider", "ic_launcher_dr", "smiles_info", "sms_bubble", "sms_devices", "stats_tooltip", "sticker",
        "story_camera", "theme_preview_image", "transparent", "venue_tooltip", "videopreview", "bluecounter",
        "photobadge", "photos_rounded", "calendar_date", "menu_copy", "redcircle", "tooltip_arrow",
        "tooltip_arrow_up", "newyear", "cards_chat", "community_cards", "ic_call_notification_answer",
        "ic_call_notification_decline", "shortcut_compose", "shortcut_user", "slide_dot_big", "slide_dot_small",
        "cocoon_logo", "cocoon_text", "telegram_logo", "telegram_logo_2", "mastercard_icon", "ton_icon", "diamond",
    )

    val systemIcons = ConcurrentHashMap<String, Int>()

    @Volatile
    private var systemNames: IntStringMap? = null

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = max(1024, maxMemory / 8)

    private val resolvedCache = ResolvedIndex(2048)
    private val sourceCache = object : LruCache<SourceCacheKey, Bitmap>(cacheSize) {
        override fun sizeOf(key: SourceCacheKey, value: Bitmap): Int = value.byteCount / 1024
    }

    private val activePacks = CopyOnWriteArrayList<IconPack>()
    private var activePacksPublished = false
    private val iconOwnerMap = ConcurrentHashMap<String, IconPack>()

    @Volatile
    private var basePreinstalledMap: SparseIntArray? = null

    private val initialPacksLoaded = CountDownLatch(1)
    private var initializationJob: Job? = null
    private var prewarmJob: Job? = null

    @Volatile
    private var initializationGeneration = 0L

    @Volatile
    private var initializationTimedOut = false

    private val resultCallbacks = SparseArray<(Uri?) -> Unit>()

    enum class ActivePacksUpdate {
        STALE,
        UNCHANGED,
        CHANGED,
        INITIAL,
    }

    /**
     * Open addressing int -> string map (resource id -> resource entry name), cheaper than a HashMap for ~5k entries.
     */
    class IntStringMap(expectedSize: Int) {
        private var keys = IntArray(0)
        private var values = arrayOfNulls<String>(0)
        private var mask = 0
        private var shift = 0
        private var size = 0

        init {
            var capacity = 4
            while (capacity < expectedSize * 2) {
                capacity = capacity shl 1
            }
            allocate(capacity)
        }

        private fun allocate(capacity: Int) {
            keys = IntArray(capacity)
            values = arrayOfNulls(capacity)
            mask = capacity - 1
            shift = 32 - Integer.numberOfTrailingZeros(capacity)
        }

        fun get(key: Int): String? {
            if (key == 0) {
                return null
            }
            var index = (key * HASH_MULTIPLIER) ushr shift
            while (true) {
                val current = keys[index]
                if (current == key) {
                    return values[index]
                }
                if (current == 0) {
                    return null
                }
                index = (index + 1) and mask
            }
        }

        fun put(key: Int, value: String?) {
            if (key == 0) {
                return
            }
            if ((size + 1) * 2 > keys.size) {
                grow()
            }
            var index = (key * HASH_MULTIPLIER) ushr shift
            while (true) {
                val current = keys[index]
                if (current == 0) {
                    keys[index] = key
                    values[index] = value
                    size++
                    return
                }
                if (current == key) {
                    values[index] = value
                    return
                }
                index = (index + 1) and mask
            }
        }

        private fun grow() {
            val oldKeys = keys
            val oldValues = values
            allocate(oldKeys.size shl 1)
            size = 0
            for (i in oldKeys.indices) {
                if (oldKeys[i] != 0) {
                    put(oldKeys[i], oldValues[i])
                }
            }
        }

        companion object {
            private const val HASH_MULTIPLIER = -0x61c88647 // 0x9E3779B9, golden ratio
        }
    }

    data class SourceCacheKey(
        val generation: Long,
        val packId: String,
        val location: String?,
        val fileName: String,
        val resId: Int,
        val density: Int,
    )

    class ResolvedIndex(private val capacity: Int) {
        private val entries = LongSparseArray<SourceCacheKey>()

        @Synchronized
        fun get(key: Long): SourceCacheKey? = entries.get(key)

        @Synchronized
        fun put(key: Long, value: SourceCacheKey) {
            if (entries.size() >= capacity) {
                entries.clear()
            }
            entries.put(key, value)
        }

        @Synchronized
        fun clear() {
            entries.clear()
        }

        @Synchronized
        fun removeByResId(resId: Int) {
            for (i in entries.size() - 1 downTo 0) {
                if ((entries.keyAt(i) shr 32).toInt() == resId) {
                    entries.removeAt(i)
                }
            }
        }
    }

    init {
        initialize()
    }

    fun isBlacklisted(name: String): Boolean {
        return name in blacklistedIcons ||
            name.contains("avd") ||
            name.endsWith("_solar") ||
            name.endsWith("_remix") ||
            name.contains("$") ||
            name.contains("animationpin") ||
            name.contains("googlepay") ||
            name.contains("shadow") ||
            name.startsWith("ic_monochrome") ||
            name.startsWith("nocover") ||
            name.startsWith("gradient_") ||
            name.startsWith("stickers_back_") ||
            name.startsWith("media_doc_") ||
            name.startsWith("loading_animation") ||
            name.startsWith("intro_") ||
            name.startsWith("minibubble_") ||
            name.startsWith("book_") ||
            name.startsWith("call_") ||
            name.startsWith("dice") ||
            name.startsWith("msg_other_new_filled") ||
            name.startsWith("profile_level") ||
            name.startsWith("widget_") ||
            name.startsWith("zoom_slide") ||
            name.startsWith("zoom_round") ||
            name.startsWith("popup_fixed_alert") ||
            name.startsWith("search_dark") ||
            name.startsWith("bar_selector")
    }

    private fun resolvedCacheKey(resId: Int, density: Int): Long {
        return (resId.toLong() shl 32) or (density.toLong() and 0xffffffffL)
    }

    private fun sourceCacheKey(pack: IconPack, fileName: String, resId: Int, density: Int): SourceCacheKey {
        return SourceCacheKey(initializationGeneration, pack.id, pack.location?.absolutePath, fileName, resId, density)
    }

    private fun resourceName(resId: Int): String? {
        return systemNames?.get(resId) ?: try {
            ApplicationLoader.applicationContext.resources.getResourceEntryName(resId)
        } catch (e: Exception) {
            null
        }
    }

    private fun bitmapDrawable(bitmap: Bitmap): Drawable {
        return BitmapDrawable(ApplicationLoader.applicationContext.resources, bitmap)
    }

    private fun postIconPackUpdated() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.iconPackUpdated)
    }

    private fun invalidateIconCaches(packId: String, resId: Int) {
        resolvedCache.removeByResId(resId)
        sourceCache.snapshot().keys
            .filter { it.packId == packId && it.resId == resId }
            .forEach { sourceCache.remove(it) }
    }

    @Synchronized
    private fun publishBitmap(generation: Long, resourceName: String, pack: IconPack, resId: Int, density: Int, bitmap: Bitmap): Boolean {
        if (generation != initializationGeneration || iconOwnerMap[resourceName] != pack) {
            return false
        }
        val fileName = pack.icons[resourceName] ?: return false
        val key = sourceCacheKey(pack, fileName, resId, density)
        sourceCache.put(key, bitmap)
        resolvedCache.put(resolvedCacheKey(resId, density), key)
        return true
    }

    fun prefetchCustomPacks() {
        scope.launch {
            syncInstalledCustomPacks(IconPackStorage.getCustomPacks())
        }
    }

    /**
     * Newly installed custom packs that are neither enabled nor hidden get added to the hidden list.
     */
    @Synchronized
    private fun syncInstalledCustomPacks(packs: List<IconPack>): Boolean {
        var changed = false
        for (pack in packs) {
            val id = pack.id
            if (!ExteraConfig.iconPacksLayout.contains(id) && !ExteraConfig.iconPacksHidden.contains(id)) {
                ExteraConfig.iconPacksHidden.add(id)
                changed = true
            }
        }
        if (changed) {
            ExteraConfig.saveIconPacksLayout()
        }
        return changed
    }

    private fun rebuildOwnerMap() {
        iconOwnerMap.clear()
        // packs earlier in the layout take precedence, so walk backwards and let them overwrite
        for (i in activePacks.size - 1 downTo 0) {
            val pack = activePacks[i]
            if (!pack.isBase) {
                for (name in pack.icons.keys) {
                    iconOwnerMap[name] = pack
                }
            }
        }
        rebuildBasePreinstalledMap()
    }

    private fun rebuildBasePreinstalledMap() {
        val map = SparseIntArray()
        for (pack in activePacks) {
            val preinstalledMap = pack.preinstalledMap
            if (preinstalledMap != null && pack.isBase) {
                for (i in 0 until preinstalledMap.size()) {
                    val key = preinstalledMap.keyAt(i)
                    if (map.indexOfKey(key) < 0) {
                        map.put(key, preinstalledMap.valueAt(i))
                    }
                }
            }
        }
        basePreinstalledMap = if (map.size() == 0) null else map
    }

    @Synchronized
    private fun updateActivePacks(generation: Long, packs: List<IconPack>): ActivePacksUpdate {
        if (generation != initializationGeneration) {
            return ActivePacksUpdate.STALE
        }
        val wasPublished = activePacksPublished
        activePacksPublished = true
        if (wasPublished && activePacks == packs) {
            return ActivePacksUpdate.UNCHANGED
        }
        activePacks.clear()
        activePacks.addAll(packs)
        rebuildOwnerMap()
        resolvedCache.clear()
        sourceCache.evictAll()
        return if (wasPublished) ActivePacksUpdate.CHANGED else ActivePacksUpdate.INITIAL
    }

    /**
     * Replaces the active pack with the same id, must be called on the main thread.
     */
    private fun replaceActivePack(pack: IconPack): Boolean {
        val index = activePacks.indexOfFirst { it.id == pack.id }
        if (index == -1) {
            return false
        }
        activePacks[index] = pack
        rebuildOwnerMap()
        return true
    }

    private fun resolvePackIconFile(pack: IconPack, fileName: String): File? {
        val location = pack.location ?: File(IconPackStorage.iconPacksDirectory, pack.id)
        return try {
            SimpliFiles.directory(location).file(fileName).file
        } catch (e: Exception) {
            FileLog.e("Failed to resolve icon file for pack ${pack.id}", e)
            null
        }
    }

    @Synchronized
    private fun launchPrewarm(generation: Long) {
        if (generation != initializationGeneration) {
            return
        }
        prewarmJob?.cancel()
        prewarmJob = scope.launch(prewarmDispatcher) {
            val density = AndroidUtilities.displayMetrics.densityDpi
            val deadline = SystemClock.elapsedRealtime() + PREWARM_TIME_BUDGET_MS
            var decoded = 0
            for ((name, pack) in iconOwnerMap) {
                if (!isActive || generation != initializationGeneration) {
                    return@launch
                }
                val resId = systemIcons[name] ?: continue
                if (decoded++ >= PREWARM_MAX_ICONS || SystemClock.elapsedRealtime() >= deadline) {
                    return@launch
                }
                val bitmap = getPackIconBitmap(pack, resId, density, null, name, false) ?: continue
                if (!isActive || generation != initializationGeneration) {
                    return@launch
                }
                publishBitmap(generation, name, pack, resId, density, bitmap)
            }
        }
    }

    fun getDrawable(resId: Int, density: Int, theme: Resources.Theme?): Drawable? {
        if (iconOwnerMap.isEmpty()) {
            return null
        }
        val targetDensity = if (density == 0) AndroidUtilities.displayMetrics.densityDpi else density
        resolvedCache.get(resolvedCacheKey(resId, targetDensity))
            ?.let { sourceCache.get(it) }
            ?.let { return bitmapDrawable(it) }

        val name = resourceName(resId) ?: return null
        val pack = iconOwnerMap[name] ?: return null
        val generation = initializationGeneration
        val bitmap = getPackIconBitmap(pack, resId, targetDensity, theme, name, false) ?: return null
        publishBitmap(generation, name, pack, resId, targetDensity, bitmap)
        return bitmapDrawable(bitmap)
    }

    fun getCachedDrawable(resId: Int): Drawable? {
        if (iconOwnerMap.isEmpty()) {
            return null
        }
        val key = resolvedCache.get(resolvedCacheKey(resId, AndroidUtilities.displayMetrics.densityDpi)) ?: return null
        val bitmap = sourceCache.get(key) ?: return null
        return bitmapDrawable(bitmap)
    }

    fun getPackIconDrawable(pack: IconPack, resId: Int): Drawable? {
        return getPackIconBitmap(pack, resId, AndroidUtilities.displayMetrics.densityDpi, null)?.let { bitmapDrawable(it) }
    }

    private fun cachedPackIconBitmap(pack: IconPack, resId: Int, density: Int): Bitmap? {
        val name = systemNames?.get(resId) ?: return null
        val fileName = pack.icons[name] ?: return null
        return sourceCache.get(sourceCacheKey(pack, fileName, resId, density))
    }

    fun getCachedPackIconDrawable(pack: IconPack, resId: Int): Drawable? {
        return cachedPackIconBitmap(pack, resId, AndroidUtilities.displayMetrics.densityDpi)?.let { bitmapDrawable(it) }
    }

    fun requestPackIconDrawable(pack: IconPack, resId: Int, callback: Utilities.Callback<Drawable>) {
        val density = AndroidUtilities.displayMetrics.densityDpi
        val cached = cachedPackIconBitmap(pack, resId, density)
        if (cached != null) {
            callback.run(bitmapDrawable(cached))
            return
        }
        scope.launch(decodeDispatcher) {
            val bitmap = getPackIconBitmap(pack, resId, density, null) ?: return@launch
            withContext(Dispatchers.Main) {
                callback.run(bitmapDrawable(bitmap))
            }
        }
    }

    fun requestDrawable(resId: Int, callback: Utilities.Callback<Drawable>) {
        if (iconOwnerMap.isEmpty()) {
            return
        }
        val cached = getCachedDrawable(resId)
        if (cached != null) {
            callback.run(cached)
            return
        }
        val density = AndroidUtilities.displayMetrics.densityDpi
        scope.launch(decodeDispatcher) {
            val drawable = getDrawable(resId, density, null) ?: return@launch
            withContext(Dispatchers.Main) {
                callback.run(drawable)
            }
        }
    }

    private fun getPackIconBitmap(
        pack: IconPack,
        resId: Int,
        density: Int,
        theme: Resources.Theme?,
        resourceName: String? = null,
        cache: Boolean = true,
    ): Bitmap? {
        val name = resourceName ?: resourceName(resId) ?: return null
        val fileName = pack.icons[name] ?: return null
        val key = sourceCacheKey(pack, fileName, resId, density)
        sourceCache.get(key)?.let { return it }
        val file = resolvePackIconFile(pack, fileName) ?: return null
        val bitmap = createBitmapFromFile(file.absolutePath, resId, density, theme)
        if (bitmap != null && cache) {
            sourceCache.put(key, bitmap)
        }
        return bitmap
    }

    /**
     * Decodes (or rasterizes, for SVG) the given file into a bitmap sized like the original drawable [resId].
     */
    fun createBitmapFromFile(path: String, resId: Int, density: Int, theme: Resources.Theme?): Bitmap? {
        try {
            val resources = ApplicationLoader.applicationContext.resources
            val original = (resources as? ExteraResources)?.getOriginalDrawable(resId)
                ?: ResourcesCompat.getDrawableForDensity(resources, resId, density, theme)
            val width = max(1, original?.intrinsicWidth ?: AndroidUtilities.dp(24f))
            val height = max(1, original?.intrinsicHeight ?: AndroidUtilities.dp(24f))

            val file = File(path)
            DecodedIconStore.get(file, width, height, density)?.let { return it }

            if (path.endsWith(".svg", ignoreCase = true)) {
                val svg = FileInputStream(path).use { SVG.getFromInputStream(it) }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                svg.setDocumentWidth(width.toFloat())
                svg.setDocumentHeight(height.toFloat())
                svg.renderToCanvas(Canvas(bitmap))
                bitmap.density = density
                DecodedIconStore.put(file, width, height, density, bitmap)
                return bitmap
            }

            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            val sourceWidth = options.outWidth
            val sourceHeight = options.outHeight
            if (sourceWidth <= 0 || sourceHeight <= 0 || sourceWidth.toLong() * sourceHeight.toLong() > MAX_SOURCE_PIXELS) {
                return null
            }
            options.inSampleSize = 1
            if (sourceHeight > height || sourceWidth > width) {
                val halfHeight = sourceHeight / 2
                val halfWidth = sourceWidth / 2
                while (halfHeight / options.inSampleSize >= height && halfWidth / options.inSampleSize >= width) {
                    options.inSampleSize *= 2
                }
            }
            val maxPixels = max(width.toLong() * height.toLong(), MIN_DECODE_PIXELS)
            while (true) {
                val sampleSize = options.inSampleSize.toLong()
                val sampledWidth = (sourceWidth + sampleSize - 1) / sampleSize
                val sampledHeight = (sourceHeight + sampleSize - 1) / sampleSize
                if (sampledWidth * sampledHeight <= maxPixels) {
                    break
                }
                options.inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            val decoded = BitmapFactory.decodeFile(path, options) ?: return null
            val bitmap = if (decoded.width != width || decoded.height != height) {
                Bitmap.createScaledBitmap(decoded, width, height, true).also {
                    if (it != decoded) {
                        decoded.recycle()
                    }
                }
            } else {
                decoded
            }
            bitmap.density = density
            DecodedIconStore.put(file, width, height, density, bitmap)
            return bitmap
        } catch (e: Exception) {
            FileLog.e("Error loading icon bitmap: $path", e)
            return null
        } catch (e: OutOfMemoryError) {
            FileLog.e("Out of memory loading icon bitmap: $path", e)
            return null
        }
    }

    private fun deleteQuietly(file: File): Result<Unit> = runCatching {
        if (file.exists()) {
            SimpliFiles.file(file).delete()
        }
    }

    fun saveCustomIcon(packId: String, resId: Int, tempFile: File, originalName: String?) {
        val resourceName = resourceName(resId) ?: return
        scope.launch(mutationDispatcher) {
            var targetFile: File? = null
            var committed = false
            try {
                val pack = IconPackStorage.findPackById(packId) ?: return@launch

                val extension = tempFile.extension
                val userName = originalName?.let { FileManager.fileNameFromUserString(it) } ?: ""
                val sourceName = userName.ifEmpty { resourceName }
                val baseName = sourceName.substringBeforeLast('.', sourceName)
                    .ifEmpty { resourceName }
                    .take(MAX_CUSTOM_ICON_NAME_LENGTH)
                    .dropLastWhile { Character.isHighSurrogate(it) }

                val packDir = SimpliFiles.directory(File(IconPackStorage.iconPacksDirectory, pack.id)).create()
                var target: File
                do {
                    target = packDir.file("${baseName}_${UUID.randomUUID()}.$extension").file
                } while (target.exists())
                targetFile = target
                SimpliFiles.file(tempFile).copyTo(target, OverwritePolicy.ERROR)

                val icons = pack.icons.toMutableMap()
                val previousFileName = icons.put(resourceName, target.name)
                val updatedPack = pack.copy(icons = icons)
                if (!IconPackStorage.saveIconPackMetadata(updatedPack)) {
                    return@launch
                }
                committed = true

                if (previousFileName != null && previousFileName !in icons.values) {
                    resolvePackIconFile(pack, previousFileName)?.let { oldFile ->
                        deleteQuietly(oldFile).exceptionOrNull()?.let { FileLog.e("Failed to delete old icon", it) }
                    }
                }

                val density = AndroidUtilities.displayMetrics.densityDpi
                val preDecoded = createBitmapFromFile(target.absolutePath, resId, density, null)
                withContext(Dispatchers.Main) {
                    if (replaceActivePack(updatedPack)) {
                        invalidateIconCaches(updatedPack.id, resId)
                        if (preDecoded != null) {
                            publishBitmap(initializationGeneration, resourceName, updatedPack, resId, density, preDecoded)
                        }
                    }
                    postIconPackUpdated()
                }
            } catch (e: Exception) {
                FileLog.e("Failed to save custom icon", e)
            } finally {
                deleteQuietly(tempFile)
                if (!committed) {
                    targetFile?.let { deleteQuietly(it) }
                }
            }
        }
    }

    fun resetCustomIcon(packId: String, resId: Int) {
        val resourceName = resourceName(resId) ?: return
        scope.launch(mutationDispatcher) {
            val pack = IconPackStorage.findPackById(packId) ?: return@launch
            val fileName = pack.icons[resourceName] ?: return@launch
            val icons = pack.icons.toMutableMap()
            icons.remove(resourceName)
            val updatedPack = pack.copy(icons = icons)
            if (!IconPackStorage.saveIconPackMetadata(updatedPack)) {
                return@launch
            }
            if (fileName !in icons.values) {
                runCatching {
                    val file = resolvePackIconFile(pack, fileName)
                    if (file != null && file.exists()) {
                        SimpliFiles.file(file).delete()
                    }
                }.exceptionOrNull()?.let { FileLog.e("Failed to delete old icon", it) }
            }
            withContext(Dispatchers.Main) {
                if (replaceActivePack(updatedPack)) {
                    invalidateIconCaches(updatedPack.id, resId)
                }
                postIconPackUpdated()
            }
        }
    }

    /**
     * Maps a drawable to its replacement from the enabled base (preinstalled) icon pack.
     */
    fun getIcon(resId: Int): Int {
        val replacement = basePreinstalledMap?.get(resId, -1) ?: -1
        return if (replacement == -1) resId else replacement
    }

    @JvmStatic
    fun getNotificationIcon(): IconCompat {
        return try {
            ExteraConfig.loadConfig()
            val customPack = ExteraConfig.iconPacksLayout.asSequence()
                .filterNot { it.startsWith(IconPack.BASE_PREFIX) }
                .mapNotNull { IconPackStorage.findPackById(it) }
                .firstOrNull { it.icons.containsKey(NOTIFICATION_ICON) }
            if (customPack != null) {
                val icon = if (Build.VERSION.SDK_INT >= 31) {
                    IconPackProvider.getIconUri(customPack.id, NOTIFICATION_ICON)?.let { uri ->
                        try {
                            ApplicationLoader.applicationContext.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e: Exception) {
                            FileLog.e(e)
                        }
                        IconCompat.createWithContentUri(uri)
                    }
                } else {
                    getPackIconBitmap(customPack, R.drawable.notification, AndroidUtilities.displayMetrics.densityDpi, null, NOTIFICATION_ICON)
                        ?.let { IconCompat.createWithBitmap(it) }
                }
                if (icon != null) {
                    return icon
                }
            }
            val baseIcon = ExteraConfig.iconPacksLayout.asSequence()
                .filter { it.startsWith(IconPack.BASE_PREFIX) }
                .mapNotNull { BaseIconPacks.getBasePack(it)?.preinstalledMap }
                .map { it.get(R.drawable.notification, -1) }
                .firstOrNull { it != -1 }
            IconCompat.createWithResource(ApplicationLoader.applicationContext, baseIcon ?: R.drawable.notification)
        } catch (e: Exception) {
            FileLog.e("Failed to resolve notification icon", e)
            IconCompat.createWithResource(ApplicationLoader.applicationContext, R.drawable.notification)
        }
    }

    @JvmStatic
    fun getNotificationSystemIcon(): Icon {
        return getNotificationIcon().toIcon(ApplicationLoader.applicationContext)
    }

    @Synchronized
    fun initialize(update: Boolean = false) {
        val currentJob = initializationJob
        if (currentJob != null && currentJob.isActive && !update) {
            return
        }
        if (update) {
            currentJob?.cancel()
        }
        prewarmJob?.cancel()
        initializationGeneration++
        val generation = initializationGeneration
        initializationJob = scope.launch {
            val result = try {
                ExteraConfig.loadConfig()
                val packs = ArrayList<IconPack>()
                for (id in ExteraConfig.iconPacksLayout) {
                    val pack = if (id.startsWith(IconPack.BASE_PREFIX)) {
                        BaseIconPacks.getBasePack(id)
                    } else {
                        IconPackStorage.findPackById(id)
                    }
                    if (pack != null) {
                        packs.add(pack)
                    }
                }
                if (generation != initializationGeneration) {
                    return@launch
                }
                updateActivePacks(generation, packs)
            } finally {
                initialPacksLoaded.countDown()
            }

            if (systemIcons.isEmpty()) {
                loadSystemIcons()
            }

            when (result) {
                ActivePacksUpdate.STALE -> return@launch
                ActivePacksUpdate.UNCHANGED -> {
                    if (update) {
                        resolvedCache.clear()
                        sourceCache.evictAll()
                        withContext(Dispatchers.Main) {
                            if (generation == initializationGeneration) {
                                postIconPackUpdated()
                            }
                        }
                    }
                }
                ActivePacksUpdate.CHANGED, ActivePacksUpdate.INITIAL -> {
                    withContext(Dispatchers.Main) {
                        onActivePacksChanged(generation, result)
                    }
                }
            }
            launchPrewarm(generation)
        }
    }

    private fun loadSystemIcons() {
        try {
            val names = IntStringMap(2048)
            for (field in R.drawable::class.java.fields) {
                val name = field.name
                if (!isBlacklisted(name)) {
                    val resId = field.getInt(null)
                    systemIcons[name] = resId
                    names.put(resId, name)
                }
            }
            systemNames = names
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun onActivePacksChanged(generation: Long, result: ActivePacksUpdate) {
        if (generation != initializationGeneration) {
            return
        }
        val editingPackId = ExteraConfig.editingIconPackId
        if (editingPackId != null) {
            val editingPack = activePacks.firstOrNull { it.id == editingPackId }
            val topCustomPack = activePacks.firstOrNull { !it.isBase }
            val launchActivity = LaunchActivity.getSafeLastFragment()?.parentActivity as? LaunchActivity
            if (editingPack != null && editingPack.id == topCustomPack?.id) {
                launchActivity?.let { IconPickerController.setActive(it, true) }
            } else {
                // the pack being edited is no longer the one on top, stop editing
                ExteraConfig.editingIconPackId = null
                launchActivity?.let { IconPickerController.setActive(it, false) }
            }
        }
        if (result == ActivePacksUpdate.INITIAL && !initializationTimedOut) {
            return
        }
        postIconPackUpdated()
        val fragment = LaunchActivity.getSafeLastFragment() ?: return
        Theme.reloadAllResources(fragment.parentActivity)
        if (result == ActivePacksUpdate.INITIAL) {
            (fragment.parentActivity as? LaunchActivity)?.rebuildAllFragments(true)
        } else {
            fragment.parentLayout?.rebuildFragments(0)
        }
    }

    fun awaitInitialization() {
        try {
            if (initialPacksLoaded.await(INITIALIZATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return
            }
            initializationTimedOut = true
            FileLog.e("Icon packs were not loaded in ${INITIALIZATION_TIMEOUT_MS}ms")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun setActiveCustomPack(packId: String?) {
        if (packId == null || ExteraConfig.iconPacksLayout.contains(packId)) {
            return
        }
        ExteraConfig.iconPacksLayout.add(packId)
        ExteraConfig.iconPacksHidden.remove(packId)
        ExteraConfig.saveIconPacksLayout()
        initialize(true)
    }

    fun findPackById(packId: String): IconPack? = IconPackStorage.findPackById(packId)

    fun bundlePackBlocking(packId: String): File? = IconPackStorage.bundlePackBlocking(packId)

    fun saveIconPackMetadata(pack: IconPack): Boolean {
        val savedPack = runBlocking(mutationDispatcher) {
            // keep the icons currently on disk, only the pack info is edited here
            val icons = IconPackStorage.findPackById(pack.id)?.icons
            val packToSave = icons?.let { pack.copy(icons = it) } ?: pack
            if (IconPackStorage.saveIconPackMetadata(packToSave)) packToSave else null
        } ?: return false
        scope.launch(Dispatchers.Main) {
            if (replaceActivePack(savedPack)) {
                resolvedCache.clear()
                sourceCache.evictAll()
            }
            postIconPackUpdated()
        }
        return true
    }

    fun deletePack(packId: String) {
        scope.launch(mutationDispatcher) {
            IconPackStorage.deletePack(packId)
            withContext(Dispatchers.Main) {
                if (ExteraConfig.iconPacksLayout.contains(packId) || ExteraConfig.iconPacksHidden.contains(packId)) {
                    ExteraConfig.iconPacksLayout.remove(packId)
                    ExteraConfig.iconPacksHidden.remove(packId)
                    ExteraConfig.saveIconPacksLayout()
                }
                initialize(true)
            }
        }
    }

    fun isIconPack(messageObject: MessageObject?): Boolean {
        return isIconPack(ChatUtils.getInstance().getPathToMessage(messageObject), messageObject)
    }

    fun isIconPack(path: String?, messageObject: MessageObject?): Boolean {
        return messageObject?.documentName != null && !path.isNullOrEmpty() && path.endsWith(".icons")
    }

    fun handleIconPack(baseFragment: BaseFragment, messageObject: MessageObject) {
        handleIconPack(baseFragment, ChatUtils.getInstance().getPathToMessage(messageObject))
    }

    private fun iconPackErrorText(error: IconPackStorageError): String {
        return LocaleController.getString(
            when (error) {
                IconPackStorageError.INVALID_ARCHIVE -> R.string.IconPackErrorInvalidArchive
                IconPackStorageError.MISSING_METADATA -> R.string.IconPackErrorMissingMetadata
                IconPackStorageError.METADATA_TOO_LARGE -> R.string.IconPackErrorMetadataTooLarge
                IconPackStorageError.INVALID_METADATA -> R.string.IconPackErrorInvalidMetadata
                IconPackStorageError.TOO_MANY_FILES -> R.string.IconPackErrorTooManyFiles
                IconPackStorageError.ARCHIVE_TOO_LARGE -> R.string.IconPackErrorArchiveTooLarge
                IconPackStorageError.FILE_TOO_LARGE -> R.string.IconPackErrorFileTooLarge
                IconPackStorageError.COMPRESSION_RATIO_TOO_HIGH -> R.string.IconPackErrorCompressionRatioTooHigh
                IconPackStorageError.STORAGE_ERROR -> R.string.IconPackErrorStorage
                IconPackStorageError.UNKNOWN -> R.string.UnknownError
            }
        )
    }

    private fun showIconPackError(baseFragment: BaseFragment, error: IconPackStorageError) {
        BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error, iconPackErrorText(error)).show()
    }

    fun handleIconPack(baseFragment: BaseFragment, path: String) {
        scope.launch {
            val file = File(path)
            val packResult = IconPackStorage.parsePackFromZip(file)
            withContext(Dispatchers.Main) {
                when (packResult) {
                    is IconPackStorageResult.Success -> {
                        val pack = packResult.value
                        val bottomSheet = InstallIconPackBottomSheet(baseFragment.parentActivity, pack) { enable, update ->
                            scope.launch {
                                installIconPack(baseFragment, file, pack, enable, update)
                            }
                        }
                        baseFragment.showDialog(bottomSheet)
                    }
                    is IconPackStorageResult.Failure -> showIconPackError(baseFragment, packResult.error)
                }
            }
        }
    }

    private suspend fun installIconPack(baseFragment: BaseFragment, file: File, pack: IconPack, enable: Boolean, update: Boolean) {
        val installResult = IconPackStorage.installPack(file)
        withContext(Dispatchers.Main) {
            when (installResult) {
                is IconPackStorageResult.Success -> {
                    BulletinFactory.of(baseFragment).createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.formatString(if (update) R.string.PluginUpdated else R.string.PluginInstalled, pack.name)
                    ).show()
                    if (enable) {
                        setActiveCustomPack(pack.id)
                    } else {
                        syncInstalledCustomPacks(listOf(pack))
                        initialize(true)
                    }
                }
                is IconPackStorageResult.Failure -> showIconPackError(baseFragment, installResult.error)
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val callback = resultCallbacks.get(requestCode) ?: return false
        resultCallbacks.remove(requestCode)
        callback(data?.data)
        return true
    }

    fun startIconPicker(activity: Activity, useDocumentPicker: Boolean, callback: (Uri?) -> Unit) {
        resultCallbacks.put(ICON_PICKER_REQUEST_CODE, callback)
        val intent = if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(activity) && !useDocumentPicker) {
            ActivityResultContracts.PickVisualMedia().createIntent(
                activity,
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } else {
            Intent(if (useDocumentPicker) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "image/svg+xml"))
                if (useDocumentPicker) {
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            }
        }
        activity.startActivityForResult(intent, ICON_PICKER_REQUEST_CODE)
    }

    @JvmOverloads
    fun showReplaceAlert(context: Context, resId: Int, iconPack: IconPack? = null) {
        val pack = iconPack ?: run {
            val editingPackId = ExteraConfig.editingIconPackId
            if (editingPackId != null) {
                activePacks.firstOrNull { it.id == editingPackId }
            } else {
                activePacks.firstOrNull { !it.isBase }
            }
        } ?: return
        val fragment = LaunchActivity.getSafeLastFragment() ?: return
        fragment.showDialog(ReplaceIconBottomSheet(context, resId, pack))
    }

    fun isBasePackOnly(type: IconPackType): Boolean {
        if (ExteraConfig.iconPack != type) {
            return false
        }
        return activePacks.all { it.isBase }
    }
}
