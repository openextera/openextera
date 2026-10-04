package com.exteragram.messenger.adblock;

import android.text.TextUtils;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.exteragram.messenger.adblock.data.BlockResult;
import com.exteragram.messenger.adblock.data.UrlCosmeticResources;
import com.exteragram.messenger.adblock.interop.AdBlock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public abstract class AdBlockClient {

    private static final IOException BLOCKED_EXCEPTION = new IOException("Blocked by filter") {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    };

    public static CosmeticHide getCosmeticHide(String url) {
        UrlCosmeticResources resources = AdBlock.getCosmeticResources(url);
        if (resources == null) {
            return null;
        }
        return new CosmeticHide(url, createHideCss(resources.getHideSelectors()), resources.getInjectedScript(), resources.getExceptions(), resources.isGenericHide());
    }

    public static String getHiddenSelectorsCss(CosmeticHide cosmeticHide, String[] classes, String[] ids) {
        if (classes.length == 0 && ids.length == 0) {
            return null;
        }
        return createHideCss(AdBlock.getHiddenSelectors(classes, ids, cosmeticHide.getExceptions()));
    }

    public static BlockResult isAdRequest(WebResourceRequest request, String pageUrl) {
        return AdBlock.getBlockResult(request.getUrl().toString(), pageUrl, getRequestType(request, pageUrl));
    }

    public static WebResourceResponse createBlockedResponse(String requestType, BlockResult result) {
        String redirect = result.getRedirect();
        if (redirect != null && redirect.startsWith("data:")) {
            int semicolon = redirect.indexOf(';');
            int comma = redirect.indexOf(',');
            if (semicolon > 5 && comma > semicolon) {
                try {
                    String mimeType = redirect.substring(5, semicolon);
                    byte[] data = Base64.decode(redirect.substring(comma + 1), Base64.DEFAULT);
                    HashMap<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", mimeType);
                    headers.put("Access-Control-Allow-Credentials", "true");
                    headers.put("Access-Control-Allow-Headers", "Cache-Control");
                    headers.put("Access-Control-Allow-Origin", "*");
                    return new WebResourceResponse(mimeType, null, 200, "OK", headers, new ByteArrayInputStream(data));
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
        if ("sub_frame".equals(requestType)) {
            return new WebResourceResponse("text/html", "utf-8", 500, "Internal Server Error", null, null);
        }
        return new WebResourceResponse("text/plain", "utf-8", new BlockedInputStream());
    }

    public static String getRequestType(WebResourceRequest request, String pageUrl) {
        if ("OPTIONS".equals(request.getMethod())) {
            return "beacon";
        }
        String url = request.getUrl().toString();
        Map<String, String> headers = request.getRequestHeaders();
        if (request.isForMainFrame() && url.equals(pageUrl)) {
            return "main_frame";
        }
        if (url.startsWith("ws")) {
            return "websocket";
        }
        if (headers != null && "XMLHttpRequest".equals(headers.get("X-Requested-With"))) {
            return "xhr";
        }
        String accept = headers != null ? headers.get("Accept") : null;
        if (!request.isForMainFrame() && accept != null && accept.startsWith("text/html")) {
            return "sub_frame";
        }
        String extension = getRequestExtension(url);
        if ("js".equals(extension)) {
            return "script";
        }
        if ("css".equals(extension)) {
            return "stylesheet";
        }
        if ("otf".equals(extension) || "ttf".equals(extension) || "ttc".equals(extension) || "woff".equals(extension) || "woff2".equals(extension)) {
            return "font";
        }
        if (!"php".equals(extension)) {
            String mime = getRequestMime(extension);
            if (!"application/octet-stream".equals(mime)) {
                return getRequestTypeFromMime(mime);
            }
        }
        if (TextUtils.isEmpty(accept) || "*/*".equals(accept)) {
            return "other";
        }
        int comma = accept.indexOf(',');
        if (comma > 0) {
            accept = accept.substring(0, comma).trim();
        }
        return getRequestTypeFromMime(accept);
    }

    private static String getRequestExtension(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        int query = url.indexOf('?');
        if (query > 0) {
            url = url.substring(0, query);
        }
        int slash = url.lastIndexOf('/');
        if (slash > 0) {
            url = url.substring(slash + 1);
        }
        int dot = url.lastIndexOf('.');
        if (dot <= 0 || dot == url.length() - 1) {
            return url.endsWith("js") ? "js" : null;
        }
        String extension = url.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.isEmpty() && extension.length() <= 8) {
            return extension;
        }
        return null;
    }

    private static String getRequestMime(String extension) {
        if ("mhtml".equals(extension) || "mht".equals(extension)) {
            return "multipart/related";
        }
        if ("json".equals(extension)) {
            return "application/json";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
    }

    private static String getRequestTypeFromMime(String mime) {
        if (TextUtils.isEmpty(mime)) {
            return "other";
        }
        if ("application/javascript".equals(mime) || "application/x-javascript".equals(mime) || "text/javascript".equals(mime) || "application/json".equals(mime)) {
            return "script";
        }
        if ("text/css".equals(mime)) {
            return "stylesheet";
        }
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            return "media";
        }
        return mime.startsWith("font/") ? "font" : "other";
    }

    private static String createHideCss(String[] selectors) {
        if (selectors == null || selectors.length == 0) {
            return null;
        }
        StringBuilder css = new StringBuilder();
        for (String selector : selectors) {
            if (!TextUtils.isEmpty(selector) && selector.indexOf('{') < 0 && selector.indexOf('}') < 0) {
                css.append(selector);
                css.append("{display:none!important}\n");
            }
        }
        return css.length() > 0 ? css.toString() : null;
    }

    public static class BlockedInputStream extends InputStream {

        private BlockedInputStream() {
        }

        @Override
        public int available() throws IOException {
            throw BLOCKED_EXCEPTION;
        }

        @Override
        public int read() throws IOException {
            throw BLOCKED_EXCEPTION;
        }
    }

    public static class CosmeticHide {
        private final String url;
        private final String hideCss;
        private final String injectedScript;
        private final String[] exceptions;
        private final boolean genericHide;

        public CosmeticHide(String url, String hideCss, String injectedScript, String[] exceptions, boolean genericHide) {
            this.url = url;
            this.hideCss = hideCss;
            this.injectedScript = injectedScript;
            this.exceptions = exceptions;
            this.genericHide = genericHide;
        }

        public String getUrl() {
            return url;
        }

        public String getHideCss() {
            return hideCss;
        }

        public String getInjectedScript() {
            return injectedScript;
        }

        public String[] getExceptions() {
            return exceptions;
        }

        public boolean isGenericHide() {
            return genericHide;
        }
    }
}
