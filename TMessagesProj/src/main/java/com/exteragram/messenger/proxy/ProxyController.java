package com.exteragram.messenger.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.utils.proxy.ProxySettings;

import java.io.UnsupportedEncodingException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProxyController {

    private static final int MAX_PINNED_PROXIES = 10;

    private static final int SCHEMA_V2 = 2;
    private static final int SCHEMA_WITH_NAMES = 3;
    private static final int SCHEMA_V4 = 4;
    private static final int SCHEMA_WITH_WEB = 5;

    public static final int PIN_ACTION_NONE = 0;
    public static final int PIN_ACTION_PIN = 1;
    public static final int PIN_ACTION_UNPIN = 2;

    private static final ProxyController INSTANCE = new ProxyController();

    private SharedConfig.ProxyInfo currentProxy;
    private boolean loaded;
    private final ArrayList<SharedConfig.ProxyInfo> proxyList = new ArrayList<>();
    private final HashSet<String> pinnedProxies = new HashSet<>();
    private final ArrayList<String> pinnedProxyOrder = new ArrayList<>();
    private final HashMap<String, String> proxyNames = new HashMap<>();

    public enum PinOperationResult {
        NO_CHANGE,
        CHANGED,
        LIMIT_REACHED
    }

    public interface ProxyCountryCallback {
        void onCountryResolved(String country);
    }

    public static ProxyController getInstance() {
        return INSTANCE;
    }

    private ProxyController() {
    }

    public int getMaxPinnedProxies() {
        return MAX_PINNED_PROXIES;
    }

    public synchronized ArrayList<SharedConfig.ProxyInfo> getProxyList() {
        ensureLoaded();
        return new ArrayList<>(proxyList);
    }

    public synchronized SharedConfig.ProxyInfo getCurrentProxy() {
        ensureLoaded();
        return currentProxy;
    }

    public synchronized void setCurrentProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        currentProxy = proxyInfo;
        SharedConfig.currentProxy = proxyInfo;
    }

    public synchronized void loadProxyList() {
        ensureLoaded();
    }

    public synchronized void saveProxyList() {
        ensureLoaded();
        ArrayList<SharedConfig.ProxyInfo> sorted = new ArrayList<>(proxyList);
        Collections.sort(sorted, (o1, o2) -> Long.compare(o1.ping + sortBias(o1, currentProxy), o2.ping + sortBias(o2, currentProxy)));
        SerializedData data = new SerializedData();
        data.writeInt32(-1);
        data.writeByte(SCHEMA_WITH_WEB);
        int count = sorted.size();
        data.writeInt32(count);
        for (int a = count - 1; a >= 0; a--) {
            SharedConfig.ProxyInfo info = sorted.get(a);
            data.writeString(info.settings.getAddress());
            data.writeInt32(info.settings.getPort());
            data.writeString(info.settings.getUser());
            data.writeString(info.settings.getPassword());
            data.writeString(info.settings.getSecret());
            data.writeInt64(info.ping);
            data.writeInt64(info.availableCheckTime);
            data.writeBool(info.settings.getType() == ProxySettings.Type.WEB);
        }
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit()
                .putString("proxy_list", Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP))
                .apply();
        data.cleanup();
    }

    private static long sortBias(SharedConfig.ProxyInfo info, SharedConfig.ProxyInfo current) {
        long bias = current == info ? -200000 : 0;
        if (!info.available) {
            bias += 100000;
        }
        return bias;
    }

    public synchronized SharedConfig.ProxyInfo addProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        for (int a = 0, size = proxyList.size(); a < size; a++) {
            SharedConfig.ProxyInfo info = proxyList.get(a);
            if (proxyInfo.settings.equals(info.settings)) {
                return info;
            }
        }
        proxyList.add(0, proxyInfo);
        syncProxyList();
        saveProxyList();
        return proxyInfo;
    }

    public synchronized SharedConfig.ProxyInfo saveProxy(SharedConfig.ProxyInfo proxyInfo, String oldLink, String name) {
        ensureLoaded();
        if (!TextUtils.isEmpty(oldLink) && !TextUtils.equals(oldLink, proxyInfo.settings.getLink())) {
            moveMetadata(oldLink, proxyInfo.settings.getLink());
        }
        SharedConfig.ProxyInfo saved = addProxy(proxyInfo);
        if (name != null) {
            setName(saved, name);
        }
        return saved;
    }

    public synchronized String buildShareLink(SharedConfig.ProxyInfo proxyInfo) {
        return buildShareLink(proxyInfo, null);
    }

    public synchronized String buildShareLink(SharedConfig.ProxyInfo proxyInfo, String title) {
        if (proxyInfo == null) {
            return "";
        }
        String link = proxyInfo.settings.getLink();
        String name = normalizeName(title);
        if (TextUtils.isEmpty(name)) {
            name = getName(proxyInfo);
        }
        if (TextUtils.isEmpty(name)) {
            return link;
        }
        try {
            return link + "&title=" + URLEncoder.encode(name, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return link;
        }
    }

    public synchronized void deleteProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        String key = getProxyKey(proxyInfo);
        if (currentProxy == proxyInfo || (key != null && key.equals(getProxyKey(currentProxy)))) {
            currentProxy = null;
            SharedConfig.currentProxy = null;
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            boolean enabled = preferences.getBoolean("proxy_enabled", false);
            SharedPreferences.Editor editor = preferences.edit();
            ProxySettings.EMPTY.toSharedPreferences(editor);
            editor.putBoolean("proxy_enabled", false);
            editor.apply();
            if (enabled) {
                ConnectionsManager.setProxySettings(false, null);
            }
        }
        proxyList.remove(proxyInfo);
        removeProxy(proxyInfo);
        syncProxyList();
        saveProxyList();
    }

    public synchronized String getDisplayName(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return "";
        }
        String name = getName(proxyInfo);
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        if (proxyInfo.settings.getType() == ProxySettings.Type.WEB) {
            return proxyInfo.settings.getAddress();
        }
        return proxyInfo.settings.getAddress() + ":" + proxyInfo.settings.getPort();
    }

    public synchronized String getProxyTypeName(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo != null && proxyInfo.settings.getType() == ProxySettings.Type.WEB) {
            return LocaleController.getString(R.string.UseProxyWeb);
        }
        return LocaleController.getString(isTelegramProxy(proxyInfo) ? R.string.UseProxyTelegram : R.string.UseProxySocks5);
    }

    public void requestProxyCountry(SharedConfig.ProxyInfo proxyInfo, ProxyCountryCallback callback) {
        if (callback == null) {
            return;
        }
        String unknown = LocaleController.getString(R.string.Unknown);
        String host = getProxyCountryKey(proxyInfo);
        if (TextUtils.isEmpty(host)) {
            AndroidUtilities.runOnUIThread(() -> callback.onCountryResolved(unknown));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String ip = resolveProxyIpAddress(host);
            if (TextUtils.isEmpty(ip)) {
                AndroidUtilities.runOnUIThread(() -> callback.onCountryResolved(unknown));
                return;
            }
            IpAddressInfoController.requestIpAddressInfo(ip, info -> {
                String country = info != null ? getCountryDisplayName(info.countryCode) : unknown;
                AndroidUtilities.runOnUIThread(() -> callback.onCountryResolved(country));
            });
        });
    }

    public synchronized String getName(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return "";
        }
        return getName(getProxyKey(proxyInfo));
    }

    public synchronized String getName(String key) {
        ensureLoaded();
        if (TextUtils.isEmpty(key)) {
            return "";
        }
        String name = proxyNames.get(key);
        return name == null ? "" : name;
    }

    public synchronized void setName(SharedConfig.ProxyInfo proxyInfo, String name) {
        setName(getProxyKey(proxyInfo), name);
    }

    public synchronized void setName(String key, String name) {
        ensureLoaded();
        if (TextUtils.isEmpty(key)) {
            return;
        }
        String normalized = normalizeName(name);
        boolean changed;
        if (TextUtils.isEmpty(normalized)) {
            changed = proxyNames.remove(key) != null;
        } else {
            changed = !TextUtils.equals(proxyNames.put(key, normalized), normalized);
        }
        if (changed) {
            save();
        }
    }

    public synchronized void moveMetadata(String fromKey, String toKey) {
        ensureLoaded();
        if (TextUtils.isEmpty(fromKey) || TextUtils.isEmpty(toKey) || TextUtils.equals(fromKey, toKey)) {
            return;
        }
        boolean changed = false;
        String name = proxyNames.remove(fromKey);
        if (!TextUtils.isEmpty(name)) {
            proxyNames.put(toKey, name);
            changed = true;
        }
        int index = pinnedProxyOrder.indexOf(fromKey);
        if (index >= 0) {
            pinnedProxyOrder.remove(index);
            pinnedProxies.remove(fromKey);
            if (!pinnedProxyOrder.contains(toKey)) {
                pinnedProxyOrder.add(Math.min(index, pinnedProxyOrder.size()), toKey);
            }
            pinnedProxies.add(toKey);
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    public synchronized boolean isPinned(SharedConfig.ProxyInfo proxyInfo) {
        return proxyInfo != null && isPinned(getProxyKey(proxyInfo));
    }

    public synchronized boolean isPinned(String key) {
        ensureLoaded();
        return !TextUtils.isEmpty(key) && pinnedProxies.contains(key);
    }

    public synchronized int getPinnedIndex(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        if (proxyInfo == null) {
            return -1;
        }
        String key = getProxyKey(proxyInfo);
        return TextUtils.isEmpty(key) ? -1 : pinnedProxyOrder.indexOf(key);
    }

    public synchronized int getPinnedCount() {
        ensureLoaded();
        return pinnedProxyOrder.size();
    }

    public synchronized int getSelectedPinAction(List<SharedConfig.ProxyInfo> proxies) {
        ensureLoaded();
        boolean hasPinned = false;
        boolean hasUnpinned = false;
        for (SharedConfig.ProxyInfo proxyInfo : proxies) {
            if (isPinned(proxyInfo)) {
                hasPinned = true;
            } else {
                hasUnpinned = true;
            }
            if (hasPinned && hasUnpinned) {
                return PIN_ACTION_NONE;
            }
        }
        if (hasPinned) {
            return PIN_ACTION_UNPIN;
        }
        return hasUnpinned ? PIN_ACTION_PIN : PIN_ACTION_NONE;
    }

    public synchronized PinOperationResult applySelectedPinAction(List<SharedConfig.ProxyInfo> proxies) {
        ensureLoaded();
        int action = getSelectedPinAction(proxies);
        if (action == PIN_ACTION_NONE) {
            return PinOperationResult.NO_CHANGE;
        }
        boolean changed = false;
        if (action == PIN_ACTION_PIN) {
            ArrayList<String> keys = new ArrayList<>();
            for (SharedConfig.ProxyInfo proxyInfo : proxies) {
                String key = getProxyKey(proxyInfo);
                if (!TextUtils.isEmpty(key) && !pinnedProxies.contains(key) && !keys.contains(key)) {
                    keys.add(key);
                }
            }
            if (pinnedProxyOrder.size() + keys.size() > MAX_PINNED_PROXIES) {
                return PinOperationResult.LIMIT_REACHED;
            }
            for (String key : keys) {
                if (pinnedProxies.add(key)) {
                    pinnedProxyOrder.add(key);
                    changed = true;
                }
            }
        } else {
            for (SharedConfig.ProxyInfo proxyInfo : proxies) {
                String key = getProxyKey(proxyInfo);
                if (!TextUtils.isEmpty(key)) {
                    if (pinnedProxies.remove(key)) {
                        changed = true;
                    }
                    if (pinnedProxyOrder.remove(key)) {
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            save();
            return PinOperationResult.CHANGED;
        }
        return PinOperationResult.NO_CHANGE;
    }

    public synchronized boolean movePinnedProxy(SharedConfig.ProxyInfo from, SharedConfig.ProxyInfo to) {
        ensureLoaded();
        String fromKey = getProxyKey(from);
        String toKey = getProxyKey(to);
        if (TextUtils.isEmpty(fromKey) || TextUtils.isEmpty(toKey) || TextUtils.equals(fromKey, toKey)) {
            return false;
        }
        if (!pinnedProxies.contains(fromKey) || !pinnedProxies.contains(toKey)) {
            return false;
        }
        int fromIndex = pinnedProxyOrder.indexOf(fromKey);
        int toIndex = pinnedProxyOrder.indexOf(toKey);
        if (fromIndex < 0 || toIndex < 0) {
            return false;
        }
        pinnedProxyOrder.remove(fromIndex);
        pinnedProxyOrder.add(toIndex, fromKey);
        save();
        return true;
    }

    public synchronized void removeProxy(SharedConfig.ProxyInfo proxyInfo) {
        ensureLoaded();
        String key = getProxyKey(proxyInfo);
        if (TextUtils.isEmpty(key)) {
            return;
        }
        boolean changed = pinnedProxyOrder.remove(key);
        changed |= proxyNames.remove(key) != null;
        changed |= pinnedProxies.remove(key);
        if (changed) {
            save();
        }
    }

    public synchronized void clearAll() {
        ensureLoaded();
        if (pinnedProxies.isEmpty() && pinnedProxyOrder.isEmpty() && proxyNames.isEmpty()) {
            return;
        }
        pinnedProxies.clear();
        pinnedProxyOrder.clear();
        proxyNames.clear();
        save();
    }

    public synchronized void sortProxyList(List<SharedConfig.ProxyInfo> proxies, boolean keepOrder, SharedConfig.ProxyInfo current) {
        ensureLoaded();
        ArrayList<SharedConfig.ProxyInfo> originalOrder = new ArrayList<>(proxies);
        Collections.sort(proxies, (o1, o2) -> {
            int index1 = getPinnedIndex(o1);
            int index2 = getPinnedIndex(o2);
            boolean pinned1 = index1 >= 0;
            boolean pinned2 = index2 >= 0;
            if (pinned1 != pinned2) {
                return pinned1 ? -1 : 1;
            }
            if (pinned1) {
                return Integer.compare(index1, index2);
            }
            long value1 = keepOrder && o1 != current ? originalOrder.indexOf(o1) * 10000L : o1.ping + sortBias(o1, current);
            long value2 = keepOrder && o2 != current ? originalOrder.indexOf(o2) * 10000L : o2.ping + sortBias(o2, current);
            return Long.compare(value1, value2);
        });
    }

    private void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private void load() {
        proxyList.clear();
        currentProxy = null;
        pinnedProxies.clear();
        pinnedProxyOrder.clear();
        proxyNames.clear();

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        ProxySettings savedSettings = ProxySettings.fromSharedPreferences(preferences);
        boolean savedValid = savedSettings.isValid();

        boolean needSave = false;
        String list = preferences.getString("proxy_list", null);
        if (!TextUtils.isEmpty(list)) {
            SerializedData data = new SerializedData(Base64.decode(list, Base64.DEFAULT));
            int count = data.readInt32(false);
            if (count == -1) {
                byte version = data.readByte(false);
                if (version == SCHEMA_WITH_WEB) {
                    count = data.readInt32(false);
                    for (int a = 0; a < count; a++) {
                        SharedConfig.ProxyInfo info = readProxyInfo(data);
                        info.ping = data.readInt64(false);
                        info.availableCheckTime = SharedConfig.ProxyInfo.normalizeAvailableCheckTime(data.readInt64(false));
                        if (data.readBool(false)) {
                            info.settings = ProxySettings.builder()
                                    .setType(ProxySettings.Type.WEB)
                                    .setAddress(info.settings.getAddress())
                                    .setPort(info.settings.getPort())
                                    .setSecret(info.settings.getSecret())
                                    .build();
                        }
                        addLoadedProxy(info, savedSettings, savedValid);
                    }
                } else if (version == SCHEMA_WITH_NAMES) {
                    count = data.readInt32(false);
                    for (int a = 0; a < count; a++) {
                        String name = data.readString(false);
                        SharedConfig.ProxyInfo info = readProxyInfo(data);
                        info.ping = data.readInt64(false);
                        info.availableCheckTime = SharedConfig.ProxyInfo.normalizeAvailableCheckTime(data.readInt64(false));
                        addLoadedProxy(info, savedSettings, savedValid);
                        String key = getProxyKey(info);
                        String normalizedName = normalizeName(name);
                        if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(normalizedName)) {
                            proxyNames.put(key, normalizedName);
                            needSave = true;
                        }
                    }
                } else if (version == SCHEMA_V4 || version == SCHEMA_V2) {
                    count = data.readInt32(false);
                    for (int a = 0; a < count; a++) {
                        SharedConfig.ProxyInfo info = readProxyInfo(data);
                        info.ping = data.readInt64(false);
                        info.availableCheckTime = SharedConfig.ProxyInfo.normalizeAvailableCheckTime(data.readInt64(false));
                        addLoadedProxy(info, savedSettings, savedValid);
                    }
                } else {
                    FileLog.e("Unknown proxy schema version: " + version);
                }
            } else {
                for (int a = 0; a < count; a++) {
                    addLoadedProxy(readProxyInfo(data), savedSettings, savedValid);
                }
            }
            data.cleanup();
        }
        if (currentProxy == null && savedValid) {
            currentProxy = new SharedConfig.ProxyInfo(savedSettings);
            proxyList.add(0, currentProxy);
        }

        String pinnedOrder = preferences.getString("proxy_pinned_order", preferences.getString("proxy_pinned_links_order", null));
        if (!TextUtils.isEmpty(pinnedOrder)) {
            for (String key : pinnedOrder.split("\\n")) {
                if (!TextUtils.isEmpty(key) && pinnedProxies.add(key)) {
                    pinnedProxyOrder.add(key);
                }
            }
        }
        Set<String> pinned = preferences.getStringSet("proxy_pinned", preferences.getStringSet("proxy_pinned_links", null));
        if (pinned != null) {
            for (String key : pinned) {
                if (!TextUtils.isEmpty(key) && pinnedProxies.add(key)) {
                    pinnedProxyOrder.add(key);
                }
            }
        }
        String names = preferences.getString("proxy_names", null);
        if (!TextUtils.isEmpty(names)) {
            for (String line : names.split("\\n")) {
                int tab;
                if (!TextUtils.isEmpty(line) && (tab = line.indexOf('\t')) > 0 && tab < line.length() - 1) {
                    String key = decode(line.substring(0, tab));
                    String name = decode(line.substring(tab + 1));
                    if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(name)) {
                        proxyNames.put(key, name);
                    }
                }
            }
        }
        loaded = true;
        if (needSave) {
            save();
        }
        syncProxyList();
    }

    private void addLoadedProxy(SharedConfig.ProxyInfo info, ProxySettings savedSettings, boolean savedValid) {
        proxyList.add(0, info);
        if (currentProxy == null && savedValid && savedSettings.equals(info.settings)) {
            currentProxy = info;
        }
    }

    private void save() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putStringSet("proxy_pinned", new HashSet<>(pinnedProxies));
        if (pinnedProxyOrder.isEmpty()) {
            editor.remove("proxy_pinned_order");
        } else {
            editor.putString("proxy_pinned_order", TextUtils.join("\n", pinnedProxyOrder));
        }
        editor.remove("proxy_pinned_links");
        editor.remove("proxy_pinned_links_order");
        if (proxyNames.isEmpty()) {
            editor.remove("proxy_names");
        } else {
            ArrayList<String> lines = new ArrayList<>(proxyNames.size());
            for (Map.Entry<String, String> entry : proxyNames.entrySet()) {
                lines.add(encode(entry.getKey()) + "\t" + encode(entry.getValue()));
            }
            editor.putString("proxy_names", TextUtils.join("\n", lines));
        }
        editor.apply();
    }

    private String getProxyKey(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null || TextUtils.isEmpty(proxyInfo.settings.getLink())) {
            return null;
        }
        return proxyInfo.settings.getLink();
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private static String encode(String value) {
        return Base64.encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    private void syncProxyList() {
        SharedConfig.proxyList.clear();
        SharedConfig.proxyList.addAll(proxyList);
        SharedConfig.currentProxy = currentProxy;
    }

    private static String resolveProxyIpAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(IDN.toASCII(host));
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                return null;
            }
            return address.getHostAddress();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String getProxyCountryKey(SharedConfig.ProxyInfo proxyInfo) {
        return proxyInfo == null ? "" : normalizeProxyCountryKey(proxyInfo.settings.getAddress());
    }

    private static String normalizeProxyCountryKey(String address) {
        if (TextUtils.isEmpty(address)) {
            return "";
        }
        String host = address.trim();
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        return host.toLowerCase(Locale.US);
    }

    private static String getCountryDisplayName(String countryCode) {
        if (TextUtils.isEmpty(countryCode)) {
            return "";
        }
        Locale currentLocale = LocaleController.getInstance().getCurrentLocale();
        if (currentLocale == null) {
            currentLocale = Locale.getDefault();
        }
        String country = new Locale("", countryCode.toUpperCase(Locale.US)).getDisplayCountry(currentLocale);
        return TextUtils.isEmpty(country) ? LocaleController.getString(R.string.Unknown) : country;
    }

    private static SharedConfig.ProxyInfo readProxyInfo(SerializedData data) {
        return new SharedConfig.ProxyInfo(buildSettings(
                data.readString(false),
                data.readInt32(false),
                data.readString(false),
                data.readString(false),
                data.readString(false)
        ));
    }

    private static ProxySettings buildSettings(String address, int port, String user, String password, String secret) {
        return ProxySettings.builder()
                .setType(TextUtils.isEmpty(secret) ? ProxySettings.Type.SOCKS5 : ProxySettings.Type.MTPROTO)
                .setAddress(address)
                .setPort(port)
                .setUser(user)
                .setPassword(password)
                .setSecret(secret)
                .build();
    }

    private static boolean isTelegramProxy(SharedConfig.ProxyInfo proxyInfo) {
        return proxyInfo != null && !TextUtils.isEmpty(proxyInfo.settings.getSecret());
    }
}
