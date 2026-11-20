package com.novaplay.app.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Plain HTTP file download with Range resume into "<target>.part". Pure JVM. */
internal class ProgressiveDownloader {

    fun download(req: DownloadRequest, listener: DownloadListener, isCancelled: () -> Boolean): File {
        val part = File(req.target.path + ".part")
        part.parentFile?.mkdirs()
        var failures = 0
        var lastLen = if (part.exists()) part.length() else 0L
        while (true) {
            if (isCancelled()) throw DownloadCancelled()
            try {
                return attempt(req, part, listener, isCancelled)
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: HttpStatusException) {
                val fatal = e.code in 400..499 && e.code != 408 && e.code != 429
                if (fatal) throw DownloadFailed("Server refused the download (HTTP ${e.code})", e)
                failures++
                if (failures > MAX_FAILURES) throw DownloadFailed("Download failed (HTTP ${e.code})", e)
            } catch (e: IOException) {
                val len = if (part.exists()) part.length() else 0L
                if (len > lastLen) {
                    failures = 0
                    lastLen = len
                } else {
                    failures++
                }
                if (failures > MAX_FAILURES) throw DownloadFailed("Network error: ${e.message}", e)
            }
            Http.sleepCancellable(Http.backoffMs(failures), isCancelled)
        }
    }

    private fun attempt(
        req: DownloadRequest,
        part: File,
        listener: DownloadListener,
        isCancelled: () -> Boolean
    ): File {
        var have = if (part.exists()) part.length() else 0L
        val conn = Http.open(req.url, req.headers, if (have > 0L) have else -1L)
        try {
            val code = conn.responseCode
            var total = -1L
            var append = true
            when (code) {
                206 -> {
                    val range = conn.getHeaderField("Content-Range")
                    total = range?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    if (total < 0L && conn.contentLengthLong >= 0L) total = have + conn.contentLengthLong
                }
                200 -> {
                    total = conn.contentLengthLong
                    if (have > 0L) {
                        // The server ignored our Range header, so start again from byte 0.
                        append = false
                        have = 0L
                    }
                }
                416 -> {
                    val range = conn.getHeaderField("Content-Range")
                    val t = range?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    if (t > 0L && have == t) {
                        finish(part, req.target)
                        listener.onProgress(t, t, 1f)
                        return req.target
                    }
                    part.delete()
                    throw IOException("Range not satisfiable; restarting")
                }
                else -> throw HttpStatusException(code)
            }

            FileOutputStream(part, append).use { out ->
                val input = conn.inputStream
                val buf = ByteArray(64 * 1024)
                var done = have
                var lastReport = 0L
                while (true) {
                    if (isCancelled()) throw DownloadCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 250L) {
                        lastReport = now
                        report(listener, done, total)
                    }
                }
                out.flush()
                if (total >= 0L && done != total) {
                    throw IOException("Connection closed early ($done of $total bytes)")
                }
                report(listener, done, if (total >= 0L) total else done)
            }
            finish(part, req.target)
            return req.target
        } finally {
            conn.disconnect()
        }
    }

    private fun report(listener: DownloadListener, done: Long, total: Long) {
        val fraction = if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
        listener.onProgress(done, total, fraction)
    }

    private fun finish(part: File, target: File) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    companion object {
        private const val MAX_FAILURES = 4
    }
}
