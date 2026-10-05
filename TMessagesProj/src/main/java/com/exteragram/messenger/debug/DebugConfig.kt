package com.exteragram.messenger.debug

import com.exteragram.messenger.config.BooleanPref
import com.exteragram.messenger.config.IntegerPref

object DebugConfig {
    @JvmStatic var debugCameraMetrics by BooleanPref(false)
    @JvmStatic var forceCompactSavedMusic by BooleanPref(false)
    @JvmStatic var disableApiRequests by BooleanPref(false)
    @JvmStatic var disableChatFadeWallpaperBlend by BooleanPref(false)
    @JvmStatic var chatFadeUseWhiteBackground by BooleanPref(false)
    @JvmStatic var heapMonitorEnabled by BooleanPref(false)
    @JvmStatic var heapMonitorLimitMb by IntegerPref(256)
    @JvmStatic var loadMonitorEnabled by BooleanPref(false)
    @JvmStatic var loadMonitorCpuPercent by IntegerPref(5)
    @JvmStatic var freezeMonitorEnabled by BooleanPref(false)
    @JvmStatic var freezeMonitorThresholdMs by IntegerPref(700)
}
