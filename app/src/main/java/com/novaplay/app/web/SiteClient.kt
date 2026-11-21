package com.novaplay.app.web

import android.content.Context
import android.webkit.CookieManager
import com.novaplay.app.data.SitePage
import com.novaplay.app.data.StreamInfo
import com.novaplay.app.data.Video
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's single entry point to the website: listing pages and stream resolving.
 * Everything runs through two hidden WebViews (one for lists, one for video pages).
 */
object SiteClient {
    private class Cached(val at: Long, val info: StreamInfo)

    private lateinit var appContext: Context
    private lateinit var lister: HiddenWeb
    private lateinit var resolver: HiddenWeb
    private val cache = ConcurrentHashMap<String, Cached>()

    private const val TTL_MS = 20 * 60 * 1000L
    private const val MAX_CACHED = 40

    private val scripts: String by lazy {
        val a = appContext.assets
        val cards = a.open("web/cards.js").bufferedReader().use { it.readText() }
        val video = a.open("web/video.js").bufferedReader().use { it.readText() }
        cards + "\n" + video
    }

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        lister = HiddenWeb(appContext, true)
        resolver = HiddenWeb(appContext, false)
    }

    /**
     * Reads one listing page (home, newest, search results, a channel or category).
     * [later] marks the pages after the first: they may not exist, so they are not waited for as long
     * and an empty answer is not retried.
     */
    suspend fun listing(url: String, later: Boolean = false): SitePage {
        val target = Site.rebase(url)
        val wait = if (later) SiteScraper.listWaitMs.coerceAtMost(10_000L) else SiteScraper.listWaitMs
        try {
            return lister.use { SiteScraper.listing(it, target, scripts, wait) }
        } catch (e: SiteException) {
            val retry = e.kind == SiteException.Kind.NETWORK || (e.kind == SiteException.Kind.EMPTY && !later)
            if (!retry) throw e
        }
        return lister.use { SiteScraper.listing(it, target, scripts, wait) } // one retry for flaky loads
    }

    suspend fun search(query: String): SitePage = listing(Site.search(query))

    /**
     * Reads [urls] one after the other, then one video page from the first list that had one, and returns a
     * text report of how each page was understood.
     */
    suspend fun diagnose(urls: List<String>): String {
        val sb = StringBuilder()
        var firstVideo: String? = null
        for (u in urls) {
            val r = lister.use { SiteScraper.diagnose(it, Site.rebase(u), scripts) }
            sb.append(r.text).append('\n')
            if (firstVideo == null) firstVideo = r.firstVideo
        }
        val video = firstVideo
        if (video != null) {
            sb.append(resolver.use { SiteScraper.diagnoseVideo(it, Site.rebase(video), scripts) }).append('\n')
        }
        return sb.toString()
    }

    /** Finds the playable stream and details of a video page. Cached for a while, since URLs are signed. */
    suspend fun resolve(pageUrl: String, force: Boolean = false): StreamInfo {
        val url = Site.rebase(pageUrl)
        val key = Video.idFor(url)
        if (!force) fresh(key)?.let { return it }
        return resolver.use { d ->
            if (!force) fresh(key)?.let { return@use it }
            val info = try {
                SiteScraper.video(d, url, scripts, ::cookieFor)
            } catch (e: SiteException) {
                if (e.kind != SiteException.Kind.NETWORK && e.kind != SiteException.Kind.NO_STREAM) throw e
                SiteScraper.video(d, url, scripts, ::cookieFor) // one retry
            }
            remember(key, info)
            info
        }
    }

    fun cached(pageUrl: String): StreamInfo? = fresh(Video.idFor(Site.rebase(pageUrl)))

    /** Drops a cached result, e.g. after its stream URL stopped working. */
    fun forget(pageUrl: String) {
        cache.remove(Video.idFor(Site.rebase(pageUrl)))
    }

    private fun fresh(key: String): StreamInfo? {
        val c = cache[key] ?: return null
        if (System.currentTimeMillis() - c.at > TTL_MS) {
            cache.remove(key)
            return null
        }
        return c.info
    }

    private fun remember(key: String, info: StreamInfo) {
        cache[key] = Cached(System.currentTimeMillis(), info)
        if (cache.size > MAX_CACHED) {
            val oldest = cache.entries.minByOrNull { it.value.at }
            if (oldest != null) cache.remove(oldest.key)
        }
    }

    private fun cookieFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (e: Exception) {
        null
    }
}
