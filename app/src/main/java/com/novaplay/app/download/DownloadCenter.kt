package com.novaplay.app.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.novaplay.app.data.DlState
import com.novaplay.app.data.DownloadItem
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.StreamInfo
import com.novaplay.app.data.Video
import com.novaplay.app.web.SiteClient
import com.novaplay.app.web.SiteException
import com.novaplay.app.web.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The in-app download manager: a small queue (two at a time) on top of [Downloader].
 * The list itself lives in [Library] so the UI can observe it; this object only drives it.
 * Every public function must be called on the main thread.
 */
object DownloadCenter {
    private const val MAX_PARALLEL = 2
    const val WAITING_WIFI = "Waiting for Wi-Fi"

    private class Run(val id: String, val fresh: Boolean) {
        /** Set when the transfer must stop (pause, remove, metered network). Read from the worker thread. */
        @Volatile
        var stop = false
        var removed = false
        var job: Job? = null
    }

    /** What the notification shows. [fraction] is -1 when no running item knows its size. */
    class Summary(val running: Int, val queued: Int, val fraction: Float, val title: String)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloader = Downloader()
    private val runs = HashMap<String, Run>()

    /** Items whose next start must resolve the page again (the old signed URL may have expired). */
    private val refresh = HashSet<String>()

    fun init(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        watchNetwork()
    }

    // ---------- public API ----------

    /** Adds a video to the download list (or resumes / restarts it). [quality] 0 = best available. */
    fun enqueue(video: Video, requested: Int = Prefs.downloadQuality) {
        val quality = requested.coerceAtLeast(0) // -1 ("ask every time") is resolved by the caller
        val existing = Library.downloadFor(video.id)
        if (existing != null) {
            if (existing.state == DlState.DONE) {
                if (existing.filePath.isNotEmpty() && File(existing.filePath).exists()) {
                    toast("Already downloaded")
                    return
                }
                // The file was deleted behind our back: download again.
                existing.filePath = ""
                existing.bytes = 0L
                existing.totalBytes = -1L
                existing.progress = 0f
            } else if (existing.state == DlState.RUNNING) {
                toast("Already downloading")
                return
            }
            existing.quality = quality
            existing.state = DlState.QUEUED
            existing.error = ""
            refresh.add(existing.id)
            Library.putDownload(existing)
            toast("Added to downloads")
            pump()
            return
        }
        val item = DownloadItem(
            id = video.id,
            video = video,
            state = DlState.QUEUED,
            progress = 0f,
            bytes = 0L,
            totalBytes = -1L,
            filePath = "",
            error = "",
            quality = quality,
            createdAt = System.currentTimeMillis()
        )
        Library.putDownload(item)
        toast("Download started")
        pump()
    }

    fun pause(id: String) {
        val item = Library.downloadFor(id) ?: return
        if (item.state != DlState.RUNNING && item.state != DlState.QUEUED) return
        item.state = DlState.PAUSED
        item.error = ""
        Library.putDownload(item)
        stopRun(id)
    }

    /** Resumes a paused download or retries a failed one. */
    fun resume(id: String) {
        val item = Library.downloadFor(id) ?: return
        if (item.state == DlState.DONE || item.state == DlState.RUNNING) return
        refresh.add(id)
        item.state = DlState.QUEUED
        item.error = ""
        Library.putDownload(item)
        pump()
    }

    /** Removes the item from the list and deletes its file (finished or partial). */
    fun remove(id: String) {
        val item = Library.downloadFor(id) ?: return
        Library.removeDownload(id)
        refresh.remove(id)
        val run = runs[id]
        if (run != null) {
            run.removed = true
            run.stop = true
            run.job?.cancel()
        } else {
            deleteFiles(item)
        }
        pump()
    }

    fun pauseAll() {
        for (d in Library.downloadList()) {
            if (d.state == DlState.RUNNING || d.state == DlState.QUEUED) pause(d.id)
        }
    }

    fun resumeAll() {
        for (d in Library.downloadList()) {
            if (d.state == DlState.PAUSED || d.state == DlState.FAILED) resume(d.id)
        }
    }

    fun isBusy(): Boolean = runs.isNotEmpty()

    fun summary(): Summary {
        var running = 0
        var queued = 0
        var known = 0
        var sum = 0f
        var title = ""
        for (d in Library.downloadList()) {
            if (d.state == DlState.RUNNING) {
                running++
                if (title.isEmpty()) title = d.video.title
                if (d.progress >= 0f) {
                    known++
                    sum += d.progress
                }
            } else if (d.state == DlState.QUEUED && d.error != WAITING_WIFI) {
                queued++
            }
        }
        return Summary(running, queued, if (known == 0) -1f else sum / known, title)
    }

    /** Folder that holds finished downloads and partial files. */
    fun downloadDir(): File {
        val base = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir
        val dir = File(base, "NovaPlay")
        dir.mkdirs()
        return dir
    }

    /** Copies a finished download into the shared Movies/NovaPlay folder (Android 10+), so the gallery and file managers see it. */
    fun saveToGallery(item: DownloadItem, onResult: (String) -> Unit) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { copyOut(item) }
            onResult(error ?: "Saved in Movies/NovaPlay")
        }
    }

    // ---------- scheduling ----------

    private fun pump() {
        val blocked = Prefs.wifiOnlyDownloads && isMetered()
        val queue = Library.downloadList().filter { it.state == DlState.QUEUED && !runs.containsKey(it.id) }
        if (blocked) {
            for (item in queue) {
                if (item.error != WAITING_WIFI) {
                    item.error = WAITING_WIFI
                    Library.putDownload(item, false)
                }
            }
        } else {
            for (item in queue.sortedBy { it.createdAt }) {
                if (runs.size >= MAX_PARALLEL) break
                start(item)
            }
        }
        if (runs.isNotEmpty()) startService()
    }

    private fun start(item: DownloadItem) {
        val run = Run(item.id, refresh.remove(item.id))
        runs[item.id] = run
        item.state = DlState.RUNNING
        item.error = ""
        Library.putDownload(item)
        run.job = scope.launch {
            var file: File? = null
            var failure: String? = null
            try {
                val info = SiteClient.resolve(item.video.url, run.fresh)
                if (run.stop) throw DownloadCancelled()
                file = withContext(Dispatchers.IO) { transfer(item, info, run) }
            } catch (e: DownloadCancelled) {
                // paused, removed or waiting for Wi-Fi: whoever asked already updated the item
            } catch (e: CancellationException) {
                // same, cancelled while the page was being resolved
            } catch (e: SiteException) {
                failure = e.userMessage()
            } catch (e: DownloadFailed) {
                failure = e.message ?: "Download failed"
            } catch (e: IOException) {
                failure = "Network error: ${e.message}"
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                ended(item, run, file, failure)
            }
        }
    }

    private fun ended(item: DownloadItem, run: Run, file: File?, failure: String?) {
        runs.remove(item.id)
        when {
            run.removed -> deleteFiles(item)
            file != null -> {
                item.state = DlState.DONE
                item.progress = 1f
                item.filePath = file.path
                item.bytes = file.length()
                item.totalBytes = item.bytes
                item.error = ""
                Library.putDownload(item)
                DownloadService.notifyDone(app, item)
            }
            run.stop -> Unit
            else -> {
                item.state = DlState.FAILED
                item.error = failure ?: "Stopped unexpectedly"
                Library.putDownload(item)
            }
        }
        pump()
    }

    private fun stopRun(id: String) {
        val run = runs[id] ?: return
        run.stop = true
        run.job?.cancel()
    }

    // ---------- the transfer itself (worker thread) ----------

    private fun transfer(item: DownloadItem, info: StreamInfo, run: Run): File {
        val urls = candidates(info, item.quality)
        if (urls.isEmpty()) throw DownloadFailed("No downloadable stream found")
        var last: Exception? = null
        for (url in urls) {
            if (run.stop) throw DownloadCancelled()
            val moved = LongArray(1)
            try {
                return fetch(item, url, info.headers, run, moved)
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: DownloadFailed) {
                last = e
            } catch (e: IOException) {
                last = e
            }
            // Try the next stream only if this one never delivered anything; otherwise keep the partial file for a retry.
            if (moved[0] > 0L) break
        }
        val err = last
        if (err is DownloadFailed) throw err
        throw DownloadFailed(err?.message ?: "Download failed", err)
    }

    private fun fetch(item: DownloadItem, url: String, headers: Map<String, String>, run: Run, moved: LongArray): File {
        val probe = downloader.probe(url, headers)
        if (run.stop) throw DownloadCancelled()
        val target = targetFor(item, probe.ext)
        main.post {
            item.filePath = target.path
            if (probe.totalBytes > 0L) item.totalBytes = probe.totalBytes
            Library.putDownload(item)
        }
        var lastTick = 0L
        val listener = DownloadListener { bytes, total, fraction ->
            moved[0] = bytes
            val now = SystemClock.elapsedRealtime()
            if (now - lastTick >= 400L) {
                lastTick = now
                main.post {
                    item.bytes = bytes
                    item.totalBytes = total
                    item.progress = fraction
                    Library.putDownload(item, false)
                }
            }
        }
        val req = DownloadRequest(item.id, url, headers, target, item.quality)
        return downloader.download(req, listener) { run.stop }
    }

    /** Candidate URLs, best first. HLS goes first (it can pick a height); plain MP4s follow, nearest to the wanted height first. */
    internal fun candidates(info: StreamInfo, quality: Int): List<String> {
        val out = LinkedHashSet<String>()
        info.hlsUrl?.let { out.add(it) }
        out.addAll(info.hlsFallbacks)
        val ordered = if (quality <= 0) {
            info.mp4
        } else {
            info.mp4.filter { it.height in 1..quality }.sortedByDescending { it.height } +
                info.mp4.filter { it.height <= 0 || it.height > quality }.sortedBy { it.height }
        }
        for (m in ordered) out.add(m.url)
        return out.toList()
    }

    /** The same path on every resume, so the ".part" / ".state" leftovers keep matching. */
    private fun targetFor(item: DownloadItem, ext: String): File {
        if (item.filePath.isNotEmpty()) {
            val old = File(item.filePath)
            if (old.extension.equals(ext, true)) return old
            Downloader.deletePartial(old)
        }
        return File(downloadDir(), safeName(item.video) + "." + ext)
    }

    internal fun safeName(v: Video): String {
        val sb = StringBuilder()
        for (ch in v.title) {
            sb.append(if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' || ch == '(' || ch == ')') ch else ' ')
        }
        var base = sb.toString().replace(Regex("\\s+"), " ").trim()
        if (base.length > 70) base = base.substring(0, 70).trim()
        if (base.isEmpty()) base = "video"
        val tag = Integer.toHexString(v.id.hashCode()).padStart(8, '0')
        return "$base-$tag"
    }

    private fun deleteFiles(item: DownloadItem) {
        val path = item.filePath
        if (path.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val f = File(path)
            Downloader.deletePartial(f)
            f.delete()
        }
    }

    // ---------- network ----------

    private fun isMetered(): Boolean {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.isActiveNetworkMetered
        } catch (e: Exception) {
            false
        }
    }

    private fun watchNetwork() {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    main.post { onNetworkChanged() }
                }

                override fun onLost(network: Network) {
                    main.post { onNetworkChanged() }
                }
            })
        } catch (e: Exception) {
            // Without the callback downloads still work; they just don't react to a Wi-Fi switch until the next action.
        }
    }

    private fun onNetworkChanged() {
        if (Prefs.wifiOnlyDownloads && isMetered()) {
            for (id in runs.keys.toList()) {
                val item = Library.downloadFor(id) ?: continue
                item.state = DlState.QUEUED
                item.error = WAITING_WIFI
                Library.putDownload(item)
                stopRun(id)
            }
        }
        pump()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            // Not allowed from the background on newer Android: the transfer keeps going while the process lives.
        }
    }

    // ---------- export ----------

    private fun copyOut(item: DownloadItem): String? {
        if (Build.VERSION.SDK_INT < 29) return "Saving to the gallery needs Android 10 or newer"
        val src = File(item.filePath)
        if (!src.exists()) return "The file is missing"
        val resolver = app.contentResolver
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
        values.put(MediaStore.Video.Media.MIME_TYPE, mimeFor(src.extension))
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NovaPlay")
        values.put(MediaStore.Video.Media.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return "Could not create the file"
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the destination")
            out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
            val done = ContentValues()
            done.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
            return null
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            return e.message ?: "Copy failed"
        }
    }

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        else -> "video/mp4"
    }

    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
    }
}
