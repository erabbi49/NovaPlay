package com.novaplay.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.novaplay.app.R
import com.novaplay.app.adblock.AdBlockWeb
import com.novaplay.app.data.Video
import com.novaplay.app.player.Playback
import com.novaplay.app.player.PlayerActivity
import com.novaplay.app.web.Site

/**
 * A plain in-app browser with the ad blocker switched on. It is the fallback when the site wants a
 * human check (Cloudflare): the cookie it earns is shared with the hidden loaders.
 */
class BrowserActivity : BaseActivity() {
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var urlView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playButton: ImageButton
    private val chromeClient = chrome()
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pageUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(true)
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.userAgentString = Site.browserUserAgent(s.userAgentString)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        AdBlockWeb.install(web, true)
        web.webViewClient = client()
        web.webChromeClient = chromeClient
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chromeClient.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
        val start = intent.getStringExtra(EXTRA_URL) ?: Site.home()
        web.loadUrl(start)
    }

    private fun build() {
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.bg(this))
        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0)
        val close = Ui.iconButton(this, R.drawable.ic_close, "Close", Ui.text(this))
        close.setOnClickListener { finish() }
        bar.addView(close)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        titleView = Ui.textView(this, 14f, Ui.text(this), true, 1)
        urlView = Ui.textView(this, 11f, Ui.textDim(this), false, 1)
        texts.addView(titleView, Ui.lp(Ui.MATCH, Ui.WRAP))
        texts.addView(urlView, Ui.lp(Ui.MATCH, Ui.WRAP))
        val tp = Ui.lp(0, Ui.WRAP)
        tp.weight = 1f
        tp.leftMargin = Ui.dp(this, 4)
        bar.addView(texts, tp)

        playButton = Ui.iconButton(this, R.drawable.ic_play, "Play in NovaPlay", Ui.accent(this))
        playButton.visibility = View.GONE
        playButton.setOnClickListener { playCurrent() }
        bar.addView(playButton)
        val reload = Ui.iconButton(this, R.drawable.ic_refresh, "Reload", Ui.text(this))
        reload.setOnClickListener { web.reload() }
        bar.addView(reload)
        column.addView(bar, Ui.lp(Ui.MATCH, Ui.dp(this, 52)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent(this))
        progress.visibility = View.INVISIBLE
        column.addView(progress, Ui.lp(Ui.MATCH, Ui.dp(this, 3)))

        web = WebView(this)
        val wp = Ui.lp(Ui.MATCH, 0)
        wp.weight = 1f
        column.addView(web, wp)
        root.addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        setContentView(root)
    }

    private fun client() = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            AdBlockWeb.intercept(request, pageUrl.ifEmpty { null })

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            // intent://, market:// and friends are how ad pages leave the browser
            if (!url.startsWith("http://") && !url.startsWith("https://")) return true
            // a script (not the user) sending us to another site is an ad redirect
            if (!request.hasGesture() && !Site.isSiteUrl(url)) return true
            return AdBlockWeb.shouldBlockPopup(url, pageUrl.ifEmpty { null })
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageUrl = url
            AdBlockWeb.onPageStarted(view, url)
            progress.visibility = View.VISIBLE
            showAddress(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.INVISIBLE
            showAddress(url)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Dialogs.toast(this@BrowserActivity, "The page crashed")
            finish()
            return true
        }
    }

    private fun chrome() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            titleView.text = title
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Open the target in a throw-away WebView just to learn its URL, then decide.
            val probe = WebView(view.context)
            probe.webViewClient = object : WebViewClient() {
                private var handled = false

                private fun route(v: WebView, url: String) {
                    if (handled) return
                    handled = true
                    v.stopLoading()
                    v.post { v.destroy() }
                    val ok = isUserGesture && (url.startsWith("http://") || url.startsWith("https://")) &&
                        Site.isSiteUrl(url) && !AdBlockWeb.shouldBlockPopup(url, pageUrl.ifEmpty { null })
                    if (ok) web.loadUrl(url)
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    route(v, request.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    route(v, url)
                }
            }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            column.visibility = View.INVISIBLE
            root.addView(view, Ui.frame(Ui.MATCH, Ui.MATCH))
            SystemBars.setHidden(window, true)
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            root.removeView(v)
            customView = null
            column.visibility = View.VISIBLE
            SystemBars.setHidden(window, false)
            customCallback?.onCustomViewHidden()
            customCallback = null
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    private fun showAddress(url: String) {
        urlView.text = Site.hostOf(url).ifEmpty { url }
        playButton.visibility = if (Site.isVideoUrl(url)) View.VISIBLE else View.GONE
    }

    /** Hands the page that is open to the real player. */
    private fun playCurrent() {
        val url = web.url ?: return
        if (!Site.isVideoUrl(url)) return
        val title = web.title.orEmpty().ifEmpty { "Video" }
        Playback.playNow(Video(id = Video.idFor(url), url = url, title = title))
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        column.removeView(web)
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun open(ctx: Context, url: String) {
            val i = Intent(ctx, BrowserActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
