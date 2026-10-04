package com.exteragram.messenger.adblock;

import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.Keep;

import com.exteragram.messenger.adblock.backend.AdBlockManager;
import com.exteragram.messenger.adblock.data.BlockResult;
import com.exteragram.messenger.adblock.interop.AdBlock;

import org.json.JSONObject;
import org.telegram.messenger.Utilities;

import java.util.concurrent.atomic.AtomicInteger;

public class WebAdBlocker {

    private static final String COSMETICS_SCRIPT =
            "(function() {\n" +
            "    var name = '$BRIDGE';\n" +
            "    var bridge = window[name];\n" +
            "    if (!bridge || window[name + '_c']) return;\n" +
            "    Object.defineProperty(window, name + '_c', {value: true});\n" +
            "    function addStyle(css) {\n" +
            "        if (!css) return;\n" +
            "        var style = document.createElement('style');\n" +
            "        style.textContent = css;\n" +
            "        (document.head || document.documentElement).appendChild(style);\n" +
            "    }\n" +
            "    addStyle($CSS);\n" +
            "    if (!$OBSERVE) return;\n" +
            "    var seenClasses = new Set(), seenIds = new Set(), classes = [], ids = [], scheduled = false;\n" +
            "    var delay = window.setTimeout.bind(window);\n" +
            "    function collect(element) {\n" +
            "        var list = element.classList;\n" +
            "        if (list) {\n" +
            "            for (var i = 0; i < list.length; i++) {\n" +
            "                if (!seenClasses.has(list[i])) {\n" +
            "                    seenClasses.add(list[i]);\n" +
            "                    classes.push(list[i]);\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "        var id = element.id;\n" +
            "        if (typeof id === 'string' && id && !seenIds.has(id)) {\n" +
            "            seenIds.add(id);\n" +
            "            ids.push(id);\n" +
            "        }\n" +
            "    }\n" +
            "    function collectTree(node) {\n" +
            "        if (!node || node.nodeType !== 1) return;\n" +
            "        collect(node);\n" +
            "        var elements = node.querySelectorAll('[class],[id]');\n" +
            "        for (var i = 0; i < elements.length; i++) collect(elements[i]);\n" +
            "    }\n" +
            "    function flush() {\n" +
            "        scheduled = false;\n" +
            "        if (!classes.length && !ids.length) return;\n" +
            "        var found = bridge.onElementsFound(classes.join(' '), ids.join(' '));\n" +
            "        classes = [];\n" +
            "        ids = [];\n" +
            "        addStyle(found);\n" +
            "    }\n" +
            "    new MutationObserver(function(mutations) {\n" +
            "        for (var i = 0; i < mutations.length; i++) {\n" +
            "            var mutation = mutations[i];\n" +
            "            if (mutation.type === 'attributes') {\n" +
            "                collect(mutation.target);\n" +
            "            } else {\n" +
            "                for (var j = 0; j < mutation.addedNodes.length; j++) collectTree(mutation.addedNodes[j]);\n" +
            "            }\n" +
            "        }\n" +
            "        if (!scheduled && (classes.length || ids.length)) {\n" +
            "            scheduled = true;\n" +
            "            delay(flush, 100);\n" +
            "        }\n" +
            "    }).observe(document, {childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id']});\n" +
            "    collectTree(document.documentElement);\n" +
            "    flush();\n" +
            "})();\n";

    private final WebView webView;
    private final String bridgeName;
    private final AtomicInteger blockedCount = new AtomicInteger();

    private volatile AdBlockClient.CosmeticHide cosmeticHide;
    private volatile String pageUrl;
    private volatile String previousPageUrl;
    private volatile int previousBlockedCount;
    private volatile boolean navigationPending;
    private volatile boolean allowBlockedPage;
    private volatile boolean pageBlocked;

    public WebAdBlocker(WebView webView) {
        this.webView = webView;
        bridgeName = "_" + Long.toHexString(Utilities.fastRandom.nextLong());
        webView.addJavascriptInterface(this, bridgeName);
        AdBlockManager.initialize();
    }

    public int getBlockedCount() {
        return blockedCount.get();
    }

    public void allowBlockedPage() {
        allowBlockedPage = true;
    }

    public boolean consumePageBlocked() {
        boolean blocked = pageBlocked;
        pageBlocked = false;
        return blocked;
    }

    public void reload() {
        webView.reload();
    }

    public WebResourceResponse interceptRequest(WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (request.isForMainFrame()) {
            if (!navigationPending) {
                navigationPending = true;
                previousPageUrl = pageUrl;
                previousBlockedCount = blockedCount.getAndSet(0);
            }
            pageUrl = url;
            boolean allow = allowBlockedPage;
            allowBlockedPage = false;
            if (allow || !AdBlockManager.isActive()) {
                return null;
            }
            BlockResult result = AdBlockClient.isAdRequest(request, url);
            if (result == null || !result.isMatched()) {
                return null;
            }
            pageBlocked = true;
            return new WebResourceResponse("plain/text", "utf-8", 590, "Page blocked", null, null);
        }
        if (!AdBlockManager.isActive()) {
            return null;
        }
        String sourceUrl = pageUrl;
        if (TextUtils.isEmpty(sourceUrl)) {
            sourceUrl = url;
        }
        String requestType = AdBlockClient.getRequestType(request, sourceUrl);
        BlockResult result = AdBlock.getBlockResult(url, sourceUrl, requestType);
        if (result == null || !result.isMatched()) {
            return null;
        }
        blockedCount.incrementAndGet();
        return AdBlockClient.createBlockedResponse(requestType, result);
    }

    public void onPageStarted(String url) {
        navigationPending = false;
        previousPageUrl = null;
        pageUrl = url;
        cosmeticHide = null;
    }

    public void onDownloadStart() {
        if (navigationPending) {
            navigationPending = false;
            pageUrl = previousPageUrl;
            previousPageUrl = null;
            blockedCount.addAndGet(previousBlockedCount);
        }
    }

    public void injectCosmetics(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://")) || !AdBlockManager.isActive()) {
            return;
        }
        AdBlockClient.CosmeticHide current = cosmeticHide;
        if (current != null && TextUtils.equals(current.getUrl(), url)) {
            return;
        }
        AdBlockClient.CosmeticHide hide = AdBlockClient.getCosmeticHide(url);
        if (hide == null) {
            return;
        }
        cosmeticHide = hide;
        String hideCss = hide.getHideCss();
        if (hideCss != null || !hide.isGenericHide()) {
            webView.evaluateJavascript(COSMETICS_SCRIPT
                    .replace("$BRIDGE", bridgeName)
                    .replace("$OBSERVE", String.valueOf(!hide.isGenericHide()))
                    .replace("$CSS", hideCss != null ? JSONObject.quote(hideCss) : "null"), null);
        }
        String injectedScript = hide.getInjectedScript();
        if (TextUtils.isEmpty(injectedScript)) {
            return;
        }
        String flag = "'" + bridgeName + "_s'";
        webView.evaluateJavascript("if (!window[" + flag + "]) {\nObject.defineProperty(window, " + flag + ", {value: true});\n" + injectedScript + "\n}", null);
    }

    @JavascriptInterface
    @Keep
    public String onElementsFound(String classes, String ids) {
        AdBlockClient.CosmeticHide hide = cosmeticHide;
        if (hide == null || hide.isGenericHide() || !AdBlockManager.isActive()) {
            return null;
        }
        return AdBlockClient.getHiddenSelectorsCss(hide, split(classes), split(ids));
    }

    private static String[] split(String value) {
        return TextUtils.isEmpty(value) ? new String[0] : value.split(" ");
    }
}
