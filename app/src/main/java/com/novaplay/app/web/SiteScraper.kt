package com.novaplay.app.web

import com.novaplay.app.data.SitePage
import com.novaplay.app.data.StreamInfo
import kotlinx.coroutines.CancellationException
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

    /** A list with at least this many cards counts as a whole page as soon as it stops growing. */
    @Volatile
    var fullPage = 20

    /** A shorter list has to stay unchanged this long before it is accepted (unless the page source agrees sooner). */
    @Volatile
    var settleMs = 5_000L

    /** After this long, a short list also reads the page's own source and scrolls to wake lazy lists. */
    @Volatile
    var assistAfterMs = 1_500L

    /** How long a whole page (or one the page source agrees with) must stay unchanged before it is accepted. */
    @Volatile
    var fullQuietMs = 700L

    @Volatile
    var sourceQuietMs = 1_200L

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

    /**
     * Loads a listing page (home, search, channel, category) and reads its video cards.
     *
     * The cards come from the live page and from the JSON the page ships with. A short list is not
     * trusted: the page may still be rendering (or only renders what is on screen), so the page's own
     * source is read as well and the list has to stop growing before it is accepted.
     */
    suspend fun listing(d: PageDriver, url: String, scripts: String, waitMs: Long = listWaitMs): SitePage {
        d.load(url, LOAD_TIMEOUT_MS)
        val start = System.currentTimeMillis()
        val deadline = start + waitMs
        var best: JSONObject? = null
        var bestCount = 0
        var lastGrowth = start
        var sourceAsked = false
        var sourceDone = false
        var title = ""
        while (true) {
            prepare(d, scripts)
            title = d.title()
            val now = System.currentTimeMillis()
            val challenge = isChallengeTitle(title)

            var obj: JSONObject? = null
            if (sourceAsked) {
                val poll = d.evalObject("JSON.stringify(window.__novaFetchPoll(${cfg()}))")
                when (poll?.optString("state").orEmpty()) {
                    "done" -> {
                        sourceDone = true
                        obj = poll?.optJSONObject("res")
                    }
                    "error" -> sourceDone = true
                    "idle" -> sourceAsked = false // the page was replaced; ask again
                }
            }
            if (obj == null) obj = d.evalObject("JSON.stringify(window.__novaCards(${cfg()}))")

            val count = obj?.optJSONArray("items")?.length() ?: 0
            if (obj != null && count > 0 && count >= bestCount) {
                if (count > bestCount) lastGrowth = now
                best = obj
                bestCount = count
            }

            val quiet = now - lastGrowth
            if (bestCount >= fullPage && quiet >= fullQuietMs) break
            if (bestCount > 0 && sourceDone && quiet >= sourceQuietMs) break
            if (bestCount > 0 && quiet >= settleMs) break
            if (now >= deadline) break

            if (!challenge && bestCount < fullPage && now - start >= assistAfterMs) {
                if (!sourceAsked) {
                    sourceAsked = true
                    sourceDone = false
                    d.eval("window.__novaFetchStart&&window.__novaFetchStart(${cfg()})")
                }
                d.eval("window.__novaScroll&&window.__novaScroll()")
            }
            delay(if (challenge) pollMs * 2 else pollMs)
        }
        val found = best
        if (found != null) return SiteParser.parseCards(found)
        if (isChallengeTitle(title)) {
            throw SiteException(SiteException.Kind.CHALLENGE, "The site is asking for a human check")
        }
        throw SiteException(
            SiteException.Kind.EMPTY,
            "No videos found on this page" + if (title.isNotEmpty()) " (\"$title\")" else ""
        )
    }

    /** The text of a diagnostics report plus the first video the page listed (to diagnose a video page next). */
    class Diag(val text: String, val firstVideo: String?)

    /** A plain-text report on how [url] reads, for Settings > Diagnostics. Never throws. */
    suspend fun diagnose(d: PageDriver, url: String, scripts: String): Diag {
        val sb = StringBuilder()
        var firstVideo: String? = null
        sb.append("== ").append(url).append('\n')
        val t0 = System.currentTimeMillis()
        try {
            val page = listing(d, url, scripts)
            firstVideo = page.videos.firstOrNull()?.url
            sb.append("result: ").append(page.videos.size).append(" videos in ")
                .append(System.currentTimeMillis() - t0).append(" ms\n")
            sb.append("next: ").append(page.nextUrl ?: "(none)").append('\n')
            sb.append("page ").append(page.page).append(" of ").append(page.pages)
                .append(", template ").append(page.template ?: "(none)")
                .append(", numbered links ").append(page.pageLinks.keys.toList()).append('\n')
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            sb.append("result: FAILED (").append(e.kind).append(") ").append(e.message)
                .append(" after ").append(System.currentTimeMillis() - t0).append(" ms\n")
        } catch (e: Exception) {
            sb.append("result: ERROR ").append(e.message).append('\n')
            return Diag(sb.toString(), null)
        }
        try {
            prepare(d, scripts)
            val report = d.eval("window.__novaDiag(${cfg()})") as? String
            sb.append(report ?: "(the page gave no report)").append('\n')
            d.eval("window.__novaFetchStart&&window.__novaFetchStart(${cfg()})")
            var state = ""
            var poll: JSONObject? = null
            for (i in 0 until 14) {
                delay(450)
                poll = d.evalObject("JSON.stringify(window.__novaFetchPoll(${cfg()}))")
                state = poll?.optString("state").orEmpty()
                if (state == "done" || state == "error") break
            }
            sb.append("page source: ").append(state.ifEmpty { "no answer" })
            if (poll != null) {
                sb.append(", ").append(poll.optInt("len")).append(" bytes")
                if (state == "done") {
                    sb.append(", cards ").append(poll.optInt("fetchN"))
                        .append(" (dom ").append(poll.optInt("fetchDomN"))
                        .append(" / json ").append(poll.optInt("fetchJsonN")).append(")")
                }
                val msg = poll.optString("msg")
                if (msg.isNotEmpty()) sb.append(", ").append(msg)
            }
            sb.append('\n')
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sb.append("report failed: ").append(e.message).append('\n')
        }
        return Diag(sb.toString(), firstVideo)
    }

    /** Opens one video page the way playback does and reports what was found on it. Never throws. */
    suspend fun diagnoseVideo(d: PageDriver, pageUrl: String, scripts: String): String {
        val sb = StringBuilder()
        sb.append("== video ").append(pageUrl).append('\n')
        val t0 = System.currentTimeMillis()
        try {
            val info = video(d, pageUrl, scripts) { null }
            sb.append("result: OK in ").append(System.currentTimeMillis() - t0).append(" ms\n")
            sb.append("title: ").append(info.title.take(70)).append('\n')
            sb.append("streams: hls ").append(if (info.hlsUrl != null) "yes" else "no")
                .append(" (+").append(info.hlsFallbacks.size).append(" backup), mp4 ")
                .append(info.mp4.map { it.label }).append('\n')
            sb.append("tags (").append(info.tags.size).append("): ")
                .append(info.tags.joinToString(", ") { it.name + " -> " + Site.pathOf(it.url) }.take(500)).append('\n')
            sb.append("related: ").append(info.related.size).append(" | uploader: ").append(info.uploader)
                .append(" | views: ").append(info.views).append(" | description: ").append(info.description.length)
                .append(" chars\n")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteException) {
            sb.append("result: FAILED (").append(e.kind).append(") ").append(e.message)
                .append(" after ").append(System.currentTimeMillis() - t0).append(" ms\n")
        } catch (e: Exception) {
            sb.append("result: ERROR ").append(e.message).append('\n')
        }
        return sb.toString()
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
