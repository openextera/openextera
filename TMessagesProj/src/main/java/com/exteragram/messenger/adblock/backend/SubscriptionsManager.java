package com.exteragram.messenger.adblock.backend;

import android.content.SharedPreferences;
import android.util.Base64;

import com.exteragram.messenger.backup.PreferencesUtils;
import com.exteragram.messenger.utils.network.ExteraHttpClient;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SubscriptionsManager {

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15";
    private static final long DEFAULT_EXPIRATION = TimeUnit.DAYS.toMillis(5);
    private static final Pattern redirectPattern = Pattern.compile("!\\s*Redirect:\\s*(\\S+)");

    private static SubscriptionsManager instance;

    private final Object lock = new Object();
    private final DispatchQueue queue = new DispatchQueue("SubscriptionsManager");
    private final OkHttpClient client = ExteraHttpClient.INSTANCE.getClient();
    private final SharedPreferences prefs = PreferencesUtils.getPreferences("ublock_subscriptions");

    public static SubscriptionsManager getInstance() {
        if (instance == null) {
            instance = new SubscriptionsManager();
        }
        return instance;
    }

    private File getFileForUrl(String url) {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "adblock");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE) + ".txt");
    }

    public void update(String[] defaultUrls, Runnable onDone) {
        queue.postRunnable(() -> {
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            long now = System.currentTimeMillis();
            for (FilterMetadata metadata : getSubscriptions()) {
                if (now >= metadata.expires || !getFileForUrl(metadata.url).exists()) {
                    urls.add(metadata.url);
                }
            }
            synchronized (lock) {
                for (String url : defaultUrls) {
                    if (!prefs.contains("metadata_" + url) && !prefs.contains("redirect_" + url)) {
                        urls.add(url);
                    }
                }
            }
            for (String url : urls) {
                fetchSubscription(url, 0);
            }
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    public boolean hasFilters() {
        return !getSubscriptionFilePaths().isEmpty();
    }

    private void unsubscribe(String url) {
        synchronized (lock) {
            prefs.edit().remove("metadata_" + url).apply();
        }
        File file = getFileForUrl(url);
        if (file.exists()) {
            file.delete();
        }
    }

    public List<FilterMetadata> getSubscriptions() {
        ArrayList<FilterMetadata> subscriptions = new ArrayList<>();
        synchronized (lock) {
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith("metadata_")) {
                    continue;
                }
                try {
                    String json = prefs.getString(key, null);
                    if (json != null) {
                        subscriptions.add(FilterMetadata.fromJson(new JSONObject(json)));
                    }
                } catch (JSONException ignore) {
                }
            }
        }
        return Collections.unmodifiableList(subscriptions);
    }

    public List<String> getSubscriptionFilePaths() {
        ArrayList<String> paths = new ArrayList<>();
        for (FilterMetadata metadata : getSubscriptions()) {
            File file = getFileForUrl(metadata.url);
            if (file.exists()) {
                paths.add(file.getAbsolutePath());
            }
        }
        return paths;
    }

    private boolean fetchSubscription(String url, int depth) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return false;
                }
                String content = response.body().string();
                String redirect = extractRedirect(content);
                if (redirect != null) {
                    if (depth >= 3 || !fetchSubscription(redirect, depth + 1)) {
                        return false;
                    }
                    unsubscribe(url);
                    synchronized (lock) {
                        prefs.edit().putString("redirect_" + url, redirect).apply();
                    }
                    return true;
                }
                FilterMetadata metadata = parseMetadata(url, content);
                File file = getFileForUrl(url);
                File tmpFile = new File(file.getPath() + ".tmp");
                try {
                    try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    tmpFile.delete();
                    return false;
                }
                if (!tmpFile.renameTo(file)) {
                    tmpFile.delete();
                    return false;
                }
                synchronized (lock) {
                    prefs.edit().putString("metadata_" + url, metadata.toJson().toString()).apply();
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String extractRedirect(String content) {
        Matcher matcher = redirectPattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private FilterMetadata parseMetadata(String url, String content) {
        String title = extractMetadataValue(content, "Title");
        String homepage = extractMetadataValue(content, "Homepage");
        int rulesCount = countRules(content);
        long expires = calculateExpiration(content);
        return new FilterMetadata(url, title != null ? title : "Unnamed list", homepage != null ? homepage : "", rulesCount, expires);
    }

    private String extractMetadataValue(String content, String key) {
        Matcher matcher = Pattern.compile("!\\s*" + key + ":\\s*(.+)").matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private int countRules(String content) {
        int count = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("!")) {
                count++;
            }
        }
        return count;
    }

    private long calculateExpiration(String content) {
        String value = extractMetadataValue(content, "Expires");
        if (value == null) {
            return System.currentTimeMillis() + DEFAULT_EXPIRATION;
        }
        try {
            int amount = Integer.parseInt(value.replaceAll("[^0-9]", ""));
            if (value.contains("hour")) {
                return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(amount);
            } else if (value.contains("day")) {
                return System.currentTimeMillis() + TimeUnit.DAYS.toMillis(amount);
            }
            return System.currentTimeMillis() + DEFAULT_EXPIRATION;
        } catch (NumberFormatException e) {
            return System.currentTimeMillis() + DEFAULT_EXPIRATION;
        }
    }

    public static class FilterMetadata {
        public final String url;
        public final String title;
        public final String homepage;
        public final int rulesCount;
        public final long expires;

        private FilterMetadata(String url, String title, String homepage, int rulesCount, long expires) {
            this.url = url;
            this.title = title;
            this.homepage = homepage;
            this.rulesCount = rulesCount;
            this.expires = expires;
        }

        private static FilterMetadata fromJson(JSONObject json) throws JSONException {
            return new FilterMetadata(
                    json.getString("url"),
                    json.getString("title"),
                    json.getString("homepage"),
                    json.getInt("rulesCount"),
                    json.getLong("expires")
            );
        }

        private JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("url", url);
            json.put("title", title);
            json.put("homepage", homepage);
            json.put("rulesCount", rulesCount);
            json.put("expires", expires);
            return json;
        }
    }
}
