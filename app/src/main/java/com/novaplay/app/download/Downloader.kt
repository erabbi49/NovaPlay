package com.novaplay.app.download

import java.io.File
import java.io.IOException

class DownloadRequest(
    val id: String,
    val url: String,
    val headers: Map<String, String>,
    val target: File,
    /** <= 0 means "best available" (only used for HLS master playlists). */
    val preferredHeight: Int
)

fun interface DownloadListener {
    /** totalBytes / fraction are -1 when unknown. */
    fun onProgress(downloadedBytes: Long, totalBytes: Long, fraction: Float)
}

class DownloadCancelled : Exception("Download cancelled")

class DownloadFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

class Probe(
    val isHls: Boolean,
    /** Extension the finished file should get: mp4, ts, webm ... */
    val ext: String,
    /** Available heights, highest first (HLS master playlists only). */
    val heights: List<Int>,
    val totalBytes: Long,
    val durationSec: Double
)

/** Facade over [ProgressiveDownloader] and [HlsDownloader]. Blocking; call from a background thread. */
class Downloader {
    private val hls = HlsDownloader()
    private val progressive = ProgressiveDownloader()

    fun probe(url: String, headers: Map<String, String>): Probe {
        if (looksLikeHls(url, headers)) return probeHls(url, headers)
        return probeProgressive(url, headers)
    }

    /** Blocks until done. Cancelling keeps "<target>.part" so a later call can resume. */
    fun download(req: DownloadRequest, listener: DownloadListener, isCancelled: () -> Boolean): File {
        return if (looksLikeHls(req.url, req.headers)) {
            hls.download(req, listener, isCancelled)
        } else {
            progressive.download(req, listener, isCancelled)
        }
    }

    private fun looksLikeHls(url: String, headers: Map<String, String>): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".m3u8")) return true
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".mov")) return false
        // Unknown extension: look at the first bytes.
        return try {
            val conn = Http.open(url, headers, 0L, 1023L)
            try {
                val buf = ByteArray(16)
                val n = conn.inputStream.use { it.read(buf) }
                n >= 7 && String(buf, 0, n, Charsets.ISO_8859_1).startsWith("#EXTM3U")
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun probeHls(url: String, headers: Map<String, String>): Probe {
        try {
            val first = M3u8.parse(Http.readText(url, headers), url)
            var heights: List<Int> = emptyList()
            var bandwidth = 0L
            val media: Playlist.Media
            if (first is Playlist.Master) {
                heights = first.variants.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
                val best = hls.pickVariant(first.variants, 0)
                bandwidth = best.bandwidth
                val second = M3u8.parse(Http.readText(best.url, headers), best.url)
                if (second !is Playlist.Media) throw DownloadFailed("Variant playlist is not a media playlist")
                media = second
            } else {
                media = first as Playlist.Media
            }
            val duration = media.totalDurationSec
            val total = if (bandwidth > 0L) (bandwidth.toDouble() * duration / 8.0).toLong() else -1L
            return Probe(true, M3u8.extensionFor(media), heights, total, duration)
        } catch (e: M3u8Exception) {
            throw DownloadFailed(e.message ?: "Unsupported playlist", e)
        } catch (e: IOException) {
            throw DownloadFailed("Could not read playlist: ${e.message}", e)
        }
    }

    private fun probeProgressive(url: String, headers: Map<String, String>): Probe {
        var total = -1L
        try {
            val conn = Http.open(url, headers, 0L, 0L)
            try {
                val code = conn.responseCode
                if (code == 206) {
                    total = conn.getHeaderField("Content-Range")
                        ?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                } else if (code == 200) {
                    total = conn.contentLengthLong
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw DownloadFailed("Could not reach the video: ${e.message}", e)
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "mp4").takeIf { it in KNOWN_EXT } ?: "mp4"
        return Probe(false, ext, emptyList(), total, 0.0)
    }

    companion object {
        private val KNOWN_EXT = setOf("mp4", "webm", "mkv", "mov", "m4v", "ts", "flv")

        /** Removes the resumable leftovers of a download that is cancelled for good. */
        fun deletePartial(target: File) {
            File(target.path + ".part").delete()
            File(target.path + ".state").delete()
        }
    }
}
