package com.novaplay.app.download

import java.net.URL

/** Thrown for malformed or unsupported playlists (SAMPLE-AES, live streams, empty playlists ...). */
class M3u8Exception(message: String) : Exception(message)

/** Absolute byte range inside a resource (offset already resolved, never "continue from previous"). */
class ByteRange(val offset: Long, val length: Long)

class Variant(
    val url: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecs: String?,
    /** AUDIO group id; when set the audio may live in a separate rendition that this downloader does not merge. */
    val audioGroup: String? = null
)

class Segment(
    val url: String,
    val durationSec: Double,
    /** Index into [Playlist.Media.keys], or null when the segment is not encrypted. */
    val keyIndex: Int?,
    val byteRange: ByteRange?
)

/** [iv] is the raw attribute text ("0x...") or null (then the media sequence number is the IV). */
class KeyInfo(val method: String, val uri: String, val iv: String?)

sealed class Playlist {
    class Master(val variants: List<Variant>) : Playlist()

    class Media(
        val segments: List<Segment>,
        val initSegmentUrl: String?,
        val keys: List<KeyInfo>,
        val totalDurationSec: Double,
        val endList: Boolean,
        val isLive: Boolean,
        /** EXT-X-MEDIA-SEQUENCE of the first segment (needed for the default AES IV). */
        val mediaSequence: Long = 0L,
        val initByteRange: ByteRange? = null,
        val initKeyIndex: Int? = null
    ) : Playlist() {
        val isEncrypted: Boolean get() = keys.isNotEmpty()
    }
}

object M3u8 {

    /**
     * Parses a master or media playlist. Relative URLs are resolved against [baseUrl].
     * Live playlists (no EXT-X-ENDLIST and not PLAYLIST-TYPE:VOD) are rejected unless [allowLive].
     */
    fun parse(text: String, baseUrl: String, allowLive: Boolean = false): Playlist {
        val raw = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
            throw M3u8Exception("Not an M3U8 playlist")
        }
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        return if (isMaster) parseMaster(lines, baseUrl) else parseMedia(lines, baseUrl, allowLive)
    }

    /** File extension that matches the container produced by concatenating the segments ("ts", "mp4", "aac", ...). */
    fun extensionFor(media: Playlist.Media): String {
        if (media.initSegmentUrl != null) return "mp4"
        val first = media.segments.firstOrNull()?.url ?: return "ts"
        val path = first.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".cmfv") || path.endsWith(".m4v") -> "mp4"
            path.endsWith(".m4a") || path.endsWith(".cmfa") -> "m4a"
            path.endsWith(".aac") -> "aac"
            path.endsWith(".mp3") -> "mp3"
            path.endsWith(".webm") -> "webm"
            else -> "ts"
        }
    }

    fun resolveUrl(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http://", ignoreCase = true) || r.startsWith("https://", ignoreCase = true)) return r
        return try {
            URL(URL(base), r).toExternalForm()
        } catch (e: Exception) {
            r
        }
    }

    /** Parses `KEY=value,KEY2="quoted, value"` attribute lists. Keys are upper-cased. */
    fun parseAttributes(s: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val n = s.length
        var i = 0
        while (i < n) {
            while (i < n && (s[i] == ',' || s[i] == ' ')) i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().uppercase()
            i = eq + 1
            val value: String
            if (i < n && s[i] == '"') {
                var end = s.indexOf('"', i + 1)
                if (end < 0) end = n
                value = s.substring(i + 1, end)
                i = end + 1
            } else {
                var end = s.indexOf(',', i)
                if (end < 0) end = n
                value = s.substring(i, end).trim()
                i = end
            }
            map[key] = value
        }
        return map
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): Playlist {
        val variants = ArrayList<Variant>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = parseAttributes(line.substring("#EXT-X-STREAM-INF:".length))
            } else if (line.startsWith("#")) {
                continue // other tags, including EXT-X-I-FRAME-STREAM-INF and EXT-X-MEDIA
            } else if (pending != null) {
                val a: Map<String, String> = pending
                pending = null
                val bw = a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                var w = 0
                var h = 0
                val res = a["RESOLUTION"]
                if (res != null) {
                    val p = res.lowercase().split('x')
                    if (p.size == 2) {
                        w = p[0].trim().toIntOrNull() ?: 0
                        h = p[1].trim().toIntOrNull() ?: 0
                    }
                }
                variants.add(Variant(resolveUrl(baseUrl, line), bw, w, h, a["CODECS"], a["AUDIO"]))
            }
        }
        if (variants.isEmpty()) throw M3u8Exception("Master playlist has no variants")
        return Playlist.Master(variants)
    }

    /** "length[@offset]" -> absolute range. [lastEnd] is the end of the previous range of the same resource. */
    private fun parseRange(raw: String, lastEnd: Long?): ByteRange? {
        val parts = raw.trim().split('@')
        val length = parts[0].trim().toLongOrNull() ?: return null
        val offset = if (parts.size > 1) parts[1].trim().toLongOrNull() ?: 0L else (lastEnd ?: 0L)
        return ByteRange(offset, length)
    }

    private fun parseMedia(lines: List<String>, baseUrl: String, allowLive: Boolean): Playlist {
        val segments = ArrayList<Segment>()
        val keys = ArrayList<KeyInfo>()
        var curKey: Int? = null
        var initUrl: String? = null
        var initRange: ByteRange? = null
        var initKey: Int? = null
        var seq = 0L
        var endList = false
        var playlistType: String? = null
        var pendingDur = 0.0
        var pendingRangeRaw: String? = null
        var total = 0.0
        val lastEnd = HashMap<String, Long>()

        for (line in lines) {
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTINF:") ->
                        pendingDur = line.substring(8).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    line.startsWith("#EXT-X-BYTERANGE:") ->
                        pendingRangeRaw = line.substring("#EXT-X-BYTERANGE:".length)
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        seq = line.substring("#EXT-X-MEDIA-SEQUENCE:".length).trim().toLongOrNull() ?: 0L
                    line.startsWith("#EXT-X-PLAYLIST-TYPE:") ->
                        playlistType = line.substring("#EXT-X-PLAYLIST-TYPE:".length).trim()
                    line.startsWith("#EXT-X-ENDLIST") -> endList = true
                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = parseAttributes(line.substring("#EXT-X-KEY:".length))
                        val method = (a["METHOD"] ?: "").uppercase()
                        when {
                            method == "NONE" -> curKey = null
                            method == "AES-128" -> {
                                val kf = a["KEYFORMAT"]
                                if (kf != null && !kf.equals("identity", ignoreCase = true)) {
                                    throw M3u8Exception("Unsupported key format: $kf")
                                }
                                val uri = a["URI"] ?: throw M3u8Exception("AES-128 key without URI")
                                keys.add(KeyInfo("AES-128", resolveUrl(baseUrl, uri), a["IV"]))
                                curKey = keys.size - 1
                            }
                            method.startsWith("SAMPLE-AES") ->
                                throw M3u8Exception("SAMPLE-AES encryption (DRM) is not supported")
                            else -> throw M3u8Exception("Unsupported encryption method: $method")
                        }
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val a = parseAttributes(line.substring("#EXT-X-MAP:".length))
                        val uri = a["URI"] ?: throw M3u8Exception("EXT-X-MAP without URI")
                        val u = resolveUrl(baseUrl, uri)
                        if (initUrl != null && initUrl != u) {
                            throw M3u8Exception("Playlist switches init segments (EXT-X-MAP); not supported")
                        }
                        if (initUrl == null) {
                            initUrl = u
                            initRange = a["BYTERANGE"]?.let { parseRange(it, 0L) }
                            initKey = curKey
                        }
                    }
                }
            } else {
                val url = resolveUrl(baseUrl, line)
                var range: ByteRange? = null
                val rawRange = pendingRangeRaw
                if (rawRange != null) {
                    range = parseRange(rawRange, lastEnd[url])
                    if (range != null) lastEnd[url] = range.offset + range.length
                }
                segments.add(Segment(url, pendingDur, curKey, range))
                total += pendingDur
                pendingDur = 0.0
                pendingRangeRaw = null
            }
        }

        if (segments.isEmpty()) throw M3u8Exception("Playlist has no segments")
        val live = !endList && !"VOD".equals(playlistType, ignoreCase = true)
        if (live && !allowLive) throw M3u8Exception("Live streams are not supported")
        return Playlist.Media(segments, initUrl, keys, total, endList, live, seq, initRange, initKey)
    }
}
