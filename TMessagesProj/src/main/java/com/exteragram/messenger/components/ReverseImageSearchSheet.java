package com.exteragram.messenger.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.exteragram.messenger.adblock.WebAdBlocker;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;

public class ReverseImageSearchSheet extends BottomSheet {

    private static final int OPEN_IN_BROWSER = 1;

    public enum Provider {
        YANDEX("Yandex", "https://yandex.com/images/"),
        GOOGLE("Google", "https://www.google.com/"),
        BING("Bing", "https://www.bing.com/images"),
        TINEYE("TinEye", "https://tineye.com/");

        public final String title;
        public final String landingUrl;

        Provider(String title, String landingUrl) {
            this.title = title;
            this.landingUrl = landingUrl;
        }
    }

    private final Provider provider;
    private final WebAdBlocker adBlocker;
    private final CircularProgressIndicator spinner;
    private WebView webView;

    private volatile String currentUrl;
    private String pendingScript;
    private boolean uploadInjected;
    private int pageStartCount;
    private int injectedAtStartCount = -1;
    private boolean revealed;
    private Runnable revealTimeout;

    @SuppressLint("SetJavaScriptEnabled")
    public ReverseImageSearchSheet(Context context, File file, Provider provider, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.provider = provider;
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        useBackgroundTopPadding = false;
        setCanDismissWithSwipe(false);
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite));

        int actionBarHeight = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;

        FrameLayout frameLayout = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
            }
        };
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        webView = new WebView(context);
        webView.setVisibility(View.INVISIBLE);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        File databaseDir = new File(ApplicationLoader.getFilesDirFixed(), "webview_database");
        if ((databaseDir.exists() && databaseDir.isDirectory()) || databaseDir.mkdirs()) {
            settings.setDatabasePath(databaseDir.getAbsolutePath());
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        adBlocker = new WebAdBlocker(webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && !request.isForMainFrame()) {
                    WebResourceResponse response = adBlocker.interceptRequest(request);
                    if (response != null) {
                        return response;
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.isForMainFrame()) {
                    Uri url = request.getUrl();
                    String host = url != null ? url.getHost() : null;
                    if (host != null && !isProviderHost(provider, host)) {
                        Browser.openUrlInSystemBrowser(getContext(), url.toString());
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageStartCount++;
                onUrlChanged(url);
                adBlocker.onPageStarted(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                onUrlChanged(url);
                if (!uploadInjected) {
                    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                        uploadInjected = true;
                        injectedAtStartCount = pageStartCount;
                        if (pendingScript != null) {
                            view.evaluateJavascript(pendingScript, null);
                            pendingScript = null;
                        }
                    }
                } else if (pageStartCount > injectedAtStartCount) {
                    reveal();
                }
                adBlocker.injectCosmetics(url);
                hideProviderAds(view);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                hideProviderAds(view);
                onUrlChanged(url);
                if (provider == Provider.TINEYE && uploadInjected && url != null) {
                    String path;
                    try {
                        path = Uri.parse(url).getPath();
                    } catch (Exception e) {
                        path = null;
                    }
                    if (path != null && path.startsWith("/search")) {
                        reveal();
                    }
                }
            }
        });
        float actionBarHeightDp = actionBarHeight / AndroidUtilities.density;
        frameLayout.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, actionBarHeightDp, 0, 0));

        View divider = new View(context);
        divider.setBackgroundColor(getThemedColor(Theme.key_divider));
        frameLayout.addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density, Gravity.TOP | Gravity.LEFT, 0, actionBarHeightDp, 0, 0));

        ActionBar actionBar = new ActionBar(context, resourcesProvider);
        actionBar.setOccupyStatusBar(true);
        actionBar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_actionBarWhiteSelector), false);
        actionBar.setBackButtonImage(R.drawable.ic_close_white);
        actionBar.setTitle(provider.title);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    dismiss();
                } else if (id == OPEN_IN_BROWSER && !TextUtils.isEmpty(currentUrl)) {
                    Browser.openUrlInSystemBrowser(getContext(), currentUrl);
                }
            }
        });
        if (provider == Provider.YANDEX) {
            actionBar.createMenu().addItem(OPEN_IN_BROWSER, R.drawable.msg_openin);
        }
        frameLayout.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, actionBarHeightDp));

        spinner = new CircularProgressIndicator(context);
        spinner.setIndeterminate(true);
        spinner.setIndicatorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        spinner.setTrackColor(getThemedColor(Theme.key_windowBackgroundWhiteInputField));
        spinner.setIndicatorSize(AndroidUtilities.dp(48));
        spinner.setTrackThickness(AndroidUtilities.dp(4));
        spinner.setTrackCornerRadius(AndroidUtilities.dp(2));
        spinner.setIndicatorTrackGapSize(AndroidUtilities.dp(3));
        frameLayout.addView(spinner, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        setCustomView(frameLayout);

        Utilities.globalQueue.postRunnable(() -> {
            String encoded = encodeImage(file);
            AndroidUtilities.runOnUIThread(() -> {
                if (webView == null) {
                    return;
                }
                if (encoded == null) {
                    dismiss();
                    return;
                }
                seedConsentCookies(provider);
                pendingScript = buildUploadScript(provider, encoded);
                webView.loadUrl(provider.landingUrl);
                revealTimeout = this::reveal;
                AndroidUtilities.runOnUIThread(revealTimeout, 30000);
            });
        });
    }

    private void reveal() {
        if (revealed || webView == null) {
            return;
        }
        revealed = true;
        if (revealTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(revealTimeout);
            revealTimeout = null;
        }
        webView.setAlpha(0f);
        webView.setVisibility(View.VISIBLE);
        webView.animate().alpha(1f).setDuration(150).start();
        if (spinner != null) {
            spinner.animate().alpha(0f).setDuration(150).withEndAction(() -> spinner.setVisibility(View.GONE)).start();
        }
    }

    private void onUrlChanged(String url) {
        currentUrl = url;
    }

    private void hideProviderAds(WebView view) {
        if (provider != Provider.YANDEX) {
            return;
        }
        view.evaluateJavascript("(function(){try{if(!document.getElementById('__ayu_adcleanup')){var s=document.createElement('style');s.id='__ayu_adcleanup';s.textContent='.DistributionPopup,.Smartbanner{display:none!important}';(document.head||document.documentElement).appendChild(s);}}catch(e){}})();", null);
    }

    private static void seedConsentCookies(Provider provider) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        switch (provider) {
            case GOOGLE:
                cookieManager.setCookie("https://www.google.com", "SOCS=CAISHAgBEhJnd3NfMjAyNjA2MjYtMF9SQzEaAmVuIAEaBgiAjozSBg; Domain=.google.com; Path=/; Secure; SameSite=Lax");
                break;
            case BING:
                cookieManager.setCookie("https://www.bing.com", "BCP=AD=1&AL=1&SM=1; Domain=.bing.com; Path=/; Secure");
                break;
            case TINEYE:
                cookieManager.setCookie("https://tineye.com", "cookie_consent=accepted; Path=/");
                break;
            case YANDEX:
                for (String url : new String[]{"https://yandex.com", "https://yandex.ru"}) {
                    cookieManager.setCookie(url, "gdpr=0; Domain=" + (url.endsWith(".ru") ? ".yandex.ru" : ".yandex.com") + "; Path=/; Secure");
                }
                break;
        }
        cookieManager.flush();
    }

    private static boolean isProviderHost(Provider provider, String host) {
        String lowerHost = host.toLowerCase();
        switch (provider) {
            case GOOGLE:
                return lowerHost.contains("google.") || lowerHost.endsWith("gstatic.com") || lowerHost.endsWith("googleusercontent.com");
            case BING:
                return lowerHost.endsWith("bing.com") || lowerHost.endsWith("bingapis.com") || lowerHost.endsWith("live.com") || lowerHost.endsWith("microsoft.com");
            case YANDEX:
                return lowerHost.contains("yandex.") || lowerHost.endsWith("ya.ru") || lowerHost.contains("yastatic.");
            default:
                return lowerHost.endsWith("tineye.com");
        }
    }

    private static String buildUploadScript(Provider provider, String base64) {
        switch (provider) {
            case GOOGLE:
                return "(function(){try{" + bytesFromBase64(base64) + "var file=new File([a],'image.jpg',{type:'image/jpeg'});var f=document.createElement('form');f.method='POST';f.enctype='multipart/form-data';f.action='https://lens.google.com/v3/upload';var i=document.createElement('input');i.type='file';i.name='encoded_image';f.appendChild(i);document.body.appendChild(f);var dt=new DataTransfer();dt.items.add(file);i.files=dt.files;f.submit();}catch(e){}})();";
            case BING:
                return "(function(){try{var f=document.createElement('form');f.method='POST';f.enctype='multipart/form-data';f.action='https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=SBIHMP&sbifnm=image.jpg';var i=document.createElement('input');i.type='hidden';i.name='imageBin';i.value='" + base64 + "';f.appendChild(i);document.body.appendChild(f);f.submit();}catch(e){}})();";
            case YANDEX:
                return "(function(){try{" + bytesFromBase64(base64) + "var o=location.protocol+'//'+location.host;var blob=new Blob([a],{type:'image/jpeg'});var d=new FormData();d.append('upfile',blob,'image.jpg');var u=o+'/images/touch/search?rpt=imageview&format=json&request='+encodeURIComponent('{\"blocks\":[{\"block\":\"cbir-uploader__get-cbir-id\"}]}');fetch(u,{method:'POST',credentials:'include',headers:{'X-Requested-With':'XMLHttpRequest','Accept':'application/json, text/javascript, */*; q=0.01'},body:d}).then(function(r){return r.json();}).then(function(j){var p=j.blocks[0].params;if(p&&p.cbirId){location.href=o+'/images/search?cbir_id='+encodeURIComponent(p.cbirId)+'&rpt=imageview&tabInt=1&url='+encodeURIComponent(p.originalImageUrl||'');}}).catch(function(e){});}catch(e){}})();";
            default:
                return "(function(){try{" + bytesFromBase64(base64) + "var file=new File([a],'image.jpg',{type:'image/jpeg'});var n=0;var t=setInterval(function(){var i=document.querySelector('input#upload-box');if(i){clearInterval(t);try{var dt=new DataTransfer();dt.items.add(file);i.files=dt.files;i.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}}else if(++n>24){clearInterval(t);}},250);}catch(e){}})();";
        }
    }

    private static String bytesFromBase64(String base64) {
        return "var b='" + base64 + "';var bin=atob(b);var a=new Uint8Array(bin.length);for(var k=0;k<bin.length;k++)a[k]=bin.charCodeAt(k);";
    }

    private static String encodeImage(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sampleSize > 2560) {
                sampleSize *= 2;
            }
            options.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) {
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int maxSide = Math.max(width, height);
            if (maxSide > 1280) {
                float scale = 1280f / maxSide;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
            bitmap.recycle();
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void dismiss() {
        if (revealTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(revealTimeout);
            revealTimeout = null;
        }
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Exception e) {
                FileLog.e(e);
            }
            webView = null;
        }
        super.dismiss();
    }
}
