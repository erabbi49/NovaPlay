package com.novaplay.app.web

import com.novaplay.app.data.SitePage
import com.novaplay.app.data.Source
import com.novaplay.app.data.StreamInfo
import com.novaplay.app.data.StreamVariant
import com.novaplay.app.data.Video
import org.json.JSONArray
import org.json.JSONObject

/** Turns what assets/web/cards.js and video.js return into app models. */
object SiteParser {

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    /** "12:34" -> 754, "1:02:03" -> 3723, anything else -> 0. */
    fun parseDuration(text: String): Int {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return 0
        var total = 0
        for (p in parts) {
            val n = p.trim().toIntOrNull() ?: return 0
            total = total * 60 + n
        }
        return total
    }

    fun cardToVideo(o: JSONObject): Video? {
        val url = o.str("url")
        if (url.isEmpty()) return null
        return Video(
            id = Video.idFor(url),
            url = url,
            title = o.str("title").ifEmpty { "Video" },
            thumb = o.str("thumb"),
            durationSec = parseDuration(o.str("dur")),
            views = o.str("views"),
            uploader = o.str("up"),
            uploaderUrl = o.str("upUrl"),
            quality = o.str("q"),
            previewUrl = o.str("prev")
        )
    }

    fun parseVideos(arr: JSONArray?, skipId: String? = null): List<Video> {
        if (arr == null) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Video>()
        for (i in 0 until arr.length()) {
            val v = cardToVideo(arr.optJSONObject(i) ?: continue) ?: continue
            if (v.id == skipId) continue
            if (seen.add(v.id)) out.add(v)
        }
        return out
    }

    /** Result of window.__novaCards(). */
    fun parseCards(o: JSONObject): SitePage {
        val next = o.str("next")
        return SitePage(parseVideos(o.optJSONArray("items")), next.ifEmpty { null }, o.str("title"))
    }

    // ---------- stream URLs ----------

    private val NOT_A_STREAM = Regex("preview|teaser|thumb|sprite|poster|trailer|storyboard")

    private fun pathOnly(url: String): String = Site.pathOf(url).lowercase()

    fun kindOf(url: String): String {
        val p = pathOnly(url)
        return when {
            p.contains(".m3u8") -> "hls"
            p.endsWith(".mp4") -> "mp4"
            else -> ""
        }
    }

    /** True for the .m3u8 / .mp4 requests a player makes for the real video (not previews or thumbnails). */
    fun isStreamUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (kindOf(url).isEmpty()) return false
        return !NOT_A_STREAM.containsMatchIn(pathOnly(url))
    }

    fun codecOf(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".av1.") || u.contains("/av1/") -> "av1"
            u.contains(".h264.") -> "h264"
            else -> ""
        }
    }

    private val LABEL_HEIGHT = Regex("(\\d{3,4})\\s*[pP]")
    private val URL_HEIGHT = Regex("[._/=-](\\d{3,4})p(?=[._/,-]|$)")

    fun heightOf(label: String, url: String): Int {
        LABEL_HEIGHT.find(label)?.let { return it.groupValues[1].toInt() }
        URL_HEIGHT.find(Site.pathOf(url).lowercase())?.let { return it.groupValues[1].toInt() }
        return 0
    }

    private class Raw(val url: String, val kind: String, val label: String, val codec: String)

    /**
     * Builds the player model from video.js output ([o]) plus the stream URLs the page's own
     * player was seen requesting ([sniffed]). Never throws; the result may have no candidates.
     */
    fun buildStreamInfo(
        pageUrl: String,
        o: JSONObject?,
        sniffed: List<String>,
        userAgent: String,
        cookieFor: (String) -> String?
    ): StreamInfo {
        val raws = ArrayList<Raw>()
        val seen = HashSet<String>()
        val arr = o?.optJSONArray("streams")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.str("url")
                if (url.isEmpty() || !seen.add(url)) continue
                raws.add(Raw(url, s.str("kind").ifEmpty { kindOf(url) }, s.str("label"), s.str("codec")))
            }
        }
        for (u in sniffed) {
            if (isStreamUrl(u) && seen.add(u)) raws.add(Raw(u, kindOf(u), "", codecOf(u)))
        }

        // AV1 is not decodable everywhere, so H.264 playlists go first.
        val hls = raws.filter { it.kind == "hls" }.sortedBy { if (it.codec == "av1") 1 else 0 }.map { it.url }

        class Mp4(val variant: StreamVariant, val codec: String)
        val mp4 = raws.filter { it.kind == "mp4" }.map { r ->
            val h = heightOf(r.label, r.url)
            val label = r.label.ifEmpty { if (h > 0) "${h}p" else "MP4" }
            Mp4(StreamVariant(label, h, r.url), r.codec)
        }.sortedWith(compareByDescending<Mp4> { it.variant.height }.thenBy { if (it.codec == "av1") 1 else 0 })
            .map { it.variant }

        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = pageUrl
        if (userAgent.isNotEmpty()) headers["User-Agent"] = userAgent
        val first = hls.firstOrNull() ?: mp4.firstOrNull()?.url
        val cookie = if (first != null) cookieFor(first) else null
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

        val tags = ArrayList<Source>()
        val tagArr = o?.optJSONArray("tags")
        if (tagArr != null) {
            for (i in 0 until tagArr.length()) {
                val t = tagArr.optJSONObject(i) ?: continue
                val name = t.str("name")
                val url = t.str("url")
                if (name.isNotEmpty() && url.isNotEmpty()) tags.add(Source(name, url))
            }
        }

        return StreamInfo(
            pageUrl = pageUrl,
            title = (o?.str("title") ?: "").ifEmpty { "Video" },
            hlsUrl = hls.firstOrNull(),
            mp4 = mp4,
            headers = headers,
            thumb = o?.str("thumb") ?: "",
            durationSec = o?.optInt("dur", 0) ?: 0,
            uploader = o?.str("up") ?: "",
            uploaderUrl = o?.str("upUrl") ?: "",
            description = o?.str("desc") ?: "",
            tags = tags,
            related = parseVideos(o?.optJSONArray("related"), Video.idFor(pageUrl)),
            views = o?.str("views") ?: "",
            likes = o?.str("likes") ?: "",
            hlsFallbacks = if (hls.size > 1) hls.drop(1) else emptyList()
        )
    }
}
