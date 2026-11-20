package com.novaplay.app.adblock

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the filter lists: loads the bundled copies (or newer downloaded ones), builds the
 * [FilterEngine] on a background thread and keeps the user's switches.
 */
object FilterLists {
    data class ListInfo(
        val id: String,
        val title: String,
        val enabled: Boolean,
        val ruleCount: Int,
        /** Epoch millis of the last successful download, 0 when only the bundled copy is used. */
        val updatedAt: Long
    )

    private class Spec(val id: String, val title: String, val asset: String, val urls: List<String>)

    private const val UBO = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master"
    private const val ADULT = "https://raw.githubusercontent.com/easylist/easylist/master/easylist_adult"

    private val specs = listOf(
        Spec("nova-base", "NovaPlay base list", "nova-base.txt", emptyList()),
        Spec("ubo-filters", "uBlock filters", "ublock-filters.txt", listOf("$UBO/filters/filters.txt")),
        Spec("ubo-privacy", "uBlock filters - Privacy", "ublock-privacy.txt", listOf("$UBO/filters/privacy.txt")),
        Spec("ubo-badware", "uBlock filters - Badware risks", "ublock-badware.txt", listOf("$UBO/filters/badware.txt")),
        Spec("ubo-unbreak", "uBlock filters - Unbreak", "ublock-unbreak.txt", listOf("$UBO/filters/unbreak.txt")),
        Spec("easylist", "EasyList", "easylist.txt", listOf("$UBO/thirdparties/easylist/easylist.txt")),
        Spec("easyprivacy", "EasyPrivacy", "easyprivacy.txt", listOf("$UBO/thirdparties/easylist/easyprivacy.txt")),
        Spec(
            "easylist-adult", "EasyList Adult", "easylist-adult.txt",
            listOf(
                "$ADULT/adult_adservers.txt",
                "$ADULT/adult_thirdparty.txt",
                "$ADULT/adult_specific_block.txt",
                "$ADULT/adult_specific_hide.txt",
                "$ADULT/adult_specific_block_popup.txt"
            )
        )
    )

    private lateinit var appContext: Context
    private lateinit var sp: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val readyLatch = CountDownLatch(1)
    private val counts = ConcurrentHashMap<String, Int>()
    private val reloadQueued = AtomicBoolean(false)

    @Volatile
    var engine: FilterEngine? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    /** Requests blocked since install (persisted every few blocks). */
    val blockedCount = AtomicLong(0)

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        sp = appContext.getSharedPreferences("nova_adblock", Context.MODE_PRIVATE)
        blockedCount.set(sp.getLong("blocked_total", 0L))
        reload()
    }

    fun awaitReady(timeoutMs: Long): Boolean {
        return try {
            readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // ---------- switches ----------

    var adBlockEnabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var cosmeticEnabled: Boolean
        get() = sp.getBoolean("cosmetic", true)
        set(v) = sp.edit().putBoolean("cosmetic", v).apply()

    var scriptletsEnabled: Boolean
        get() = sp.getBoolean("scriptlets", true)
        set(v) = sp.edit().putBoolean("scriptlets", v).apply()

    var popupBlockEnabled: Boolean
        get() = sp.getBoolean("popups", true)
        set(v) = sp.edit().putBoolean("popups", v).apply()

    /** The "My filters" text box. Assigning rebuilds the engine. */
    var userFilters: String
        get() = sp.getString("user_filters", "") ?: ""
        set(v) {
            sp.edit().putString("user_filters", v).apply()
            reload()
        }

    private fun isEnabled(id: String): Boolean = sp.getBoolean("en_$id", true)

    fun setEnabled(id: String, on: Boolean) {
        sp.edit().putBoolean("en_$id", on).apply()
        reload()
    }

    fun noteBlocked() {
        val n = blockedCount.incrementAndGet()
        if (n % 50L == 0L) saveStats()
    }

    fun saveStats() {
        if (this::sp.isInitialized) sp.edit().putLong("blocked_total", blockedCount.get()).apply()
    }

    // ---------- catalog / status ----------

    fun catalog(): List<ListInfo> {
        val out = ArrayList<ListInfo>()
        for (s in specs) {
            out.add(ListInfo(s.id, s.title, isEnabled(s.id), counts[s.id] ?: 0, cacheFile(s).let { if (it.exists()) it.lastModified() else 0L }))
        }
        return out
    }

    fun ruleSummary(): String {
        val e = engine ?: return "Loading filter lists..."
        return "%,d network rules, %,d cosmetic rules".format(e.networkRuleCount, e.cosmeticRuleCount)
    }

    // ---------- building ----------

    fun reload() {
        if (!reloadQueued.compareAndSet(false, true)) return
        worker.execute {
            reloadQueued.set(false)
            try {
                rebuild()
            } catch (e: Throwable) {
                // keep serving the previous engine
            }
        }
    }

    private fun rebuild() {
        val inputs = ArrayList<Pair<String, String>>()
        counts.clear()
        for (s in specs) {
            if (!isEnabled(s.id)) continue
            val text = readList(s) ?: continue
            counts[s.id] = countRules(text)
            inputs.add(Pair(s.id, text))
        }
        val user = userFilters
        if (user.isNotBlank()) inputs.add(Pair("user", user))
        engine = FilterEngine.build(inputs)
        isReady = true
        readyLatch.countDown()
    }

    private fun cacheFile(s: Spec): File = File(File(appContext.filesDir, "filters"), s.id + ".txt")

    private fun readList(s: Spec): String? {
        try {
            val cached = cacheFile(s)
            if (cached.exists() && cached.length() > 200L) return cached.readText()
        } catch (e: Exception) {
            // fall back to the bundled copy
        }
        return try {
            appContext.assets.open("filters/" + s.asset).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun countRules(text: String): Int {
        var n = 0
        var start = 0
        val len = text.length
        while (start < len) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = len
            if (end > start) {
                val c = text[start]
                if (c != '!' && c != '[' && c != '\r') n++
            }
            start = end + 1
        }
        return n
    }

    // ---------- updating ----------

    /** Downloads fresh copies of every enabled list, rebuilds the engine, then calls [onDone] on the main thread. */
    fun updateAll(onDone: (updated: Int, failed: Int) -> Unit) {
        worker.execute {
            var updated = 0
            var failed = 0
            for (s in specs) {
                if (s.urls.isEmpty() || !isEnabled(s.id)) continue
                when (download(s)) {
                    1 -> updated++
                    -1 -> failed++
                }
            }
            if (updated > 0) {
                try {
                    rebuild()
                } catch (e: Throwable) {
                    // keep old engine
                }
            }
            main.post { onDone(updated, failed) }
        }
    }

    /** 1 = updated, 0 = not modified, -1 = failed. */
    private fun download(s: Spec): Int {
        try {
            val cache = cacheFile(s)
            val single = s.urls.size == 1
            val etagKey = "etag_" + s.id
            val sb = StringBuilder()
            for (u in s.urls) {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 40000
                conn.setRequestProperty("User-Agent", "NovaPlay/1.0")
                val etag = sp.getString(etagKey, null)
                if (single && etag != null && cache.exists()) conn.setRequestProperty("If-None-Match", etag)
                try {
                    val code = conn.responseCode
                    if (code == 304) return 0
                    if (code != 200) return -1
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.length < 100) return -1
                    sb.append(body).append('\n')
                    if (single) sp.edit().putString(etagKey, conn.getHeaderField("ETag")).apply()
                } finally {
                    conn.disconnect()
                }
            }
            cache.parentFile?.mkdirs()
            val tmp = File(cache.path + ".tmp")
            tmp.writeText(sb.toString())
            if (cache.exists()) cache.delete()
            if (!tmp.renameTo(cache)) {
                cache.writeText(sb.toString())
                tmp.delete()
            }
            return 1
        } catch (e: Exception) {
            return -1
        }
    }
}
