package com.novaplay.app.adblock

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import java.io.ByteArrayInputStream

/** Object exposed to page scripts as "NovaCF". Called from WebView's JavaBridge thread. */
class AdBlockBridge(private val cosmetic: Boolean) {

    @JavascriptInterface
    fun scriptletsEnabled(): Boolean = FilterLists.adBlockEnabled && FilterLists.scriptletsEnabled

    @JavascriptInterface
    fun cosmeticEnabled(): Boolean = cosmetic && FilterLists.adBlockEnabled && FilterLists.cosmeticEnabled

    @JavascriptInterface
    fun scriptlets(host: String): String {
        val e = FilterLists.engine ?: return "[]"
        val all = JSONArray()
        for (call in e.scriptlets(host)) {
            val one = JSONArray()
            for (s in call) one.put(s)
            all.put(one)
        }
        return all.toString()
    }

    @JavascriptInterface
    fun specific(host: String): String {
        val e = FilterLists.engine ?: return ""
        return e.cosmeticCss(host)
    }

    @JavascriptInterface
    fun generic(host: String, classesJson: String, idsJson: String): String {
        val e = FilterLists.engine ?: return ""
        return FilterEngine.toCss(e.genericSelectors(host, parse(classesJson), parse(idsJson)))
    }

    private fun parse(json: String): List<String> {
        return try {
            val a = JSONArray(json)
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) out.add(a.optString(i))
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Glue between [FilterEngine] and android.webkit.WebView. */
object AdBlockWeb {
    private const val BRIDGE = "NovaCF"

    @Volatile
    private var bootstrapJs: String? = null

    private val docStartSupported: Boolean by lazy {
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        } catch (e: Throwable) {
            false
        }
    }

    private val IMAGE_EXT = arrayOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp", ".avif")
    private val FONT_EXT = arrayOf(".woff", ".woff2", ".ttf", ".otf", ".eot")
    private val MEDIA_EXT = arrayOf(".mp4", ".webm", ".m3u8", ".ts", ".m4s", ".mp3", ".ogg", ".m4a", ".aac", ".mpd")

    /**
     * Call once per WebView, before the first load. [cosmetic] false keeps element hiding off
     * (used for the invisible loader WebViews) while scriptlets still run.
     */
    @SuppressLint("JavascriptInterface", "RequiresFeature")
    fun install(webView: WebView, cosmetic: Boolean) {
        webView.addJavascriptInterface(AdBlockBridge(cosmetic), BRIDGE)
        if (docStartSupported) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, bootstrap(webView), setOf("*"))
            } catch (e: Throwable) {
                // falls back to injection in onPageStarted
            }
        }
    }

    private fun bootstrap(webView: WebView): String {
        val cached = bootstrapJs
        if (cached != null) return cached
        val assets = webView.context.applicationContext.assets
        val a = assets.open("filters/scriptlets.js").bufferedReader().use { it.readText() }
        val b = assets.open("filters/bootstrap.js").bufferedReader().use { it.readText() }
        val js = a + "\n" + b
        bootstrapJs = js
        return js
    }

    /** Call from WebViewClient.onPageStarted. Only does something on WebViews without document-start scripts. */
    fun onPageStarted(webView: WebView, url: String) {
        if (docStartSupported || !FilterLists.adBlockEnabled) return
        if (!url.startsWith("http")) return
        try {
            webView.evaluateJavascript(bootstrap(webView), null)
        } catch (e: Throwable) {
            // ignore
        }
    }

    /**
     * Returns an empty response for blocked requests, null to let the request through.
     * [pageUrl] is the top-level document the request belongs to.
     */
    fun intercept(request: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        if (!FilterLists.adBlockEnabled) return null
        val engine = FilterLists.engine ?: return null
        if (request.isForMainFrame) return null
        val url = request.url.toString()
        val type = guessType(request)
        var block = engine.shouldBlock(url, pageUrl, type)
        if (!block && type == ResType.OTHER) {
            // fetch()/script tags without an extension are indistinguishable here, so try both
            block = engine.shouldBlock(url, pageUrl, ResType.SCRIPT) || engine.shouldBlock(url, pageUrl, ResType.XHR)
        }
        if (!block) return null
        FilterLists.noteBlocked()
        return WebResourceResponse(
            "text/plain", "utf-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun shouldBlockPopup(url: String, pageUrl: String?): Boolean {
        if (!FilterLists.adBlockEnabled || !FilterLists.popupBlockEnabled) return false
        val e = FilterLists.engine ?: return false
        return e.shouldBlock(url, pageUrl, ResType.POPUP)
    }

    private fun hasExt(path: String, exts: Array<String>): Boolean {
        for (e in exts) if (path.endsWith(e)) return true
        return false
    }

    private fun guessType(r: WebResourceRequest): Int {
        val path = (r.url.path ?: "").lowercase()
        val headers = r.requestHeaders
        val accept = (headers?.get("Accept") ?: "").lowercase()
        if (path.endsWith(".js") || path.endsWith(".mjs")) return ResType.SCRIPT
        if (path.endsWith(".css")) return ResType.STYLESHEET
        if (hasExt(path, IMAGE_EXT)) return ResType.IMAGE
        if (hasExt(path, FONT_EXT)) return ResType.FONT
        if (hasExt(path, MEDIA_EXT)) return ResType.MEDIA
        if (accept.contains("text/html")) return ResType.SUBDOCUMENT
        if (accept.contains("text/css")) return ResType.STYLESHEET
        if (accept.startsWith("image/")) return ResType.IMAGE
        if (headers?.get("X-Requested-With") == "XMLHttpRequest") return ResType.XHR
        return ResType.OTHER
    }
}
