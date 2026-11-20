package com.novaplay.app.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads a VOD HLS stream by fetching every segment (4 in parallel, written in order) and
 * concatenating them into one file. Works for MPEG-TS and for fMP4 (init segment first).
 * Resumable: progress is kept in "<target>.part" + "<target>.state". Pure JVM.
 */
internal class HlsDownloader {

    private class Piece(val url: String, val range: ByteRange?, val keyIndex: Int?, val seq: Long)

    private class State(val fingerprint: String, val total: Int, val done: Int, val length: Long)

    /** Best height <= [preferredHeight]; if none, the smallest above it. preferredHeight <= 0 means "best". */
    fun pickVariant(variants: List<Variant>, preferredHeight: Int): Variant {
        val sorted = variants.sortedWith(compareBy<Variant>({ it.height }, { it.bandwidth }))
        if (preferredHeight <= 0) return sorted.last()
        val within = sorted.filter { it.height in 1..preferredHeight }
        if (within.isNotEmpty()) return within.last()
        val above = sorted.filter { it.height > preferredHeight }
        if (above.isNotEmpty()) return above.first()
        return sorted.last()
    }

    /** Loads the media playlist (resolving a master playlist first). Returns playlist, its URL and the chosen variant (if any). */
    fun loadMedia(
        url: String,
        headers: Map<String, String>,
        preferredHeight: Int
    ): Triple<Playlist.Media, String, Variant?> {
        val first = M3u8.parse(Http.readText(url, headers), url)
        if (first is Playlist.Media) return Triple(first, url, null)
        val master = first as Playlist.Master
        val variant = pickVariant(master.variants, preferredHeight)
        val second = M3u8.parse(Http.readText(variant.url, headers), variant.url)
        if (second !is Playlist.Media) throw DownloadFailed("Variant playlist is not a media playlist")
        return Triple(second, variant.url, variant)
    }

    fun download(req: DownloadRequest, listener: DownloadListener, isCancelled: () -> Boolean): File {
        val loaded = loadWithRetry(req, isCancelled)
        val media = loaded.first
        val pieces = ArrayList<Piece>()
        val initUrl = media.initSegmentUrl
        if (initUrl != null) {
            pieces.add(Piece(initUrl, media.initByteRange, media.initKeyIndex, media.mediaSequence))
        }
        for (i in media.segments.indices) {
            val s = media.segments[i]
            pieces.add(Piece(s.url, s.byteRange, s.keyIndex, media.mediaSequence + i))
        }
        val fingerprint = pieces.size.toString() + "|" +
            pieces.first().url.substringBefore('?') + "|" + pieces.last().url.substringBefore('?')

        val part = File(req.target.path + ".part")
        val stateFile = File(req.target.path + ".state")
        part.parentFile?.mkdirs()

        var startIndex = 0
        var written = 0L
        val saved = readState(stateFile)
        if (saved != null && part.exists() && saved.fingerprint == fingerprint &&
            saved.total == pieces.size && saved.done in 0..pieces.size && saved.length <= part.length()
        ) {
            startIndex = saved.done
            written = saved.length
            RandomAccessFile(part, "rw").use { it.setLength(saved.length) }
        } else {
            part.delete()
            stateFile.delete()
        }

        val keyCache = arrayOfNulls<ByteArray>(media.keys.size)

        fun keyBytes(index: Int): ByteArray {
            synchronized(keyCache) {
                val cached = keyCache[index]
                if (cached != null) return cached
                val fetched = Http.readBytes(media.keys[index].uri, req.headers, isCancelled = isCancelled)
                if (fetched.size != 16) throw DownloadFailed("Unexpected AES key length: ${fetched.size}")
                keyCache[index] = fetched
                return fetched
            }
        }

        fun decrypt(data: ByteArray, keyIndex: Int, seq: Long): ByteArray {
            if (data.isEmpty()) return data
            val iv = media.keys[keyIndex].iv?.let { ivFromHex(it) } ?: ivFromSequence(seq)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(keyIndex), "AES"), IvParameterSpec(iv))
            return cipher.doFinal(data)
        }

        fun fetch(p: Piece): ByteArray {
            var failures = 0
            while (true) {
                if (isCancelled()) throw DownloadCancelled()
                try {
                    val range = p.range
                    val raw = if (range != null) {
                        Http.readBytes(p.url, req.headers, range.offset, range.offset + range.length - 1, isCancelled)
                    } else {
                        Http.readBytes(p.url, req.headers, isCancelled = isCancelled)
                    }
                    val ki = p.keyIndex
                    return if (ki != null) decrypt(raw, ki, p.seq) else raw
                } catch (e: DownloadCancelled) {
                    throw e
                } catch (e: DownloadFailed) {
                    throw e
                } catch (e: HttpStatusException) {
                    val fatal = e.code in 400..499 && e.code != 408 && e.code != 429
                    if (fatal) throw DownloadFailed("Segment refused (HTTP ${e.code})", e)
                    failures++
                    if (failures > SEGMENT_RETRIES) throw DownloadFailed("Segment failed (HTTP ${e.code})", e)
                } catch (e: IOException) {
                    failures++
                    if (failures > SEGMENT_RETRIES) throw DownloadFailed("Network error: ${e.message}", e)
                } catch (e: GeneralSecurityException) {
                    throw DownloadFailed("Decryption failed: ${e.message}", e)
                }
                Http.sleepCancellable(Http.backoffMs(failures), isCancelled)
            }
        }

        val pool = Executors.newFixedThreadPool(WORKERS)
        val pending = java.util.ArrayDeque<Future<ByteArray>>()
        var next = startIndex
        var done = startIndex
        val out = FileOutputStream(part, true)
        try {
            while (done < pieces.size) {
                while (next < pieces.size && pending.size < WINDOW) {
                    val p = pieces[next]
                    pending.addLast(pool.submit(Callable<ByteArray> { fetch(p) }))
                    next++
                }
                val bytes = awaitFuture(pending.removeFirst(), isCancelled)
                out.write(bytes)
                out.flush()
                written += bytes.size
                done++
                writeState(stateFile, fingerprint, pieces.size, done, written)
                val fraction = done.toFloat() / pieces.size.toFloat()
                val estimate = if (done > 0) (written.toDouble() / done * pieces.size).toLong() else -1L
                listener.onProgress(written, estimate, fraction)
            }
        } finally {
            pool.shutdownNow()
            out.close()
        }

        if (req.target.exists()) req.target.delete()
        if (!part.renameTo(req.target)) {
            part.copyTo(req.target, overwrite = true)
            part.delete()
        }
        stateFile.delete()
        listener.onProgress(written, written, 1f)
        return req.target
    }

    private fun loadWithRetry(
        req: DownloadRequest,
        isCancelled: () -> Boolean
    ): Triple<Playlist.Media, String, Variant?> {
        var failures = 0
        while (true) {
            if (isCancelled()) throw DownloadCancelled()
            try {
                return loadMedia(req.url, req.headers, req.preferredHeight)
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: M3u8Exception) {
                throw DownloadFailed(e.message ?: "Unsupported playlist", e)
            } catch (e: DownloadFailed) {
                throw e
            } catch (e: HttpStatusException) {
                val fatal = e.code in 400..499 && e.code != 408 && e.code != 429
                if (fatal) throw DownloadFailed("Playlist refused (HTTP ${e.code})", e)
                failures++
                if (failures > SEGMENT_RETRIES) throw DownloadFailed("Playlist failed (HTTP ${e.code})", e)
            } catch (e: IOException) {
                failures++
                if (failures > SEGMENT_RETRIES) throw DownloadFailed("Could not load playlist: ${e.message}", e)
            }
            Http.sleepCancellable(Http.backoffMs(failures), isCancelled)
        }
    }

    private fun awaitFuture(f: Future<ByteArray>, isCancelled: () -> Boolean): ByteArray {
        while (true) {
            if (isCancelled()) {
                f.cancel(true)
                throw DownloadCancelled()
            }
            try {
                return f.get(200, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // keep waiting
            } catch (e: ExecutionException) {
                val c = e.cause
                if (c is DownloadCancelled) throw c
                if (c is DownloadFailed) throw c
                throw DownloadFailed("Segment download failed: ${c?.message}", c)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DownloadCancelled()
            }
        }
    }

    private fun readState(f: File): State? {
        if (!f.exists()) return null
        return try {
            val lines = f.readText().split('\n')
            State(
                fingerprint = lines[0],
                total = lines[1].trim().toInt(),
                done = lines[2].trim().toInt(),
                length = lines[3].trim().toLong()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeState(f: File, fingerprint: String, total: Int, done: Int, length: Long) {
        try {
            f.writeText("$fingerprint\n$total\n$done\n$length\n")
        } catch (e: IOException) {
            // state is only an optimisation for resuming
        }
    }

    private fun ivFromHex(raw: String): ByteArray {
        var hex = raw.trim()
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2)
        hex = hex.padStart(32, '0')
        if (hex.length > 32) hex = hex.substring(hex.length - 32)
        val out = ByteArray(16)
        for (i in 0 until 16) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun ivFromSequence(seq: Long): ByteArray {
        val out = ByteArray(16)
        var v = seq
        for (i in 15 downTo 8) {
            out[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return out
    }

    companion object {
        private const val WORKERS = 4
        private const val WINDOW = 8
        private const val SEGMENT_RETRIES = 3
    }
}
