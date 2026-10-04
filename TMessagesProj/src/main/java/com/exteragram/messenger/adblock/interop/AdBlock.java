package com.exteragram.messenger.adblock.interop;

import com.exteragram.messenger.adblock.backend.ScriptletsManager;
import com.exteragram.messenger.adblock.backend.SubscriptionsManager;
import com.exteragram.messenger.adblock.data.BlockResult;
import com.exteragram.messenger.adblock.data.UrlCosmeticResources;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class AdBlock {

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static volatile long enginePtr = 0;

    public static boolean isReady() {
        return enginePtr != 0;
    }

    public static void build() {
        List<String> paths = SubscriptionsManager.getInstance().getSubscriptionFilePaths();
        if (paths.isEmpty()) {
            return;
        }
        long filterSet = NativeAdBlock.createFilterSet(new String[0]);
        for (String path : paths) {
            NativeAdBlock.addFilters(filterSet, path);
        }
        long engine = NativeAdBlock.createEngine(filterSet);
        useResources(engine, ScriptletsManager.getInstance().iterScriptlets());
        swap(engine);
    }

    public static void destroy() {
        swap(0L);
    }

    public static void applyResources() {
        Collection<ScriptletsManager.Scriptlet> scriptlets = ScriptletsManager.getInstance().iterScriptlets();
        lock.writeLock().lock();
        try {
            if (enginePtr != 0) {
                useResources(enginePtr, scriptlets);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void swap(long engine) {
        lock.writeLock().lock();
        long oldEngine;
        try {
            oldEngine = enginePtr;
            enginePtr = engine;
        } finally {
            lock.writeLock().unlock();
        }
        if (oldEngine != 0) {
            NativeAdBlock.destroyEngine(oldEngine);
        }
    }

    private static void useResources(long engine, Collection<ScriptletsManager.Scriptlet> scriptlets) {
        int size = scriptlets.size();
        String[] names = new String[size];
        String[][] aliases = new String[size][];
        String[] kinds = new String[size];
        String[] contents = new String[size];
        int i = 0;
        for (ScriptletsManager.Scriptlet scriptlet : scriptlets) {
            names[i] = scriptlet.filename;
            List<String> scriptletAliases = scriptlet.aliases;
            aliases[i] = scriptletAliases != null ? scriptletAliases.toArray(new String[0]) : new String[0];
            kinds[i] = ScriptletsManager.getExtension(scriptlet.filename);
            contents[i] = scriptlet.content;
            i++;
        }
        NativeAdBlock.useResources(engine, names, aliases, kinds, contents);
    }

    public static BlockResult getBlockResult(String url, String sourceUrl, String resourceType) {
        lock.readLock().lock();
        try {
            return enginePtr != 0 ? NativeAdBlock.shouldBlock(enginePtr, url, sourceUrl, resourceType) : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public static UrlCosmeticResources getCosmeticResources(String url) {
        lock.writeLock().lock();
        try {
            return enginePtr != 0 ? NativeAdBlock.getCosmeticResources(enginePtr, url) : null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static String[] getHiddenSelectors(String[] classes, String[] ids, String[] exceptions) {
        lock.writeLock().lock();
        try {
            return enginePtr != 0 ? NativeAdBlock.getHiddenSelectors(enginePtr, classes, ids, exceptions) : null;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
