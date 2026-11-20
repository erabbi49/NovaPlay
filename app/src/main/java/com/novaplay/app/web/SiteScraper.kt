package com.novaplay.app.web

import com.novaplay.app.data.SitePage
import com.novaplay.app.data.StreamInfo
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The page-reading procedures. Written against [PageDriver] so the same code runs on the hidden
 * WebView in the app and on a fake page in tests. Nothing here stops the page afterwards;
 * [HiddenWeb.use] blanks it once the block returns.
 */
internal object SiteScraper {
    const val LOAD_TIMEOUT_MS = 30_000L

    @Volatile
    var listWaitMs = 25_000L

    @Volatile
    var videoWaitMs = 30_000L

    @Volatile
    var pollMs = 450L

    private fun cfg(): String = "{videoRe:" + JSONObject.quote(Site.VIDEO_PATH_RE) + ",hostRe:''}"

    fun isChallengeTitle(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("just a moment") || t.contains("attention required") ||
            t.contains("verify you are human") || t.contains("checking your browser") ||
            t.contains("security check")
    }

    private suspend fun prepare(d: PageDriver, scripts: String) {
        d.eval("if(!window.__novaVideo){\n$scripts\n}1")
        d.eval("window.__novaAgree&&window.__novaAgree()")
    }

    /** Loads a listing page (home, search, channel, category) and reads its video cards. */
    suspend fun listing(d: PageDriver, url: String, scripts: String): SitePage {
        d.load(url, LOAD_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + listWaitMs
        var lastCount = -1
        var title = ""
        while (true) {
            prepare(d, scripts)
            title = d.title()
            val obj = d.evalObject("JSON.stringify(window.__novaCards(${cfg()}))")
            val count = obj?.optJSONArray("items")?.length() ?: 0
            // two polls in a row with the same count = the page has finished rendering its cards
            if (obj != null && count > 0 && count == lastCount) return SiteParser.parseCards(obj)
            lastCount = count
            if (System.currentTimeMillis() >= deadline) break
            delay(if (isChallengeTitle(title)) pollMs * 2 else pollMs)
        }
        if (isChallengeTitle(title)) {
            throw SiteException(SiteException.Kind.CHALLENGE, "The site is asking for a human check")
        }
        throw SiteException(
            SiteException.Kind.EMPTY,
            "No videos found on this page" + if (title.isNotEmpty()) " (\"$title\")" else ""
        )
    }

    /** Opens a video page and returns its stream URLs, details and related videos. */
    suspend fun video(d: PageDriver, pageUrl: String, scripts: String, cookieFor: (String) -> String?): StreamInfo {
        d.load(pageUrl, LOAD_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + videoWaitMs
        var obj: JSONObject? = null
        var title = ""
        var polls = 0
        var extra = 0
        var triedPlay = false
        while (true) {
            prepare(d, scripts)
            title = d.title()
            obj = d.evalObject("JSON.stringify(window.__novaVideo(${cfg()}))") ?: obj
            val err = if (obj == null || obj.isNull("error")) "" else obj.optString("error")
            if (err.isNotEmpty()) throw SiteException(SiteException.Kind.UNAVAILABLE, err)
            val extracted = obj?.optJSONArray("streams")?.length() ?: 0
            val haveStream = extracted > 0 || d.sniffed().any { SiteParser.isStreamUrl(it) }
            if (haveStream) {
                // give the related list a moment to render, but do not hold playback back for long
                val related = obj?.optJSONArray("related")?.length() ?: 0
                if (related > 0 || extra >= 3) break
                extra++
            } else {
                polls++
                if (!triedPlay && polls >= 3) {
                    triedPlay = true
                    d.eval("window.__novaPlay&&window.__novaPlay()")
                }
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(pollMs)
        }
        val info = SiteParser.buildStreamInfo(pageUrl, obj, d.sniffed(), d.userAgent, cookieFor)
        if (info.candidates().isEmpty()) {
            if (isChallengeTitle(title)) {
                throw SiteException(SiteException.Kind.CHALLENGE, "The site is asking for a human check")
            }
            throw SiteException(SiteException.Kind.NO_STREAM, "Could not find a playable stream on this page")
        }
        return info
    }
}
