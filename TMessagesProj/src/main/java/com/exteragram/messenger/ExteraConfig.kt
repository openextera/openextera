package com.exteragram.messenger

import android.content.SharedPreferences
import android.util.Pair
import com.exteragram.messenger.adblock.backend.AdBlockManager
import com.exteragram.messenger.api.ApiController
import com.exteragram.messenger.backup.PreferencesUtils
import com.exteragram.messenger.config.BooleanPref
import com.exteragram.messenger.config.BottomNavigationBar
import com.exteragram.messenger.config.EnumPref
import com.exteragram.messenger.config.FloatPref
import com.exteragram.messenger.config.IntegerPref
import com.exteragram.messenger.config.LongPref
import com.exteragram.messenger.config.NullableStringPref
import com.exteragram.messenger.config.SanitizedIntegerPref
import com.exteragram.messenger.config.StringPref
import com.exteragram.messenger.config.StringSetPref
import com.exteragram.messenger.config.allDelegates
import com.exteragram.messenger.config.registeredKeys
import com.exteragram.messenger.icons.IconManager
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.translator.TranslatorUtils
import com.exteragram.messenger.utils.addIf
import com.exteragram.messenger.utils.network.RemoteUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.SharedConfig
import org.telegram.ui.web.SearchEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ExteraConfig {

    private const val API_BOT_KEY = "extera_api_bot"
    private const val DEFAULT_API_BOT_ID = 8083294286L
    private const val DEFAULT_API_BOT_USERNAME = "exteraAuthBot"

    private val BASE_ICON_PACKS = arrayOf("base.default", "base.solar", "base.remix")

    @JvmStatic
    val GSON = Gson()

    @JvmStatic
    val preferences: SharedPreferences = PreferencesUtils.getPreferences("exteraconfig")

    @JvmStatic
    val editor: SharedPreferences.Editor = preferences.edit()

    private val sync = Any()
    private val servicesStarted = AtomicBoolean(false)

    @Volatile
    private var configLoaded = false

    @Volatile
    private var currentApiBot: Pair<Long, String>? = null
    private var apiBotListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // General
    @JvmStatic var translationProvider by IntegerPref(0)
    @JvmStatic var disableNumberRounding by BooleanPref(false)
    @JvmStatic var formatTimeWithSeconds by BooleanPref(false)
    @JvmStatic var relativeLastSeen by BooleanPref(false)
    @JvmStatic var inAppVibration by BooleanPref(true)
    @JvmStatic var disableNotificationDelay by BooleanPref(false)
    @JvmStatic var filterZalgo by BooleanPref(true)
    @JvmStatic var downloadSpeedBoost by IntegerPref(0)
    @JvmStatic var uploadSpeedBoost by BooleanPref(false)
    @JvmStatic var hidePhoneNumber by BooleanPref(false)
    @JvmStatic var showIdAndDc by IntegerPref(1)
    @JvmStatic var hideArchiveFolder by BooleanPref(false)
    @JvmStatic var archiveOnPull by BooleanPref(false)
    @JvmStatic var disableUnarchiveSwipe by BooleanPref(true)
    @JvmStatic var doNotUseProxy by IntegerPref(0)
    @JvmStatic var customSavePath by StringPref("exteraGram")

    @JvmStatic
    var doNotMarkAsNew = ArrayList<String>()
        private set

    @JvmStatic
    var newFeaturesShowedAt = HashMap<String, Long>()
        private set

    // Appearance
    @JvmStatic var iconPack by EnumPref(IconPackType.DEFAULT)
    @JvmStatic var editingIconPackId by NullableStringPref(null)

    @JvmStatic
    var iconPacksLayout = ArrayList<String>()
        private set

    @JvmStatic
    var iconPacksHidden = ArrayList<String>()
        private set

    @JvmStatic var avatarCorners by FloatPref(28f)
    @JvmStatic var singleCornerRadius by BooleanPref(false)
    @JvmStatic var dividerStyle by EnumPref(DividerStyle.LINE)
    @JvmStatic var forceSnow by BooleanPref(false)
    @JvmStatic var hideActionBarStatus by BooleanPref(false)
    @JvmStatic var centerTitle by BooleanPref(false)
    @JvmStatic var hideStories by BooleanPref(false)
    @JvmStatic var hideFloatingButton by BooleanPref(false)
    @JvmStatic var hideDialogsSearchBar by BooleanPref(false)
    @JvmStatic var senderMiniAvatars by BooleanPref(true)
    @JvmStatic var titleText by IntegerPref(0)
    @JvmStatic var tabIcons by EnumPref(TabIconsMode.TITLES_ONLY)
    @JvmStatic var tabCounterMode by EnumPref(TabCounterMode.ALL)
    @JvmStatic var hideAllChats by BooleanPref(false)
    @JvmStatic var squareFab by BooleanPref(true)
    @JvmStatic var sectionRadius by FloatPref(20f)
    @JvmStatic var sectionsSeparatedHeadersPreference by BooleanPref(true, "sectionsSeparatedHeaders")
    @JvmStatic var newLoadingStyle by BooleanPref(true)
    @JvmStatic var newSliderStyle by BooleanPref(true)
    @JvmStatic var newSwitchStyle by BooleanPref(true)
    @JvmStatic var newChatHeaderStyle by BooleanPref(false)
    @JvmStatic var newNavigationBarStyle by BooleanPref(false)
    @JvmStatic var newFabStyle by BooleanPref(false)
    @JvmStatic var tabletMode by IntegerPref(0)
    @JvmStatic var useSystemFonts by BooleanPref(true)
    @JvmStatic var gooeyAvatarAnimation by BooleanPref(true)
    @JvmStatic var customThemes by BooleanPref(true)
    @JvmStatic var predictiveBackIntensity by FloatPref(1f)
    @JvmStatic var transitionAnimation by EnumPref(TransitionAnimation.SPRING)
    @JvmStatic var glassOutlineStyle by EnumPref(GlassOutlineStyle.GLARE)
    @JvmStatic var glassMessageMenu by BooleanPref(true)
    @JvmStatic var forceBlur by BooleanPref(false)
    @JvmStatic var eventType by IntegerPref(0)
    @JvmStatic var navigationDrawer by BooleanPref(false)
    @JvmStatic var immersiveDrawerAnimation by BooleanPref(false)
    @JvmStatic var showFeedTab by BooleanPref(false)
    @JvmStatic var showFeedUnreadCounter by BooleanPref(true)

    @JvmStatic
    var mainMenuLayout = ArrayList<Int>()
        private set

    @JvmStatic
    var mainMenuHiddenItems = ArrayList<Int>()
        private set

    @JvmStatic
    var sectionsSeparatedHeaders: Boolean
        get() = dividerStyle == DividerStyle.SEGMENTS || sectionsSeparatedHeadersPreference
        set(value) {
            sectionsSeparatedHeadersPreference = value || dividerStyle == DividerStyle.SEGMENTS
        }

    @JvmStatic
    val springAnimations: Boolean
        get() = transitionAnimation == TransitionAnimation.SPRING

    @JvmStatic
    val aospTransitions: Boolean
        get() = transitionAnimation == TransitionAnimation.AOSP

    @JvmStatic
    val springSwipeback: Boolean
        get() = transitionAnimation != TransitionAnimation.DEFAULT

    // Chats
    @JvmStatic var stickerSize by FloatPref(12f)
    @JvmStatic var stickerTimeMode by EnumPref(StickerTimeMode.DEFAULT)
    @JvmStatic var replyColors by BooleanPref(true)
    @JvmStatic var replyEmoji by BooleanPref(true)
    @JvmStatic var replyBackground by BooleanPref(true)
    @JvmStatic var stickerShape by IntegerPref(1)
    @JvmStatic var unlimitedRecentStickers by BooleanPref(false)
    @JvmStatic var hideReactionsInPrivateChats by BooleanPref(false)
    @JvmStatic var hideReactionsInChannels by BooleanPref(false)
    @JvmStatic var hideReactionsInGroups by BooleanPref(false)
    @JvmStatic var doubleTapAction by SanitizedIntegerPref(1)
    @JvmStatic var doubleTapActionOutOwner by SanitizedIntegerPref(1)
    @JvmStatic var swipeActions by StringPref("2")
    @JvmStatic var swipeActionsLoop by BooleanPref(false)
    @JvmStatic var swipeActionsReversed by BooleanPref(false)
    @JvmStatic var bottomButton by IntegerPref(2)
    @JvmStatic var widePostsInFeed by BooleanPref(true)
    @JvmStatic var widePostsInChannels by BooleanPref(false)
    @JvmStatic var telegramAiEditor by BooleanPref(true)
    @JvmStatic var telegramAiSummaries by BooleanPref(true)
    @JvmStatic var telegramAiInstantViewSummaries by BooleanPref(true)
    @JvmStatic var quickAdminShortcuts by BooleanPref(true)
    @JvmStatic var quickTransitionForChannels by BooleanPref(true)
    @JvmStatic var quickTransitionForTopics by BooleanPref(true)
    @JvmStatic var disableGreetingSticker by BooleanPref(false)
    @JvmStatic var hideKeyboardOnScroll by BooleanPref(true)
    @JvmStatic var addCommaAfterMention by BooleanPref(true)
    @JvmStatic var inlineMathResult by BooleanPref(true)
    @JvmStatic var disableMarkdown by BooleanPref(false)
    @JvmStatic var hideSendAsPeer by BooleanPref(false)
    @JvmStatic var removeMessageTail by BooleanPref(true)
    @JvmStatic var replaceEditedWithIcon by BooleanPref(true)
    @JvmStatic var showOnlineStatus by BooleanPref(false)
    @JvmStatic var showForwardsCount by BooleanPref(false)
    @JvmStatic var hideShareButton by BooleanPref(true)
    @JvmStatic var showResultsBeforeVoting by BooleanPref(false)
    @JvmStatic var showCopyPhotoButton by BooleanPref(true)
    @JvmStatic var showSaveMessageButton by BooleanPref(false)
    @JvmStatic var showRepeatMessageButton by BooleanPref(false)
    @JvmStatic var showClearButton by BooleanPref(true)
    @JvmStatic var showHistoryButton by BooleanPref(false)
    @JvmStatic var showReportButton by BooleanPref(true)
    @JvmStatic var showGenerateButton by BooleanPref(true)
    @JvmStatic var showDetailsButton by BooleanPref(false)
    @JvmStatic var groupMessageMenu by BooleanPref(true)
    @JvmStatic var recognitionLanguage by StringPref("none")
    @JvmStatic var postprocessingWithAi by BooleanPref(false)

    // Camera & media
    @JvmStatic var cameraType by EnumPref(
        if (SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_HIGH) CameraType.CAMERA_X else CameraType.CAMERA_1
    )
    @JvmStatic var extendedFramesPerSecond by BooleanPref(false)
    @JvmStatic var cameraStabilization by BooleanPref(false)
    @JvmStatic var cameraMirrorMode by BooleanPref(true)
    @JvmStatic var videoMessagesCamera by EnumPref(VideoMessagesCamera.FRONT)
    @JvmStatic var rememberLastUsedCamera by BooleanPref(false)
    @JvmStatic var startWithWideAngleCamera by BooleanPref(false)
    @JvmStatic var zoomSlider by BooleanPref(true)
    @JvmStatic var staticZoom by BooleanPref(false)
    @JvmStatic var alwaysSendInHD by BooleanPref(true)
    @JvmStatic var hideCameraTile by BooleanPref(false)
    @JvmStatic var doubleTapSeekDuration by IntegerPref(1)
    @JvmStatic var preferOriginalQuality by BooleanPref(false)
    @JvmStatic var swipeToPip by BooleanPref(false)
    @JvmStatic var unmuteWithVolumeButtons by BooleanPref(false)
    @JvmStatic var pauseOnMinimizeVideo by BooleanPref(true)
    @JvmStatic var pauseOnMinimizeVoice by BooleanPref(false)
    @JvmStatic var pauseOnMinimizeRound by BooleanPref(false)

    // Other
    // OpenExtera ships without Firebase Analytics/Crashlytics: these keys are kept only so
    // that the settings screen and backups stay compatible, they are never applied anywhere.
    @JvmStatic var useGoogleCrashlytics by BooleanPref(false)
    @JvmStatic var useGoogleAnalytics by BooleanPref(false)
    @JvmStatic var enableAdBlock by BooleanPref(true)
    @JvmStatic var updateScheduleTimestamp by LongPref(0L)
    @JvmStatic var sdkUpdateScheduleTimestamp by LongPref(0L)
    @JvmStatic var targetLang by StringPref("app")
    @JvmStatic var flashWarmth by FloatPref(0.5f)
    @JvmStatic var flashIntensity by FloatPref(1f)

    @JvmStatic
    val yandexSearchEngine = SearchEngine(
        "Yandex",
        "https://mini.ya.ru/",
        "https://ya.ru/search/?text=",
        "https://suggestqueries.google.com/complete/search?client=chrome&q=",
        "https://yandex.ru/legal/confidential"
    )

    // Plugins (not available in lite, kept for settings/backup compatibility)
    @JvmStatic var pluginsDevMode by BooleanPref(false)
    @JvmStatic var pluginsSafeMode by BooleanPref(false)
    @JvmStatic var pluginsCompactView by BooleanPref(false)
    @JvmStatic var pluginsPySdkAutoUpdate by BooleanPref(false)
    @JvmStatic var pluginsPySdkBetaVersions by BooleanPref(false)
    @JvmStatic var pluginsDisableArtOpts by BooleanPref(false)
    @JvmStatic var pluginsUnknownSources by BooleanPref(false)
    @JvmStatic var pinnedPlugins by StringSetPref(emptySet())
    @JvmStatic var useSystemIconShape by BooleanPref(true)

    @JvmStatic
    var pluginsEngine = false
        private set

    @JvmStatic
    val backupKeys: Array<PreferencesUtils.BackupItem>
        get() = registeredKeys.toTypedArray()

    @JvmStatic
    val currentLangName: String
        get() = TranslatorUtils.getTargetLanguageTitle()

    @JvmStatic
    val doubleTapSeekDurationMillis: Int
        get() = when (doubleTapSeekDuration) {
            0, 1, 2 -> (doubleTapSeekDuration + 1) * 5000
            else -> 30000
        }

    @JvmStatic
    var logging: Boolean
        get() = systemConfigPrefs().getBoolean("logsEnabled", false)
        set(value) {
            BuildVars.LOGS_ENABLED = value
            systemConfigPrefs().edit().putBoolean("logsEnabled", value).apply()
            if (!value) {
                FileLog.cleanupLogs()
            }
        }

    @JvmStatic
    fun init() {
        loadConfig()
        if (servicesStarted.compareAndSet(false, true)) {
            ApiController.init()
            // TODO(openextera): disabled, exteraSquad infrastructure (remote-config channel fetching)
            // RemoteUtils.init()
            PluginsController.getInstance().init(pluginsSafeMode) {
                val controller = PluginsController.getInstance()
                controller.executeOnAppEvent("app_start")
                if (!ApplicationLoader.mainInterfacePaused) {
                    controller.executeOnAppEvent("app_resume")
                }
            }
            AdBlockManager.preload()
            IconManager.prefetchCustomPacks()
        }
    }

    @JvmStatic
    fun getApiBotInfo(): Pair<Long, String> {
        currentApiBot?.let { return it }
        synchronized(sync) {
            currentApiBot?.let { return it }
            ensureApiBotListener()
            val value = RemoteUtils.getStringConfigValue(API_BOT_KEY, "$DEFAULT_API_BOT_ID:$DEFAULT_API_BOT_USERNAME")
            if (value != null) {
                try {
                    val parts = value.split(":", limit = 2)
                    if (parts.size == 2) {
                        return Pair(parts[0].toLong(), parts[1]).also { currentApiBot = it }
                    }
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
            return Pair(DEFAULT_API_BOT_ID, DEFAULT_API_BOT_USERNAME).also { currentApiBot = it }
        }
    }

    private fun ensureApiBotListener() {
        if (apiBotListener != null) {
            return
        }
        try {
            RemoteUtils.initCached()
            val remotePreferences = RemoteUtils.sharedPreferences ?: return
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == API_BOT_KEY) {
                    currentApiBot = null
                }
            }
            remotePreferences.registerOnSharedPreferenceChangeListener(listener)
            apiBotListener = listener
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun isProxyDisabledOn(condition: ProxyDisableCondition): Boolean =
        (condition.flag and doNotUseProxy) != 0

    @JvmStatic
    fun setProxyDisabledOn(condition: ProxyDisableCondition, disabled: Boolean) {
        doNotUseProxy = if (disabled) {
            doNotUseProxy or condition.flag
        } else {
            doNotUseProxy and condition.flag.inv()
        }
    }

    @JvmStatic
    fun loadConfig() {
        synchronized(sync) {
            if (configLoaded) {
                return
            }
            BottomNavigationBar.mode = preferences.getInt("bottomNavigationBarMode", 0)

            loadIconPacksLayout()

            doNotMarkAsNew = preferences.getString("doNotMarkAsNew", null)?.let {
                GSON.fromJson<ArrayList<String>>(it, object : TypeToken<ArrayList<String>>() {}.type)
            } ?: ArrayList()
            newFeaturesShowedAt = preferences.getString("newFeaturesShowedAt", null)?.let {
                GSON.fromJson<HashMap<String, Long>>(it, object : TypeToken<HashMap<String, Long>>() {}.type)
            } ?: HashMap()

            loadMainMenuLayout()

            migrateProxyConditions()
            migrateTelegramAiInstantViewSummaries()
            migrateStickerTimeMode()
            migrateTransitionAnimation()
            migrateTabCounterMode()
            TranslatorUtils.ensureTargetLanguageCompatibleWithProvider()
            configLoaded = true
        }
    }

    private fun loadIconPacksLayout() {
        val layoutJson = preferences.getString("iconPacksLayout", null)
        val hiddenJson = preferences.getString("iconPacksHidden", null)
        if (layoutJson != null) {
            val type = object : TypeToken<ArrayList<String>>() {}.type
            iconPacksLayout = GSON.fromJson(layoutJson, type)
            iconPacksHidden = if (hiddenJson != null) GSON.fromJson(hiddenJson, type) else ArrayList()

            // only one base pack may be active at a time
            var changed = false
            var basePack: String? = null
            val iterator = iconPacksLayout.iterator()
            while (iterator.hasNext()) {
                val pack = iterator.next()
                if (pack.startsWith("base.")) {
                    if (basePack == null) {
                        basePack = pack
                    } else {
                        iterator.remove()
                        changed = true
                    }
                }
            }
            if (basePack == null) {
                iconPacksLayout.add("base.default")
                changed = true
            }
            if (changed) {
                saveIconPacksLayout()
            }
        } else {
            iconPacksLayout = ArrayList()
            iconPacksHidden = ArrayList()
            BASE_ICON_PACKS.forEachIndexed { index, pack ->
                (if (index == iconPack.ordinal) iconPacksLayout else iconPacksHidden).add(pack)
            }
            saveIconPacksLayout()
        }

        var changed = false
        for (pack in BASE_ICON_PACKS) {
            if (!iconPacksLayout.contains(pack) && !iconPacksHidden.contains(pack)) {
                iconPacksHidden.add(pack)
                changed = true
            }
        }
        if (changed) {
            saveIconPacksLayout()
        }
    }

    private fun loadMainMenuLayout() {
        val layoutJson = preferences.getString("mainMenuLayout", null)
        val hiddenJson = preferences.getString("mainMenuHiddenItems", null)
        if (layoutJson != null) {
            val type = object : TypeToken<ArrayList<Int>>() {}.type
            mainMenuLayout = GSON.fromJson(layoutJson, type)
            mainMenuHiddenItems = if (hiddenJson != null) GSON.fromJson(hiddenJson, type) else ArrayList()
        } else {
            mainMenuLayout = ArrayList()
            mainMenuHiddenItems = ArrayList()
            mainMenuLayout.addAll(getDefaultMainMenuLayout())
            for (item in MainMenuItem.entries) {
                if (item != MainMenuItem.DIVIDER && !mainMenuLayout.contains(item.id) &&
                    (item != MainMenuItem.PLUGINS || PluginsController.isPluginEngineSupported())
                ) {
                    mainMenuHiddenItems.add(item.id)
                }
            }
            saveMainMenuLayout()
        }

        val pluginsId = MainMenuItem.PLUGINS.id
        if (!PluginsController.isPluginEngineSupported()) {
            pluginsEngine = false
            if (preferences.getBoolean("pluginsEngine", false)) {
                editor.putBoolean("pluginsEngine", false).apply()
            }
            mainMenuLayout.remove(pluginsId)
            mainMenuHiddenItems.remove(pluginsId)
        } else {
            pluginsEngine = preferences.getBoolean("pluginsEngine", false)
            if (!mainMenuLayout.contains(pluginsId) && !mainMenuHiddenItems.contains(pluginsId)) {
                mainMenuHiddenItems.add(pluginsId)
            }
        }

        val feedId = MainMenuItem.FEED.id
        if (!mainMenuLayout.contains(feedId) && !mainMenuHiddenItems.contains(feedId)) {
            mainMenuLayout.add(feedId)
            saveMainMenuLayout()
        }
        mainMenuLayout.removeAll(mainMenuHiddenItems.toSet())
        ensureSettingsVisibility()
        sanitizeMenu()
    }

    @JvmStatic
    fun reloadConfig() {
        synchronized(sync) {
            configLoaded = false
            allDelegates.forEach { it.invalidate() }
            loadConfig()
        }
    }

    @JvmStatic
    fun saveMainMenuLayout() {
        editor.putString("mainMenuLayout", GSON.toJson(mainMenuLayout))
            .putString("mainMenuHiddenItems", GSON.toJson(mainMenuHiddenItems))
            .apply()
    }

    @JvmStatic
    fun saveIconPacksLayout() {
        iconPack = when (iconPacksLayout.firstOrNull { it.startsWith("base.") }) {
            "base.solar" -> IconPackType.SOLAR
            "base.remix" -> IconPackType.REMIX
            else -> IconPackType.DEFAULT
        }
        editor.putString("iconPacksLayout", GSON.toJson(iconPacksLayout))
            .putString("iconPacksHidden", GSON.toJson(iconPacksHidden))
            .apply()
    }

    @JvmStatic
    fun getDefaultMainMenuLayout(): ArrayList<Int> {
        val layout = ArrayList<Int>()
        layout.add(MainMenuItem.ARCHIVE.id)
        layout.addIf(BottomNavigationBar.hidden(), MainMenuItem.PROFILE.id)
        layout.add(MainMenuItem.NEW_GROUP.id)
        layout.addIf(BottomNavigationBar.hidden(), MainMenuItem.CONTACTS.id)
        layout.add(MainMenuItem.SAVED.id)
        layout.add(MainMenuItem.FEED.id)
        layout.add(MainMenuItem.BOTS.id)
        layout.addIf(BottomNavigationBar.hidden(), MainMenuItem.SETTINGS.id)
        return layout
    }

    @JvmStatic
    fun ensureSettingsVisibility() {
        val settingsId = MainMenuItem.SETTINGS.id
        if (BottomNavigationBar.hidden() && !mainMenuLayout.contains(settingsId)) {
            mainMenuHiddenItems.remove(settingsId)
            mainMenuLayout.add(settingsId)
            saveMainMenuLayout()
        }
    }

    @JvmStatic
    fun sanitizeMenu() {
        val isUnknown = { id: Int -> id != MainMenuItem.DIVIDER.id && MainMenuItem.getById(id) == null }
        var changed = mainMenuLayout.removeIf { isUnknown(it) }
        changed = mainMenuHiddenItems.removeIf { isUnknown(it) } or changed
        for (item in MainMenuItem.entries) {
            if (item == MainMenuItem.DIVIDER || (item == MainMenuItem.PLUGINS && !PluginsController.isPluginEngineSupported())) {
                continue
            }
            if (!mainMenuLayout.contains(item.id) && !mainMenuHiddenItems.contains(item.id)) {
                mainMenuHiddenItems.add(item.id)
                changed = true
            }
        }
        if (changed) {
            saveMainMenuLayout()
        }
    }

    private fun migrateProxyConditions() {
        if (preferences.contains("doNotUseProxyWithVpn")) {
            if (preferences.getBoolean("doNotUseProxyWithVpn", false)) {
                setProxyDisabledOn(ProxyDisableCondition.VPN, true)
            }
            editor.remove("doNotUseProxyWithVpn").apply()
        }
    }

    private fun migrateTelegramAiInstantViewSummaries() {
        if (!preferences.contains("telegramAiInstantViewSummaries")) {
            telegramAiInstantViewSummaries = telegramAiSummaries
        }
    }

    private fun migrateTransitionAnimation() {
        if (preferences.contains("springAnimations") || preferences.contains("aospTransitions")) {
            transitionAnimation = when {
                preferences.getBoolean("aospTransitions", false) -> TransitionAnimation.AOSP
                preferences.getBoolean("springAnimations", true) -> TransitionAnimation.SPRING
                else -> TransitionAnimation.DEFAULT
            }
            editor.remove("springAnimations").remove("aospTransitions").apply()
        }
    }

    private fun migrateTabCounterMode() {
        if (preferences.contains("tabCounter")) {
            if (!preferences.getBoolean("tabCounter", true)) {
                tabCounterMode = TabCounterMode.HIDDEN
            }
            editor.remove("tabCounter").apply()
        }
    }

    private fun migrateStickerTimeMode() {
        if (preferences.contains("hideStickerTime")) {
            if (preferences.getBoolean("hideStickerTime", false)) {
                stickerTimeMode = StickerTimeMode.HIDDEN
            }
            editor.remove("hideStickerTime").apply()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getAvatarCorners(size: Float, inPixels: Boolean = false, forum: Boolean = false): Int =
        getAvatarCorners(size, inPixels, if (forum) AvatarCornerType.FORUM else AvatarCornerType.DEFAULT)

    @JvmStatic
    fun getAvatarCorners(size: Float, inPixels: Boolean, type: AvatarCornerType): Int {
        if (avatarCorners == 0f) {
            return 0
        }
        var radius = avatarCorners * size / 56f
        if (!inPixels) {
            radius = AndroidUtilities.dp(radius).toFloat()
        }
        if (!singleCornerRadius) {
            radius = when (type) {
                AvatarCornerType.DEFAULT -> radius
                AvatarCornerType.FORUM -> ((radius.toInt() * 42) shr 6).toFloat()
                AvatarCornerType.COMMUNITY -> radius * 40f / 72f
            }
        }
        return ceil(radius).toInt().coerceAtLeast(0)
    }

    @JvmStatic
    val avatarSquareness: Float
        get() = (1f - avatarCorners / 28f).coerceIn(0f, 1f)

    @JvmStatic
    val onlineDotOuterRadius: Int
        get() = AndroidUtilities.dp(avatarSquareness * 2f + 7f)

    @JvmStatic
    val onlineDotInnerRadius: Int
        get() = AndroidUtilities.dp(avatarSquareness + 5f)

    @JvmStatic
    fun getOnlineDotOffset(circleOffset: Float, squareOffset: Float): Float =
        circleOffset + ((squareOffset / sqrt(2.0)).toFloat() - circleOffset) * avatarSquareness

    @JvmStatic
    val sectionRadiusDp: Int
        get() = sanitizeSectionRadius(sectionRadius).roundToInt()

    private fun sanitizeSectionRadius(radius: Float): Float =
        if (radius.isFinite()) radius.coerceIn(0f, 28f) else 20f

    private fun systemConfigPrefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", 0)

    @JvmStatic
    fun toggleLogging() {
        logging = !BuildVars.LOGS_ENABLED
    }
}
