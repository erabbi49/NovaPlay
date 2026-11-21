package com.novaplay.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the user keeps: history, liked, watch later, playlists, subscriptions, custom
 * chips, search history, downloads and the saved queue. Stored as small JSON files.
 * Safe to call from any thread; change listeners always run on the main thread.
 */
object Library {
    private lateinit var dir: File
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val notifyPending = AtomicBoolean(false)

    private val history = ArrayList<HistoryEntry>()
    private val liked = ArrayList<Video>()
    private val watchLater = ArrayList<Video>()
    private val playlists = ArrayList<UserPlaylist>()
    private val subs = ArrayList<Source>()
    private val chips = ArrayList<Source>()
    private val searches = ArrayList<String>()
    private val downloads = ArrayList<DownloadItem>()
    private val savedQueue = ArrayList<Video>()
    private var savedQueueIndex = 0

    private const val MAX_HISTORY = 500
    private const val MAX_SEARCHES = 40

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, "library")
        dir.mkdirs()
        synchronized(this) {
            val h = readArray("history")
            for (i in 0 until h.length()) {
                val o = h.optJSONObject(i) ?: continue
                val e = HistoryEntry.fromJson(o)
                if (e.video.url.isNotEmpty()) history.add(e)
            }
            liked.addAll(readArray("liked").toVideos())
            watchLater.addAll(readArray("watchlater").toVideos())
            val p = readArray("playlists")
            for (i in 0 until p.length()) {
                val o = p.optJSONObject(i) ?: continue
                playlists.add(UserPlaylist.fromJson(o))
            }
            val s = readArray("subs")
            for (i in 0 until s.length()) {
                val o = s.optJSONObject(i) ?: continue
                subs.add(Source.fromJson(o))
            }
            val c = readArray("chips")
            for (i in 0 until c.length()) {
                val o = c.optJSONObject(i) ?: continue
                chips.add(Source.fromJson(o))
            }
            val q = readArray("searches")
            for (i in 0 until q.length()) searches.add(q.optString(i))
            val d = readArray("downloads")
            for (i in 0 until d.length()) {
                val o = d.optJSONObject(i) ?: continue
                val item = DownloadItem.fromJson(o)
                // A download that was running when the app died is resumable, not running.
                if (item.state == DlState.RUNNING || item.state == DlState.QUEUED) item.state = DlState.PAUSED
                downloads.add(item)
            }
            val qo = readObject("queue")
            savedQueue.addAll((qo.optJSONArray("videos") ?: JSONArray()).toVideos())
            savedQueueIndex = qo.optInt("index", 0)
        }
    }

    // ---------- change notification ----------

    fun addListener(r: Runnable) {
        listeners.add(r)
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    private fun changed() {
        if (notifyPending.compareAndSet(false, true)) {
            main.post {
                notifyPending.set(false)
                for (l in listeners) l.run()
            }
        }
    }

    // ---------- history ----------

    fun historyList(): List<HistoryEntry> = synchronized(this) { ArrayList(history) }

    fun recordWatch(video: Video) {
        if (!Prefs.keepHistory) return
        synchronized(this) {
            val existing = history.firstOrNull { it.video.id == video.id }
            val pos = existing?.positionMs ?: 0L
            val dur = existing?.durationMs ?: 0L
            if (existing != null) history.remove(existing)
            history.add(0, HistoryEntry(video, pos, dur, System.currentTimeMillis()))
            while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        }
        persist("history") { arrayOfHistory() }
        changed()
    }

    fun updatePosition(videoId: String, positionMs: Long, durationMs: Long) {
        synchronized(this) {
            val e = history.firstOrNull { it.video.id == videoId } ?: return
            e.positionMs = positionMs
            if (durationMs > 0L) e.durationMs = durationMs
        }
    }

    fun flushHistory() {
        persist("history") { arrayOfHistory() }
    }

    /** True when [v] was watched to (nearly) the end. */
    fun isWatched(v: Video): Boolean {
        val total = v.durationSec * 1000L
        if (total <= 0L) return false
        return positionFor(v.id) >= total * 9 / 10
    }

    fun positionFor(videoId: String): Long = synchronized(this) {
        val e = history.firstOrNull { it.video.id == videoId }
        if (e == null) 0L else e.positionMs
    }

    fun removeHistory(videoId: String) {
        synchronized(this) { history.removeAll { it.video.id == videoId } }
        persist("history") { arrayOfHistory() }
        changed()
    }

    fun clearHistory() {
        synchronized(this) { history.clear() }
        persist("history") { arrayOfHistory() }
        changed()
    }

    private fun arrayOfHistory(): JSONArray {
        val a = JSONArray()
        for (e in history) a.put(e.toJson())
        return a
    }

    // ---------- liked / watch later ----------

    fun likedList(): List<Video> = synchronized(this) { ArrayList(liked) }

    fun isLiked(id: String): Boolean = synchronized(this) { liked.any { it.id == id } }

    /** Returns true when the video is liked after the call. */
    fun toggleLike(video: Video): Boolean {
        val now = synchronized(this) {
            val existing = liked.firstOrNull { it.id == video.id }
            if (existing != null) {
                liked.remove(existing)
                false
            } else {
                liked.add(0, video)
                true
            }
        }
        persist("liked") { liked.toJsonArray() }
        changed()
        return now
    }

    fun watchLaterList(): List<Video> = synchronized(this) { ArrayList(watchLater) }

    fun isWatchLater(id: String): Boolean = synchronized(this) { watchLater.any { it.id == id } }

    fun toggleWatchLater(video: Video): Boolean {
        val now = synchronized(this) {
            val existing = watchLater.firstOrNull { it.id == video.id }
            if (existing != null) {
                watchLater.remove(existing)
                false
            } else {
                watchLater.add(0, video)
                true
            }
        }
        persist("watchlater") { watchLater.toJsonArray() }
        changed()
        return now
    }

    // ---------- playlists ----------

    fun playlistList(): List<UserPlaylist> = synchronized(this) { ArrayList(playlists) }

    fun createPlaylist(name: String): UserPlaylist {
        val p = UserPlaylist("pl" + System.currentTimeMillis(), name.trim().ifEmpty { "Playlist" }, ArrayList())
        synchronized(this) { playlists.add(p) }
        persistPlaylists()
        changed()
        return p
    }

    fun addToPlaylist(playlistId: String, video: Video) {
        synchronized(this) {
            val p = playlists.firstOrNull { it.id == playlistId } ?: return
            if (p.videos.none { it.id == video.id }) p.videos.add(video)
        }
        persistPlaylists()
        changed()
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        synchronized(this) {
            val p = playlists.firstOrNull { it.id == playlistId } ?: return
            p.videos.removeAll { it.id == videoId }
        }
        persistPlaylists()
        changed()
    }

    fun renamePlaylist(playlistId: String, name: String) {
        synchronized(this) {
            val p = playlists.firstOrNull { it.id == playlistId } ?: return
            p.name = name.trim().ifEmpty { p.name }
        }
        persistPlaylists()
        changed()
    }

    fun deletePlaylist(playlistId: String) {
        synchronized(this) { playlists.removeAll { it.id == playlistId } }
        persistPlaylists()
        changed()
    }

    private fun persistPlaylists() {
        persist("playlists") {
            val a = JSONArray()
            for (p in playlists) a.put(p.toJson())
            a
        }
    }

    // ---------- subscriptions and custom chips ----------

    fun subList(): List<Source> = synchronized(this) { ArrayList(subs) }

    fun isSubscribed(url: String): Boolean = synchronized(this) { subs.any { sameUrl(it.url, url) } }

    fun toggleSub(source: Source): Boolean {
        val now = synchronized(this) {
            val existing = subs.firstOrNull { sameUrl(it.url, source.url) }
            if (existing != null) {
                subs.remove(existing)
                false
            } else {
                subs.add(0, source)
                true
            }
        }
        persist("subs") { sourcesToArray(subs) }
        changed()
        return now
    }

    fun chipList(): List<Source> = synchronized(this) { ArrayList(chips) }

    fun addChip(source: Source) {
        synchronized(this) {
            if (chips.none { sameUrl(it.url, source.url) }) chips.add(source)
        }
        persist("chips") { sourcesToArray(chips) }
        changed()
    }

    fun removeChip(url: String) {
        synchronized(this) { chips.removeAll { sameUrl(it.url, url) } }
        persist("chips") { sourcesToArray(chips) }
        changed()
    }

    private fun sourcesToArray(list: List<Source>): JSONArray {
        val a = JSONArray()
        for (s in list) a.put(s.toJson())
        return a
    }

    private fun sameUrl(a: String, b: String): Boolean {
        return Video.idFor(a).equals(Video.idFor(b), ignoreCase = true)
    }

    // ---------- search history ----------

    fun searchList(): List<String> = synchronized(this) { ArrayList(searches) }

    fun addSearch(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        synchronized(this) {
            searches.removeAll { it.equals(text, ignoreCase = true) }
            searches.add(0, text)
            while (searches.size > MAX_SEARCHES) searches.removeAt(searches.size - 1)
        }
        persistSearches()
    }

    fun removeSearch(q: String) {
        synchronized(this) { searches.removeAll { it == q } }
        persistSearches()
    }

    fun clearSearches() {
        synchronized(this) { searches.clear() }
        persistSearches()
    }

    private fun persistSearches() {
        persist("searches") {
            val a = JSONArray()
            for (s in searches) a.put(s)
            a
        }
    }

    // ---------- downloads ----------

    fun downloadList(): List<DownloadItem> = synchronized(this) { ArrayList(downloads) }

    fun downloadFor(id: String): DownloadItem? = synchronized(this) { downloads.firstOrNull { it.id == id } }

    /** Inserts or replaces. Pass persist = false for the frequent progress ticks. */
    fun putDownload(item: DownloadItem, persist: Boolean = true) {
        synchronized(this) {
            val i = downloads.indexOfFirst { it.id == item.id }
            if (i >= 0) downloads[i] = item else downloads.add(0, item)
        }
        if (persist) persistDownloads()
        changed()
    }

    fun removeDownload(id: String) {
        synchronized(this) { downloads.removeAll { it.id == id } }
        persistDownloads()
        changed()
    }

    fun persistDownloads() {
        persist("downloads") {
            val a = JSONArray()
            for (d in downloads) a.put(d.toJson())
            a
        }
    }

    // ---------- saved queue ----------

    fun saveQueue(videos: List<Video>, index: Int) {
        synchronized(this) {
            savedQueue.clear()
            savedQueue.addAll(videos)
            savedQueueIndex = index
        }
        val text = synchronized(this) {
            val o = JSONObject()
            o.put("videos", savedQueue.toJsonArray())
            o.put("index", savedQueueIndex)
            o.toString()
        }
        writeAsync("queue", text)
    }

    fun loadQueue(): Pair<List<Video>, Int> = synchronized(this) { Pair(ArrayList(savedQueue), savedQueueIndex) }

    // ---------- storage helpers ----------

    private fun persist(name: String, build: () -> JSONArray) {
        val text = synchronized(this) { build().toString() }
        writeAsync(name, text)
    }

    private fun writeAsync(name: String, text: String) {
        io.execute {
            try {
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(text)
                val target = File(dir, "$name.json")
                if (!tmp.renameTo(target)) {
                    target.writeText(text)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // storage is best effort
            }
        }
    }

    private fun readArray(name: String): JSONArray {
        return try {
            val f = File(dir, "$name.json")
            if (f.exists()) JSONArray(f.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun readObject(name: String): JSONObject {
        return try {
            val f = File(dir, "$name.json")
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
