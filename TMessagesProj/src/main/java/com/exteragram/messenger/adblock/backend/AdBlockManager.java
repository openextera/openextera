package com.exteragram.messenger.adblock.backend;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.adblock.interop.AdBlock;
import com.exteragram.messenger.adblock.interop.NativeAdBlock;
import com.exteragram.messenger.utils.network.RemoteUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;

import java.util.ArrayList;

public abstract class AdBlockManager {

    private static final String[] FILTERS = {
            "https://ublockorigin.github.io/uAssetsCDN/filters/filters.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/badware.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/privacy.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/unbreak.min.txt",
            "https://ublockorigin.github.io/uAssetsCDN/filters/quick-fixes.min.txt",
            "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
            "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/thirdparties/easylist.txt",
            "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/thirdparties/easyprivacy.txt",
            "https://cdn.jsdelivr.net/gh/dimisa-RUAdList/RUAdListCDN@main/lists/ruadlist.ubo.min.txt"
    };

    private static final long PRELOAD_DELAY = 10000;

    private static boolean loading;
    private static DispatchQueue queue;
    private static final ArrayList<Runnable> readyCallbacks = new ArrayList<>();

    private static synchronized DispatchQueue getQueue() {
        if (queue == null) {
            queue = new DispatchQueue("AdBlockManager");
        }
        return queue;
    }

    public static boolean isAvailable() {
        return RemoteUtils.getBooleanConfigValue("use_adblock", false) && !NativeAdBlock.isLoadFailed();
    }

    public static boolean isActive() {
        return ExteraConfig.getEnableAdBlock() && AdBlock.isReady();
    }

    public static void initialize() {
        if (ExteraConfig.getEnableAdBlock()) {
            getQueue().postRunnable(() -> load(null));
        }
    }

    public static void preload() {
        if (ExteraConfig.getEnableAdBlock()) {
            getQueue().postRunnable(() -> load(null), PRELOAD_DELAY);
        }
    }

    public static void setEnabled(boolean enabled, Runnable onReady) {
        ExteraConfig.setEnableAdBlock(enabled);
        getQueue().postRunnable(() -> {
            if (enabled) {
                load(onReady);
            } else {
                readyCallbacks.clear();
                AdBlock.destroy();
            }
        });
    }

    private static void load(Runnable onReady) {
        if (!ExteraConfig.getEnableAdBlock()) {
            return;
        }
        if (AdBlock.isReady()) {
            if (onReady != null) {
                AndroidUtilities.runOnUIThread(onReady);
            }
            return;
        }
        if (!RemoteUtils.getBooleanConfigValue("use_adblock", false) || !NativeAdBlock.loadLibraries()) {
            return;
        }
        if (onReady != null) {
            readyCallbacks.add(onReady);
        }
        if (loading) {
            return;
        }
        loading = true;
        ScriptletsManager scriptletsManager = ScriptletsManager.getInstance();
        if (!scriptletsManager.isDownloaded()) {
            scriptletsManager.download(success -> getQueue().postRunnable(AdBlock::applyResources));
        }
        SubscriptionsManager subscriptionsManager = SubscriptionsManager.getInstance();
        if (subscriptionsManager.hasFilters()) {
            build();
            subscriptionsManager.update(FILTERS, null);
        } else {
            subscriptionsManager.update(FILTERS, () -> getQueue().postRunnable(AdBlockManager::build));
        }
    }

    private static void build() {
        loading = false;
        if (ExteraConfig.getEnableAdBlock()) {
            AdBlock.build();
        }
        if (readyCallbacks.isEmpty()) {
            return;
        }
        ArrayList<Runnable> callbacks = new ArrayList<>(readyCallbacks);
        readyCallbacks.clear();
        if (AdBlock.isReady()) {
            AndroidUtilities.runOnUIThread(() -> {
                for (int i = 0; i < callbacks.size(); i++) {
                    callbacks.get(i).run();
                }
            });
        }
    }
}
