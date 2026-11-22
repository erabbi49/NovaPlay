package com.novaplay.app.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.novaplay.app.data.DlState
import com.novaplay.app.data.DownloadItem
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.StreamInfo
import com.novaplay.app.data.StreamVariant
import com.novaplay.app.data.Video
import com.novaplay.app.web.SiteClient
import com.novaplay.app.web.SiteException
import com.novaplay.app.web.SiteParser
import com.novaplay.app.web.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one player of the app plus the play queue around it. The queue is a cursor model: videos
 * before [index] were played, the one at [index] is playing, the ones after it are up next.
 * Everything here runs on the main thread.
 */
object Playback {
    enum class Status { IDLE, RESOLVING, READY, ERROR }

    interface Listener {
        fun onVideoChanged() {}
        fun onStatusChanged() {}
        fun onQueueChanged() {}
    }

    class QualityOption(val label: String, val height: Int)

    private const val MAX_PLAYED_KEPT = 40

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var http: DefaultHttpDataSource.Factory? = null
    private var exo: ExoPlayer? = null
    private var job: Job? = null

    private val queueItems = ArrayList<Video>()

    var index = -1
        private set
    var info: StreamInfo? = null
        private set
    var status = Status.IDLE
        private set
    var errorMessage: String? = null
        private set
    var errorKind: SiteException.Kind? = null
        private set

    /** True from the first play until [stop]; the mini player and notification follow this. */
    var hasSession = false
        private set

    /** Set while a downloaded file is playing instead of the online stream. */
    var offline: DownloadItem? = null
        private set

    /** 0 = automatic (adaptive). */
    var forcedHeight = 0
        private set

    var sleepEndsAt = 0L
        private set
    var sleepAtVideoEnd = false
        private set

    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var retriedResolve = false
    private var countedWatch = false
    private var videoActive = true

    val player: ExoPlayer get() = exo ?: error("Playback.init() was not called")
    val current: Video? get() = queueItems.getOrNull(index)
    val isPlayingHls: Boolean get() = candidates.getOrNull(candidateIndex)?.let { SiteParser.kindOf(it) == "hls" } ?: false

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun queue(): List<Video> = ArrayList(queueItems)

    fun init(context: Context) {
        if (exo != null) return
        app = context.applicationContext
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
        http = factory
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val p = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(playerListener)
        p.repeatMode = if (Prefs.repeatMode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo = p
        val saved = Library.loadQueue()
        queueItems.addAll(saved.first)
        index = if (saved.second in queueItems.indices) saved.second else -1
    }

    // ---------- starting playback ----------

    /** Plays [video] right away. It goes in right after the current one so Previous returns to it. */
    fun playNow(video: Video, startMs: Long = -1) {
        val existing = queueItems.indexOfFirst { it.id == video.id }
        if (existing >= 0) {
            index = existing
        } else {
            val at = if (index in queueItems.indices) index + 1 else queueItems.size
            queueItems.add(at, video)
            index = at
        }
        trimPlayed()
        load(index, false, startMs)
    }

    /** Replaces the queue (e.g. "play all" on a playlist) and starts at [start]. */
    fun playAll(videos: List<Video>, start: Int = 0) {
        if (videos.isEmpty()) return
        queueItems.clear()
        queueItems.addAll(videos)
        index = start.coerceIn(0, videos.size - 1)
        load(index, false, -1)
    }

    fun playIndex(i: Int) {
        if (i !in queueItems.indices) return
        index = i
        load(i, false, -1)
    }

    fun retry() {
        if (index !in queueItems.indices) return
        val pos = if (status == Status.ERROR) exo?.currentPosition ?: 0L else 0L
        SiteClient.forget(queueItems[index].url)
        load(index, true, if (pos > 0) pos else -1)
    }

    // ---------- queue editing ----------

    fun addToQueue(video: Video) {
        if (queueItems.any { it.id == video.id }) {
            toast("Already in the queue")
            return
        }
        queueItems.add(video)
        if (index < 0) index = queueItems.size - 1
        queueChanged()
        toast("Added to queue")
    }

    fun playNext(video: Video) {
        val cur = current
        if (cur != null && cur.id == video.id) return
        val old = queueItems.indexOfFirst { it.id == video.id }
        if (old >= 0) {
            queueItems.removeAt(old)
            if (old < index) index--
        }
        val at = if (index in queueItems.indices) index + 1 else queueItems.size
        queueItems.add(at, video)
        if (index < 0) index = 0
        queueChanged()
        toast("Will play next")
    }

    fun removeAt(i: Int) {
        if (i !in queueItems.indices || i == index) return
        queueItems.removeAt(i)
        if (i < index) index--
        queueChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in queueItems.indices || to !in queueItems.indices || from == to) return
        val v = queueItems.removeAt(from)
        queueItems.add(to, v)
        index = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        queueChanged()
    }

    /** Drops everything after the current video. */
    fun clearUpcoming() {
        while (queueItems.size > index + 1) queueItems.removeAt(queueItems.size - 1)
        queueChanged()
    }

    fun shuffleUpcoming() {
        if (index + 1 >= queueItems.size) return
        val tail = ArrayList(queueItems.subList(index + 1, queueItems.size))
        tail.shuffle()
        while (queueItems.size > index + 1) queueItems.removeAt(queueItems.size - 1)
        queueItems.addAll(tail)
        queueChanged()
    }

    fun upcoming(): List<Video> =
        if (index in queueItems.indices) ArrayList(queueItems.subList(index + 1, queueItems.size)) else emptyList()

    private fun trimPlayed() {
        while (index > MAX_PLAYED_KEPT) {
            queueItems.removeAt(0)
            index--
        }
    }

    // ---------- moving through the queue ----------

    private fun relatedCandidate(): Video? {
        val related = info?.related ?: return null
        return related.firstOrNull { r -> queueItems.none { it.id == r.id } }
    }

    fun hasNext(): Boolean = index + 1 < queueItems.size || relatedCandidate() != null

    fun next() {
        if (index + 1 < queueItems.size) {
            playIndex(index + 1)
            return
        }
        val rel = relatedCandidate()
        if (rel != null) {
            queueItems.add(rel)
            playIndex(queueItems.size - 1)
        } else if (Prefs.repeatMode == 2 && queueItems.isNotEmpty()) {
            playIndex(0)
        } else {
            toast("Nothing to play next")
        }
    }

    fun previous() {
        val p = exo ?: return
        if (p.currentPosition > 3_000 || index <= 0) {
            p.seekTo(0)
        } else {
            playIndex(index - 1)
        }
    }

    // ---------- loading and starting streams ----------

    private fun load(i: Int, force: Boolean, startMs: Long) {
        val p = player
        val v = queueItems[i]
        index = i
        job?.cancel()
        p.stop()
        p.clearMediaItems()
        hasSession = true
        info = null
        offline = null
        candidates = emptyList()
        candidateIndex = 0
        retriedResolve = false
        countedWatch = false
        forcedHeight = 0
        setStatus(Status.RESOLVING, null, null)
        Library.saveQueue(queueItems, index)
        for (l in listeners) l.onVideoChanged()
        for (l in listeners) l.onQueueChanged()
        ensureService()

        val resume = when {
            startMs >= 0 -> startMs
            Prefs.resumePlayback -> Library.positionFor(v.id)
            else -> 0L
        }
        val start = if (resume < 3_000) 0L else resume

        val dl = Library.downloadFor(v.id)
        if (dl != null && dl.state == DlState.DONE && dl.filePath.isNotEmpty() && File(dl.filePath).exists()) {
            offline = dl
            playFile(v, dl, start)
            return
        }

        job = scope.launch {
            try {
                val si = SiteClient.resolve(v.url, force)
                if (current?.id != v.id) return@launch
                info = si
                val full = enrich(v, si)
                if (full !== v) {
                    queueItems[index] = full // a pasted link starts with no title or thumbnail
                    Library.saveQueue(queueItems, index)
                }
                candidates = orderedCandidates(si)
                for (l in listeners) l.onVideoChanged()
                startStream(full, si, 0, start)
            } catch (e: SiteException) {
                if (current?.id == v.id) setStatus(Status.ERROR, siteMessage(e), e.kind)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current?.id == v.id) setStatus(Status.ERROR, e.message ?: "Could not load the video", null)
            }
        }
    }

    /** Fills the gaps of a video that was opened from a link with what the page itself told us. */
    private fun enrich(v: Video, si: StreamInfo): Video {
        val title = if (v.title.isBlank() || v.title == "Video") si.title.ifEmpty { v.title } else v.title
        val thumb = v.thumb.ifEmpty { si.thumb }
        val duration = if (v.durationSec > 0) v.durationSec else si.durationSec
        val uploader = v.uploader.ifEmpty { si.uploader }
        val uploaderUrl = v.uploaderUrl.ifEmpty { si.uploaderUrl }
        val views = v.views.ifEmpty { si.views }
        if (title == v.title && thumb == v.thumb && duration == v.durationSec &&
            uploader == v.uploader && uploaderUrl == v.uploaderUrl && views == v.views
        ) return v
        return v.copy(
            title = title, thumb = thumb, durationSec = duration,
            uploader = uploader, uploaderUrl = uploaderUrl, views = views
        )
    }

    /** The streaming height cap that applies now: the mobile-data cap on a metered connection (if one is set), else the general one. 0 = no cap. */
    fun qualityCap(): Int {
        val mobile = Prefs.streamQualityMobile
        if (mobile >= 0 && onMeteredNetwork()) return mobile
        return Prefs.streamQuality
    }

    private fun onMeteredNetwork(): Boolean = try {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (e: Exception) {
        false
    }

    private fun orderedCandidates(si: StreamInfo): List<String> {
        val pref = qualityCap()
        val mp4: List<StreamVariant> = if (pref > 0) {
            si.mp4.sortedWith(
                compareBy<StreamVariant>(
                    { if (it.height in 1..pref) 0 else 1 },
                    { if (it.height in 1..pref) -it.height else it.height }
                )
            )
        } else si.mp4
        val out = LinkedHashSet<String>()
        si.hlsUrl?.let { out.add(it) }
        out.addAll(si.hlsFallbacks)
        for (m in mp4) out.add(m.url)
        return out.toList()
    }

    private fun startStream(v: Video, si: StreamInfo, candidate: Int, startMs: Long) {
        if (candidate >= candidates.size) {
            setStatus(Status.ERROR, "No playable stream found", SiteException.Kind.NO_STREAM)
            return
        }
        candidateIndex = candidate
        val url = candidates[candidate]
        val factory = http ?: return
        factory.setUserAgent(si.headers["User-Agent"])
        factory.setDefaultRequestProperties(si.headers.filterKeys { it != "User-Agent" })
        val hls = SiteParser.kindOf(url) == "hls"
        applyQualitySettings()
        val item = buildItem(v, Uri.parse(url), if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4, si.uploader)
        startItem(item, startMs)
    }

    private fun playFile(v: Video, dl: DownloadItem, startMs: Long) {
        val file = File(dl.filePath)
        val item = buildItem(v, Uri.fromFile(file), null, v.uploader)
        startItem(item, startMs)
    }

    private fun buildItem(v: Video, uri: Uri, mime: String?, artist: String): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(v.title)
            .setArtist(artist.ifEmpty { v.uploader })
        if (v.thumb.isNotEmpty()) meta.setArtworkUri(Uri.parse(v.thumb))
        val b = MediaItem.Builder().setMediaId(v.id).setUri(uri).setMediaMetadata(meta.build())
        if (mime != null) b.setMimeType(mime)
        return b.build()
    }

    private fun startItem(item: MediaItem, startMs: Long) {
        val p = player
        if (startMs > 0) p.setMediaItem(item, startMs) else p.setMediaItem(item)
        p.setPlaybackSpeed(if (Prefs.rememberSpeed) Prefs.defaultSpeed else 1f)
        p.prepare()
        p.playWhenReady = true
    }

    private fun applyQualitySettings() {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        val cap = qualityCap()
        if (cap > 0) b.setMaxVideoSize(Int.MAX_VALUE, cap) else b.clearVideoSizeConstraints()
        player.trackSelectionParameters = b.build()
    }

    private fun ensureService() {
        try {
            app.startService(Intent(app, PlaybackService::class.java))
        } catch (e: Exception) {
            // started from the background: the notification is just not available then
        }
    }

    // ---------- status ----------

    private fun setStatus(s: Status, message: String?, kind: SiteException.Kind?) {
        status = s
        errorMessage = message
        errorKind = kind
        for (l in listeners) l.onStatusChanged()
    }

    private fun siteMessage(e: SiteException): String = e.userMessage()

    private fun describe(e: PlaybackException): String {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is HttpDataSource.InvalidResponseCodeException) return "The server refused the video (HTTP ${t.responseCode})"
            t = t.cause
            depth++
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection to the video server"
            else -> "Playback error (${e.errorCodeName})"
        }
    }

    // ---------- player events ----------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && status != Status.READY) {
                setStatus(Status.READY, null, null)
                val v = current
                if (v != null && !countedWatch) {
                    countedWatch = true
                    if (Prefs.keepHistory) Library.recordWatch(v)
                }
            }
            if (playbackState == Player.STATE_ENDED) onEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            main.removeCallbacks(ticker)
            if (isPlaying) main.postDelayed(ticker, 5_000) else saveProgress(true)
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            saveProgress(false)
            if (exo?.isPlaying == true) main.postDelayed(this, 5_000)
        }
    }

    private fun saveProgress(flush: Boolean) {
        val p = exo ?: return
        val v = current ?: return
        if (!Prefs.keepHistory || status != Status.READY) return
        val d = p.duration
        Library.updatePosition(v.id, p.currentPosition, if (d > 0) d else 0L)
        if (flush) Library.flushHistory()
    }

    private fun onEnded() {
        val p = exo ?: return
        val v = current ?: return
        val d = p.duration
        Library.updatePosition(v.id, 0L, if (d > 0) d else 0L)
        Library.flushHistory()
        if (sleepAtVideoEnd) {
            sleepAtVideoEnd = false
            toast("Sleep timer: stopped after this video")
            return
        }
        if (index + 1 < queueItems.size) {
            playIndex(index + 1)
        } else if (Prefs.repeatMode == 2 && queueItems.size > 1) {
            playIndex(0)
        } else if (Prefs.autoplayNext) {
            val rel = relatedCandidate()
            if (rel != null) {
                queueItems.add(rel)
                playIndex(queueItems.size - 1)
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val v = current ?: return
        val si = info
        val pos = (exo?.currentPosition ?: 0L).coerceAtLeast(0L)
        if (offline != null) {
            setStatus(Status.ERROR, "The downloaded file could not be played", null)
            return
        }
        val netDown = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (si != null && !netDown) {
            if (candidateIndex + 1 < candidates.size) {
                startStream(v, si, candidateIndex + 1, pos)
                return
            }
            if (!retriedResolve) {
                // signed stream URLs expire: ask the site again once
                retriedResolve = true
                SiteClient.forget(v.url)
                val keepIndex = index
                job?.cancel()
                job = scope.launch {
                    try {
                        val fresh = SiteClient.resolve(v.url, true)
                        if (index != keepIndex || current?.id != v.id) return@launch
                        info = fresh
                        candidates = orderedCandidates(fresh)
                        startStream(v, fresh, 0, pos)
                    } catch (ex: SiteException) {
                        if (current?.id == v.id) setStatus(Status.ERROR, siteMessage(ex), ex.kind)
                    } catch (ex: kotlinx.coroutines.CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        if (current?.id == v.id) setStatus(Status.ERROR, ex.message ?: "Playback failed", null)
                    }
                }
                return
            }
        }
        setStatus(Status.ERROR, describe(e), null)
    }

    // ---------- controls used by the UI ----------

    fun togglePlay() {
        val p = exo ?: return
        if (status == Status.IDLE && index in queueItems.indices) {
            playIndex(index) // queued (or restored) but never loaded
        } else if (status == Status.ERROR) {
            retry()
        } else if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.playWhenReady) {
            p.pause()
        } else {
            p.play()
        }
    }

    val speed: Float get() = exo?.playbackParameters?.speed ?: 1f

    fun setSpeed(s: Float) {
        exo?.setPlaybackSpeed(s)
        if (Prefs.rememberSpeed) Prefs.defaultSpeed = s
    }

    fun setRepeat(mode: Int) {
        Prefs.repeatMode = mode
        exo?.repeatMode = if (mode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Video decoding is switched off while the screen is not showing it (audio keeps playing). */
    fun setVideoActive(active: Boolean) {
        val p = exo ?: return
        if (videoActive == active) return
        videoActive = active
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !active)
            .build()
    }

    fun qualityOptions(): List<QualityOption> {
        val out = ArrayList<QualityOption>()
        out.add(QualityOption("Auto", 0))
        val heights = java.util.TreeSet<Int>(java.util.Collections.reverseOrder())
        if (isPlayingHls) {
            val p = exo
            if (p != null) {
                for (g in p.currentTracks.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until g.length) {
                        val h = g.getTrackFormat(i).height
                        if (h > 0 && g.isTrackSupported(i)) heights.add(h)
                    }
                }
            }
        } else {
            info?.mp4?.forEach { if (it.height > 0) heights.add(it.height) }
        }
        for (h in heights) out.add(QualityOption("${h}p", h))
        return out
    }

    /** Picks a fixed quality (height) or 0 for automatic. */
    fun setQuality(height: Int) {
        val p = exo ?: return
        forcedHeight = height
        if (Prefs.rememberQuality) {
            // the pick becomes the default for the kind of connection in use now
            if (Prefs.streamQualityMobile >= 0 && onMeteredNetwork()) Prefs.streamQualityMobile = height else Prefs.streamQuality = height
        }
        if (isPlayingHls) {
            val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            val cap = qualityCap()
            if (height == 0) {
                if (cap > 0) b.setMaxVideoSize(Int.MAX_VALUE, cap) else b.clearVideoSizeConstraints()
            } else {
                b.clearVideoSizeConstraints()
                var done = false
                for (g in p.currentTracks.groups) {
                    if (done) break
                    if (g.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until g.length) {
                        if (g.getTrackFormat(i).height == height) {
                            b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            done = true
                            break
                        }
                    }
                }
            }
            p.trackSelectionParameters = b.build()
        } else {
            val si = info ?: return
            val v = current ?: return
            val target = si.mp4.firstOrNull { it.height == height } ?: si.mp4.firstOrNull() ?: return
            val at = candidates.indexOf(target.url)
            if (at >= 0) startStream(v, si, at, p.currentPosition)
        }
        for (l in listeners) l.onStatusChanged()
    }

    // ---------- sleep timer ----------

    private val sleepRunnable = Runnable {
        sleepEndsAt = 0L
        exo?.pause()
        toast("Sleep timer: paused")
        for (l in listeners) l.onStatusChanged()
    }

    fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        sleepAtVideoEnd = false
        if (minutes > 0) {
            sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
            main.postDelayed(sleepRunnable, minutes * 60_000L)
            toast("Sleep timer set: $minutes min")
        } else {
            sleepEndsAt = 0L
        }
        for (l in listeners) l.onStatusChanged()
    }

    fun setSleepAtVideoEnd() {
        main.removeCallbacks(sleepRunnable)
        sleepEndsAt = 0L
        sleepAtVideoEnd = true
        toast("Will stop after this video")
        for (l in listeners) l.onStatusChanged()
    }

    // ---------- stopping ----------

    /** Ends the session: stops playback, hides the mini player and the notification. */
    fun stop() {
        job?.cancel()
        main.removeCallbacks(sleepRunnable)
        sleepEndsAt = 0L
        sleepAtVideoEnd = false
        saveProgress(true)
        exo?.stop()
        exo?.clearMediaItems()
        hasSession = false
        info = null
        offline = null
        status = Status.IDLE
        try {
            app.stopService(Intent(app, PlaybackService::class.java))
        } catch (e: Exception) {
            // ignore
        }
        for (l in listeners) l.onVideoChanged()
        for (l in listeners) l.onStatusChanged()
    }

    private fun queueChanged() {
        Library.saveQueue(queueItems, index)
        for (l in listeners) l.onQueueChanged()
    }

    fun toast(msg: String) {
        if (this::app.isInitialized) Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
    }
}
