package com.novaplay.app.download

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Tiny HttpURLConnection helpers shared by the progressive and HLS downloaders. Pure JVM. */
internal object Http {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val MAX_REDIRECTS = 8

    /**
     * Opens [url] and follows redirects by hand (also http <-> https).
     * 4xx/5xx raise [HttpStatusException] except 416, which is returned so callers can handle it.
     */
    fun open(
        url: String,
        headers: Map<String, String>,
        rangeStart: Long = -1L,
        rangeEnd: Long = -1L,
        timeoutMs: Int = 20000
    ): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            var hasUa = false
            for ((k, v) in headers) {
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                conn.setRequestProperty(k, v)
            }
            if (!hasUa) conn.setRequestProperty("User-Agent", DEFAULT_UA)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (rangeStart >= 0L) {
                val value = if (rangeEnd >= 0L) "bytes=$rangeStart-$rangeEnd" else "bytes=$rangeStart-"
                conn.setRequestProperty("Range", value)
            }
            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                hops++
                if (loc == null || hops > MAX_REDIRECTS) throw IOException("Too many redirects")
                current = URL(URL(current), loc).toExternalForm()
                continue
            }
            if (code >= 400 && code != 416) {
                conn.disconnect()
                throw HttpStatusException(code)
            }
            return conn
        }
    }

    /** Reads a whole (small/medium) resource into memory; honours an optional byte range even if the server ignores it. */
    fun readBytes(
        url: String,
        headers: Map<String, String>,
        rangeStart: Long = -1L,
        rangeEnd: Long = -1L,
        isCancelled: () -> Boolean = { false },
        maxBytes: Int = 96 * 1024 * 1024
    ): ByteArray {
        val conn = open(url, headers, rangeStart, rangeEnd)
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw HttpStatusException(code)
            val out = ByteArrayOutputStream(
                if (conn.contentLength in 1..maxBytes) conn.contentLength else 64 * 1024
            )
            val buf = ByteArray(32 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    if (isCancelled()) throw DownloadCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Resource too large")
                }
            }
            val all = out.toByteArray()
            if (rangeStart >= 0L && code == 200) {
                // Server ignored the Range header and sent everything: slice it ourselves.
                val from = rangeStart.toInt().coerceAtMost(all.size)
                val to = if (rangeEnd >= 0L) (rangeEnd + 1).toInt().coerceAtMost(all.size) else all.size
                return all.copyOfRange(from, to)
            }
            return all
        } finally {
            conn.disconnect()
        }
    }

    fun readText(url: String, headers: Map<String, String>): String {
        return String(readBytes(url, headers, maxBytes = 8 * 1024 * 1024), Charsets.UTF_8)
    }

    /** Sleeps up to [ms] but wakes early (throwing) when cancelled. */
    fun sleepCancellable(ms: Long, isCancelled: () -> Boolean) {
        var left = ms
        while (left > 0L) {
            if (isCancelled()) throw DownloadCancelled()
            val step = if (left > 100L) 100L else left
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DownloadCancelled()
            }
            left -= step
        }
    }

    fun backoffMs(failures: Int): Long {
        val shift = if (failures < 1) 0 else if (failures > 4) 4 else failures - 1
        return 1000L shl shift
    }
}
