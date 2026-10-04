package com.exteragram.messenger.appicons;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AppIconPreviewLoader {

    private static final int MAX_CACHE_SIZE = 12 * 1024 * 1024;
    private static final int ACCENT_GRID = 24;
    private static final int HUE_BUCKETS = 12;

    private static LruCache<String, Bitmap> cache;
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Map<String, List<Callback>> pending = new HashMap<>();
    private static final List<Integer> knownSizes = new ArrayList<>();
    private static final Map<String, Integer> accents = new HashMap<>();

    public interface Callback {
        void onPreviewReady(AppIcon icon, int size, Bitmap bitmap);
    }

    private AppIconPreviewLoader() {
    }

    private static LruCache<String, Bitmap> getCache() {
        if (cache == null) {
            cache = new LruCache<String, Bitmap>((int) Math.min(MAX_CACHE_SIZE, Runtime.getRuntime().maxMemory() / 16)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
        }
        return cache;
    }

    private static String key(AppIcon icon, int size) {
        return icon.id + "@" + size;
    }

    public static Bitmap getCached(AppIcon icon, int size) {
        return getCache().get(key(icon, size));
    }

    public static Bitmap getAnyCached(AppIcon icon) {
        Bitmap best = null;
        for (int i = 0; i < knownSizes.size(); i++) {
            Bitmap bitmap = getCache().get(key(icon, knownSizes.get(i)));
            if (bitmap != null && (best == null || bitmap.getWidth() > best.getWidth())) {
                best = bitmap;
            }
        }
        return best;
    }

    public static void trimAbove(int size) {
        for (Map.Entry<String, Bitmap> entry : getCache().snapshot().entrySet()) {
            if (entry.getValue().getWidth() >= size) {
                getCache().remove(entry.getKey());
            }
        }
    }

    public static int getAccent(AppIcon icon) {
        if (icon.color != 0) {
            return icon.color;
        }
        Integer accent = accents.get(icon.id);
        if (accent != null) {
            return accent;
        }
        if (!icon.isBackgroundColor()) {
            return 0;
        }
        int color;
        try {
            color = ContextCompat.getColor(ApplicationLoader.applicationContext, icon.getBackground());
        } catch (Throwable e) {
            FileLog.e(e);
            color = 0;
        }
        accents.put(icon.id, color);
        return color;
    }

    public static int getAccentTextColor(AppIcon icon, int fallbackKey, Theme.ResourcesProvider resourcesProvider) {
        int accent = icon == null ? 0 : getAccent(icon);
        if (accent != 0) {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(accent, hsl);
            if (hsl[1] >= 0.15f) {
                if (Theme.isCurrentThemeDark()) {
                    hsl[2] = Math.max(hsl[2], 0.72f);
                    return clampTone(hsl, 70f, 100f);
                }
                hsl[2] = Math.min(hsl[2], 0.42f);
                return clampTone(hsl, 0f, 42f);
            }
        }
        return Theme.getColor(fallbackKey, resourcesProvider);
    }

    public static int getAccentTint(AppIcon icon) {
        int accent = icon == null ? 0 : getAccent(icon);
        if (accent == 0) {
            return 0;
        }
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(accent, hsl);
        hsl[1] = Math.min(hsl[1], 0.6f);
        if (Theme.isCurrentThemeDark()) {
            hsl[2] = 0.22f;
            return clampTone(hsl, 20f, 100f);
        }
        hsl[2] = 0.86f;
        return clampTone(hsl, 0f, 88f);
    }

    private static int clampTone(float[] hsl, float minLightness, float maxLightness) {
        double[] lab = new double[3];
        ColorUtils.colorToLAB(ColorUtils.HSLToColor(hsl), lab);
        double lightness = lab[0];
        if (lightness >= minLightness && lightness <= maxLightness) {
            return ColorUtils.HSLToColor(hsl);
        }
        float target = lightness >= minLightness ? maxLightness : minLightness;
        float low = 0f;
        float high = 1f;
        for (int i = 0; i < 16; i++) {
            hsl[2] = (low + high) / 2f;
            ColorUtils.colorToLAB(ColorUtils.HSLToColor(hsl), lab);
            if (lab[0] < target) {
                low = hsl[2];
            } else {
                high = hsl[2];
            }
        }
        hsl[2] = (low + high) / 2f;
        return ColorUtils.HSLToColor(hsl);
    }

    public static void load(AppIcon icon, int size, Callback callback) {
        if (size <= 0) {
            return;
        }
        if (!knownSizes.contains(size)) {
            knownSizes.add(size);
        }
        String key = key(icon, size);
        Bitmap cached = getCache().get(key);
        if (cached != null) {
            callback.onPreviewReady(icon, size, cached);
            return;
        }
        List<Callback> callbacks = pending.get(key);
        if (callbacks != null) {
            callbacks.add(callback);
            return;
        }
        callbacks = new ArrayList<>();
        callbacks.add(callback);
        pending.put(key, callbacks);

        boolean needAccent = !accents.containsKey(icon.id) && getAccent(icon) == 0;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = render(icon, size);
            int accent = bitmap != null && needAccent ? extractAccent(bitmap) : 0;
            AndroidUtilities.runOnUIThread(() -> {
                if (bitmap != null) {
                    getCache().put(key, bitmap);
                }
                if (accent != 0 && !accents.containsKey(icon.id)) {
                    accents.put(icon.id, accent);
                }
                List<Callback> waiting = pending.remove(key);
                if (waiting == null || bitmap == null) {
                    return;
                }
                for (Callback c : waiting) {
                    c.onPreviewReady(icon, size, bitmap);
                }
            });
        });
    }

    private static Bitmap render(AppIcon icon, int size) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            // adaptive icon layers are 108dp with a 72dp safe zone, so bleed them by 1/4 on each side
            int inset = size / 4;
            Rect rect = new Rect(-inset, -inset, size + inset, size + inset);
            if (icon.isBackgroundColor()) {
                canvas.drawColor(ContextCompat.getColor(ApplicationLoader.applicationContext, icon.getBackground()));
            } else {
                draw(canvas, icon.getBackground(), rect);
            }
            draw(canvas, icon.getForeground(), rect);
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static int extractAccent(Bitmap bitmap) {
        float stepX = (bitmap.getWidth() - 1) / (float) (ACCENT_GRID - 1);
        float stepY = (bitmap.getHeight() - 1) / (float) (ACCENT_GRID - 1);
        float[] hsl = new float[3];
        float[] weights = new float[HUE_BUCKETS];
        float[] hueSums = new float[HUE_BUCKETS];
        float[] saturationSums = new float[HUE_BUCKETS];
        float[] lightnessSums = new float[HUE_BUCKETS];
        float neutralCount = 0;
        float colorfulCount = 0;
        float neutralLightness = 0;

        for (int x = 0; x < ACCENT_GRID; x++) {
            for (int y = 0; y < ACCENT_GRID; y++) {
                int pixel = bitmap.getPixel((int) (x * stepX), (int) (y * stepY));
                if (Color.alpha(pixel) < 200) {
                    continue;
                }
                ColorUtils.colorToHSL(pixel, hsl);
                if (hsl[1] < 0.15f || hsl[2] < 0.06f || hsl[2] > 0.94f) {
                    neutralCount++;
                    neutralLightness += hsl[2];
                } else {
                    int bucket = Math.min(HUE_BUCKETS - 1, (int) (hsl[0] / 360f * HUE_BUCKETS));
                    float weight = hsl[1] * (1f - Math.abs(hsl[2] * 2f - 1f));
                    weights[bucket] += weight;
                    hueSums[bucket] += hsl[0] * weight;
                    saturationSums[bucket] += hsl[1] * weight;
                    lightnessSums[bucket] += hsl[2] * weight;
                    colorfulCount++;
                }
            }
        }

        int best = -1;
        for (int i = 0; i < HUE_BUCKETS; i++) {
            if (best == -1 || weights[i] > weights[best]) {
                best = i;
            }
        }
        if (best != -1 && weights[best] > 0 && colorfulCount >= neutralCount * 0.15f) {
            hsl[0] = hueSums[best] / weights[best];
            hsl[1] = saturationSums[best] / weights[best];
            hsl[2] = lightnessSums[best] / weights[best];
            return ColorUtils.HSLToColor(hsl);
        }
        if (neutralCount <= 0) {
            return 0;
        }
        hsl[0] = 0;
        hsl[1] = 0;
        hsl[2] = neutralLightness / neutralCount;
        return ColorUtils.HSLToColor(hsl);
    }

    private static void draw(Canvas canvas, int resId, Rect rect) {
        if (resId == 0) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        Resources resources = context.getResources();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inScaled = false;
        BitmapFactory.decodeResource(resources, resId, options);
        if (options.outWidth > 0 && options.outHeight > 0) {
            int sampleSize = 1;
            while (options.outWidth / (sampleSize * 2) >= rect.width()) {
                sampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeResource(resources, resId, options);
            if (decoded != null) {
                canvas.drawBitmap(decoded, null, rect, paint);
                return;
            }
        }
        Drawable drawable = ContextCompat.getDrawable(context, resId);
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null) {
            canvas.drawBitmap(((BitmapDrawable) drawable).getBitmap(), null, rect, paint);
            return;
        }
        if (drawable != null) {
            drawable.setBounds(rect);
            drawable.draw(canvas);
        }
    }
}
