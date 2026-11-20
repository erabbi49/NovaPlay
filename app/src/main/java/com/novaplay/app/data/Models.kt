package com.novaplay.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A video as shown in lists. [url] is the site's page for it; streams are resolved lazily when played. */
data class Video(
    val id: String,
    val url: String,
    val title: String,
    val thumb: String = "",
    val durationSec: Int = 0,
    val views: String = "",
    val uploader: String = "",
    val uploaderUrl: String = "",
    val quality: String = "",
    val previewUrl: String = ""
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("url", url)
        o.put("title", title)
        o.put("thumb", thumb)
        o.put("dur", durationSec)
        o.put("views", views)
        o.put("up", uploader)
        o.put("upUrl", uploaderUrl)
        o.put("q", quality)
        o.put("prev", previewUrl)
        return o
    }

    companion object {
        /** Mirrors (xhamster.com / xhamster.desi ...) share one id because only the path is used. */
        fun idFor(url: String): String {
            val rest = url.substringAfter("://", url)
            val path = rest.substringAfter('/', rest)
            return path.substringBefore('?').substringBefore('#').trim('/')
        }

        fun fromJson(o: JSONObject): Video {
            return Video(
                id = o.optString("id"),
                url = o.optString("url"),
                title = o.optString("title"),
                thumb = o.optString("thumb"),
                durationSec = o.optInt("dur", 0),
                views = o.optString("views"),
                uploader = o.optString("up"),
                uploaderUrl = o.optString("upUrl"),
                quality = o.optString("q"),
                previewUrl = o.optString("prev")
            )
        }
    }
}

fun List<Video>.toJsonArray(): JSONArray {
    val a = JSONArray()
    for (v in this) a.put(v.toJson())
    return a
}

fun JSONArray.toVideos(): MutableList<Video> {
    val out = ArrayList<Video>()
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        val v = Video.fromJson(o)
        if (v.url.isNotEmpty()) out.add(v)
    }
    return out
}

/** A history row: the video plus where the user stopped. */
class HistoryEntry(val video: Video, var positionMs: Long, var durationMs: Long, var watchedAt: Long) {
    fun toJson(): JSONObject {
        val o = video.toJson()
        o.put("pos", positionMs)
        o.put("durMs", durationMs)
        o.put("at", watchedAt)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): HistoryEntry {
            return HistoryEntry(Video.fromJson(o), o.optLong("pos"), o.optLong("durMs"), o.optLong("at"))
        }
    }
}

class UserPlaylist(val id: String, var name: String, val videos: MutableList<Video>) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("videos", videos.toJsonArray())
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): UserPlaylist {
            val arr = o.optJSONArray("videos") ?: JSONArray()
            return UserPlaylist(o.optString("id"), o.optString("name"), arr.toVideos())
        }
    }
}

/** A followed uploader / channel / category page. Also used for the custom chips on the Home screen. */
data class Source(val name: String, val url: String) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("url", url)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Source = Source(o.optString("name"), o.optString("url"))
    }
}

object DlState {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val FAILED = 3
    const val DONE = 4
}

class DownloadItem(
    val id: String,
    val video: Video,
    var state: Int,
    var progress: Float,
    var bytes: Long,
    var totalBytes: Long,
    var filePath: String,
    var error: String,
    /** Preferred height, 0 = best available. */
    var quality: Int,
    val createdAt: Long
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("video", video.toJson())
        o.put("state", state)
        o.put("progress", progress.toDouble())
        o.put("bytes", bytes)
        o.put("total", totalBytes)
        o.put("file", filePath)
        o.put("error", error)
        o.put("quality", quality)
        o.put("created", createdAt)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            val v = Video.fromJson(o.optJSONObject("video") ?: JSONObject())
            return DownloadItem(
                id = o.optString("id"),
                video = v,
                state = o.optInt("state"),
                progress = o.optDouble("progress", 0.0).toFloat(),
                bytes = o.optLong("bytes"),
                totalBytes = o.optLong("total", -1L),
                filePath = o.optString("file"),
                error = o.optString("error"),
                quality = o.optInt("quality"),
                createdAt = o.optLong("created")
            )
        }
    }
}

/** One progressive (plain MP4) rendition. */
data class StreamVariant(val label: String, val height: Int, val url: String)

/** Everything the player screen needs once a video page has been opened and its stream sniffed. */
class StreamInfo(
    val pageUrl: String,
    val title: String,
    /** HLS master (or media) playlist, preferred because it adapts and allows quality switching. */
    val hlsUrl: String?,
    /** Plain MP4 renditions, best first. Used when there is no HLS URL. */
    val mp4: List<StreamVariant>,
    /** Headers (Referer, User-Agent, Cookie) the CDN expects. */
    val headers: Map<String, String>,
    val thumb: String,
    val durationSec: Int,
    val uploader: String,
    val uploaderUrl: String,
    val description: String,
    val tags: List<Source>,
    val related: List<Video>,
    val views: String,
    val likes: String,
    /** Backup HLS playlists (other CDN hosts / codecs), tried when [hlsUrl] fails to play. */
    val hlsFallbacks: List<String> = emptyList()
) {
    /** Best single URL for downloading/playing without caring about quality. */
    val bestUrl: String? get() = hlsUrl ?: hlsFallbacks.firstOrNull() ?: mp4.firstOrNull()?.url

    /** Every URL worth trying, best first: main HLS, backup HLS, then plain MP4 renditions. */
    fun candidates(): List<String> {
        val out = LinkedHashSet<String>()
        hlsUrl?.let { out.add(it) }
        out.addAll(hlsFallbacks)
        for (m in mp4) out.add(m.url)
        return out.toList()
    }
}

/** One listing page (home / search / channel) plus where the next one lives. */
class SitePage(val videos: List<Video>, val nextUrl: String?, val title: String = "")
