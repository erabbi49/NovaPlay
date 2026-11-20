package com.novaplay.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.novaplay.app.adblock.AdBlockWeb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * An invisible WebView used as a real browser for the site: it passes bot checks a plain HTTP
 * client cannot, runs the page's scripts and lets us read the DOM. One page job at a time; the
 * WebView is created on demand and destroyed after a quiet period to give the memory back.
 */
@SuppressLint("SetJavaScriptEnabled")
class HiddenWeb(context: Context, private val blockImages: Boolean) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    private val sniff = CopyOnWriteArrayList<String>()
    private val idleKill = Runnable { destroyNow() }

    private var web: WebView? = null

    @Volatile
    private var navDone: CompletableDeferred<Unit>? = null

    @Volatile
    private var topUrl = ""

    @Volatile
    private var startDomain = ""

    @Volatile
    private var ua = ""

    /** Runs [block] with exclusive use of the page, then blanks the page so nothing keeps running. */
    suspend fun <T> use(block: suspend (PageDriver) -> T): T = lock.withLock {
        try {
            block(driver)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { park() }
        }
    }

    private val driver = object : PageDriver {
        override val userAgent: String get() = ua

        override fun sniffed(): List<String> = sniff.toList()

        override suspend fun load(url: String, timeoutMs: Long) {
            val done = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                val w = try {
                    ensureWeb()
                } catch (e: Throwable) {
                    throw SiteException(SiteException.Kind.NETWORK, "Android System WebView is not available (${e.message})")
                }
                sniff.clear()
                navDone = done
                topUrl = url
                startDomain = Site.registrable(Site.hostOf(url))
                w.loadUrl(url)
            }
            try {
                withTimeout(timeoutMs) { done.await() }
            } catch (e: TimeoutCancellationException) {
                throw SiteException(SiteException.Kind.NETWORK, "The page took too long to load")
            }
        }

        override suspend fun eval(script: String): Any? {
            val raw = withTimeoutOrNull(10_000L) {
                withContext(Dispatchers.Main) {
                    val w = web ?: throw SiteException(SiteException.Kind.NETWORK, "The web engine stopped")
                    suspendCancellableCoroutine<String?> { cont ->
                        w.evaluateJavascript(script) { v -> if (cont.isActive) cont.resume(v) }
                    }
                }
            } ?: return null
            if (raw == "null") return null
            return try {
                val v = JSONTokener(raw).nextValue()
                if (v == JSONObject.NULL) null else v
            } catch (e: Exception) {
                null
            }
        }

        override suspend fun title(): String = withContext(Dispatchers.Main) { web?.title ?: "" }

        override suspend fun stop() {
            withContext(Dispatchers.Main) { park() }
        }
    }

    // ---------- everything below runs on the main thread ----------

    private fun park() {
        val w = web ?: return
        try {
            w.stopLoading()
            w.loadUrl("about:blank")
        } catch (e: Exception) {
            // ignore
        }
        main.removeCallbacks(idleKill)
        main.postDelayed(idleKill, IDLE_KILL_MS)
    }

    private fun destroyNow() {
        main.removeCallbacks(idleKill)
        val w = web ?: return
        web = null
        try {
            w.stopLoading()
            w.webViewClient = WebViewClient()
            w.destroy()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun ensureWeb(): WebView {
        main.removeCallbacks(idleKill)
        web?.let { return it }
        val w = WebView(app)
        configure(w)
        web = w
        return w
    }

    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadsImagesAutomatically = !blockImages
        s.blockNetworkImage = blockImages
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = Site.browserUserAgent(s.userAgentString)
        ua = s.userAgentString
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(w, true)
        w.webViewClient = client
        w.webChromeClient = chrome
        AdBlockWeb.install(w, false)
        // never attached to a window, so give it a size and keep it running
        w.layout(0, 0, 1080, 1920)
        w.onResume()
        w.resumeTimers()
    }

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val blocked = AdBlockWeb.intercept(request, topUrl.ifEmpty { null })
            if (blocked != null) return blocked
            val u = request.url.toString()
            if (SiteParser.isStreamUrl(u) && !sniff.contains(u)) sniff.add(u)
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val u = request.url.toString()
            if (!u.startsWith("http")) return true
            if (request.isRedirect) return false
            // a script sending the page to another site (popunder, ad redirect) is not allowed
            return Site.registrable(Site.hostOf(u)) != startDomain && !request.hasGesture()
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null) {
                topUrl = url
                AdBlockWeb.onPageStarted(view, url)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null && url != "about:blank") navDone?.complete(Unit)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && request.url.toString() != "about:blank") {
                val why = error.description?.toString().orEmpty().ifEmpty { "Network error" }
                navDone?.completeExceptionally(SiteException(SiteException.Kind.NETWORK, why))
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            navDone?.completeExceptionally(SiteException(SiteException.Kind.NETWORK, "The web engine crashed"))
            destroyNow()
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val IDLE_KILL_MS = 90_000L
    }
}
