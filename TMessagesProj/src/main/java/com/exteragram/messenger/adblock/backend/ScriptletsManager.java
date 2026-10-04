package com.exteragram.messenger.adblock.backend;

import android.content.SharedPreferences;
import android.util.Base64;

import com.exteragram.messenger.backup.PreferencesUtils;
import com.exteragram.messenger.utils.network.ExteraHttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.Utilities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ScriptletsManager {

    private static final String RESOURCES_URL = "https://raw.githubusercontent.com/gorhill/uBlock/master/src/web_accessible_resources/";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15";

    private static final Map<String, ScriptletInfo> SCRIPTLETS_MAP = new HashMap<>();

    static {
        SCRIPTLETS_MAP.put("1x1.gif", new ScriptletInfo("1x1-transparent.gif"));
        SCRIPTLETS_MAP.put("2x2.png", new ScriptletInfo("2x2-transparent.png"));
        SCRIPTLETS_MAP.put("3x2.png", new ScriptletInfo("3x2-transparent.png"));
        SCRIPTLETS_MAP.put("32x32.png", new ScriptletInfo("32x32-transparent.png"));
        SCRIPTLETS_MAP.put("amazon_ads.js", new ScriptletInfo("amazon-adsystem.com/aax2/amzn_ads.js"));
        SCRIPTLETS_MAP.put("amazon_apstag.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("ampproject_v0.js", new ScriptletInfo("ampproject.org/v0.js"));
        SCRIPTLETS_MAP.put("chartbeat.js", new ScriptletInfo("static.chartbeat.com/chartbeat.js"));
        SCRIPTLETS_MAP.put("doubleclick_instream_ad_status.js", new ScriptletInfo("doubleclick.net/instream/ad_status.js"));
        SCRIPTLETS_MAP.put("empty", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("fingerprint2.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("fingerprint3.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("google-analytics_analytics.js", new ScriptletInfo(new String[]{"google-analytics.com/analytics.js", "googletagmanager_gtm.js", "googletagmanager.com/gtm.js"}));
        SCRIPTLETS_MAP.put("google-analytics_cx_api.js", new ScriptletInfo("google-analytics.com/cx/api.js"));
        SCRIPTLETS_MAP.put("google-analytics_ga.js", new ScriptletInfo("google-analytics.com/ga.js"));
        SCRIPTLETS_MAP.put("google-analytics_inpage_linkid.js", new ScriptletInfo("google-analytics.com/inpage_linkid.js"));
        SCRIPTLETS_MAP.put("google-ima.js", new ScriptletInfo("google-ima3"));
        SCRIPTLETS_MAP.put("googlesyndication_adsbygoogle.js", new ScriptletInfo(new String[]{"googlesyndication.com/adsbygoogle.js", "googlesyndication-adsbygoogle"}));
        SCRIPTLETS_MAP.put("googletagservices_gpt.js", new ScriptletInfo(new String[]{"googletagservices.com/gpt.js", "googletagservices-gpt"}));
        SCRIPTLETS_MAP.put("hd-main.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("nobab2.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("noeval.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("noeval-silent.js", new ScriptletInfo("silent-noeval.js"));
        SCRIPTLETS_MAP.put("nofab.js", new ScriptletInfo("fuckadblock.js-3.2.0"));
        SCRIPTLETS_MAP.put("noop-0.1s.mp3", new ScriptletInfo(new String[]{"noopmp3-0.1s", "abp-resource:blank-mp3"}));
        SCRIPTLETS_MAP.put("noop-0.5s.mp3", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("noop-1s.mp4", new ScriptletInfo(new String[]{"noopmp4-1s", "abp-resource:blank-mp4"}));
        SCRIPTLETS_MAP.put("noop.css", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("noop.html", new ScriptletInfo("noopframe"));
        SCRIPTLETS_MAP.put("noop.js", new ScriptletInfo(new String[]{"noopjs", "abp-resource:blank-js"}));
        SCRIPTLETS_MAP.put("noop.json", new ScriptletInfo(new String[]{"noopjson"}));
        SCRIPTLETS_MAP.put("noop.txt", new ScriptletInfo("nooptext"));
        SCRIPTLETS_MAP.put("noop-vast2.xml", new ScriptletInfo("noopvast-2.0"));
        SCRIPTLETS_MAP.put("noop-vast3.xml", new ScriptletInfo("noopvast-3.0"));
        SCRIPTLETS_MAP.put("noop-vast4.xml", new ScriptletInfo("noopvast-4.0"));
        SCRIPTLETS_MAP.put("noop-vmap1.xml", new ScriptletInfo(new String[]{"noop-vmap1.0.xml", "noopvmap-1.0"}));
        SCRIPTLETS_MAP.put("outbrain-widget.js", new ScriptletInfo("widgets.outbrain.com/outbrain.js"));
        SCRIPTLETS_MAP.put("popads.js", new ScriptletInfo(new String[]{"popads.net.js", "prevent-popads-net.js"}));
        SCRIPTLETS_MAP.put("popads-dummy.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("prebid-ads.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("scorecardresearch_beacon.js", new ScriptletInfo("scorecardresearch.com/beacon.js"));
        SCRIPTLETS_MAP.put("sensors-analytics.js", new ScriptletInfo(null));
        SCRIPTLETS_MAP.put("nitropay_ads.js", new ScriptletInfo(null));
    }

    private static ScriptletsManager instance;

    private final Object lock = new Object();
    private final DispatchQueue queue = new DispatchQueue("ScriptletsManager");
    private final OkHttpClient client = ExteraHttpClient.INSTANCE.getClient();
    private final SharedPreferences prefs = PreferencesUtils.getPreferences("ublock_scriptlets");

    public static ScriptletsManager getInstance() {
        if (instance == null) {
            instance = new ScriptletsManager();
        }
        return instance;
    }

    public static String getExtension(String filename) {
        if (filename.endsWith(".css")) {
            return ".css";
        } else if (filename.endsWith(".gif")) {
            return ".gif";
        } else if (filename.endsWith(".html")) {
            return ".html";
        } else if (filename.endsWith(".js")) {
            return ".js";
        } else if (filename.endsWith(".json")) {
            return ".json";
        } else if (filename.endsWith(".mp3")) {
            return ".mp3";
        } else if (filename.endsWith(".mp4")) {
            return ".mp4";
        } else if (filename.endsWith(".png")) {
            return ".png";
        } else if (filename.endsWith(".xml")) {
            return ".xml";
        }
        return ".txt";
    }

    public void download(Utilities.Callback<Boolean> callback) {
        queue.postRunnable(() -> {
            boolean success = true;
            for (Map.Entry<String, ScriptletInfo> entry : SCRIPTLETS_MAP.entrySet()) {
                if (!downloadScriptlet(entry.getKey(), entry.getValue())) {
                    success = false;
                    break;
                }
            }
            if (success) {
                synchronized (lock) {
                    prefs.edit().putBoolean("__downloaded", true).apply();
                }
            }
            if (callback != null) {
                callback.run(success);
            }
        });
    }

    private boolean downloadScriptlet(String name, ScriptletInfo info) {
        synchronized (lock) {
            if (prefs.contains(name)) {
                return true;
            }
        }
        Request request = new Request.Builder()
                .url(RESOURCES_URL + name)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404) {
                return true;
            }
            if (!response.isSuccessful()) {
                return false;
            }
            String content = Base64.encodeToString(response.body().bytes(), Base64.NO_WRAP);
            synchronized (lock) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(name, content);
                Object alias = info.alias;
                if (alias != null) {
                    JSONArray aliases = new JSONArray();
                    if (alias instanceof String) {
                        aliases.put(alias);
                    } else if (alias instanceof String[]) {
                        for (String a : (String[]) alias) {
                            aliases.put(a);
                        }
                    }
                    editor.putString(name + "_aliases", aliases.toString());
                }
                editor.apply();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isDownloaded() {
        synchronized (lock) {
            return prefs.getBoolean("__downloaded", false);
        }
    }

    public Collection<Scriptlet> iterScriptlets() {
        ArrayList<Scriptlet> scriptlets = new ArrayList<>();
        synchronized (lock) {
            for (String name : SCRIPTLETS_MAP.keySet()) {
                String content = prefs.getString(name, null);
                if (content == null) {
                    continue;
                }
                ArrayList<String> aliases = new ArrayList<>();
                String aliasesJson = prefs.getString(name + "_aliases", null);
                if (aliasesJson != null) {
                    try {
                        JSONArray array = new JSONArray(aliasesJson);
                        for (int i = 0; i < array.length(); i++) {
                            aliases.add(array.getString(i));
                        }
                    } catch (JSONException ignore) {
                    }
                }
                scriptlets.add(new Scriptlet(name, aliases, content));
            }
        }
        return Collections.unmodifiableList(scriptlets);
    }

    public static class Scriptlet {
        public final String filename;
        public final List<String> aliases;
        public final String content;

        private Scriptlet(String filename, List<String> aliases, String content) {
            this.filename = filename;
            this.aliases = aliases;
            this.content = content;
        }
    }

    public static class ScriptletInfo {
        final Object alias;

        public ScriptletInfo(Object alias) {
            this.alias = alias;
        }
    }
}
