package com.novaplay.app.data

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Call [init] once from the Application class. */
object Prefs {
    private lateinit var sp: SharedPreferences

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2
    const val THEME_AMOLED = 3

    const val DEFAULT_BASE_URL = "https://xhamster.com"

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
    }

    private fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun int(key: String, def: Int): Int = sp.getInt(key, def)
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    var theme: Int
        get() = int("theme", THEME_DARK)
        set(v) = putInt("theme", v)

    var ageConfirmed: Boolean
        get() = bool("age_ok", false)
        set(v) = putBool("age_ok", v)

    /** Site root, e.g. https://xhamster.com. Changeable so a mirror domain can be used. */
    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    /** Max height for streaming, 0 = auto (adaptive). */
    var streamQuality: Int
        get() = int("stream_q", 0)
        set(v) = putInt("stream_q", v)

    /** Height to download: 0 = best available, -1 = ask every time. */
    var downloadQuality: Int
        get() = int("dl_q", 0)
        set(v) = putInt("dl_q", v)

    var defaultSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()

    var rememberSpeed: Boolean
        get() = bool("remember_speed", true)
        set(v) = putBool("remember_speed", v)

    var autoplayNext: Boolean
        get() = bool("autoplay", true)
        set(v) = putBool("autoplay", v)

    var backgroundPlay: Boolean
        get() = bool("bg_play", true)
        set(v) = putBool("bg_play", v)

    var pipOnLeave: Boolean
        get() = bool("pip_leave", true)
        set(v) = putBool("pip_leave", v)

    var ambient: Boolean
        get() = bool("ambient", true)
        set(v) = putBool("ambient", v)

    /** How bright the ambient light is, 20..100 (percent). */
    var ambientStrength: Int
        get() = int("amb_strength", 75)
        set(v) = putInt("amb_strength", v)

    /** 0 = sharp (keeps the shapes of the video), 1 = light, 2 = medium, 3 = strong. */
    var ambientBlur: Int
        get() = int("amb_blur", 2)
        set(v) = putInt("amb_blur", v)

    /** How slowly the light follows the picture: 0 = instantly, 1 = a little, 2 = medium, 3 = very slowly. */
    var ambientSmooth: Int
        get() = int("amb_smooth", 2)
        set(v) = putInt("amb_smooth", v)

    /** Dim the light while the page below the player is scrolled. */
    var ambientDimScroll: Boolean
        get() = bool("amb_dim_scroll", true)
        set(v) = putBool("amb_dim_scroll", v)

    /** How long (ms) the light stays dimmed after scrolling stops. */
    var ambientDimMs: Int
        get() = int("amb_dim_ms", 1500)
        set(v) = putInt("amb_dim_ms", v)

    /** Darken the light below the player so the text there stays easy to read. */
    var ambientReadable: Boolean
        get() = bool("amb_readable", true)
        set(v) = putBool("amb_readable", v)

    /** Light the black bars around the video (fullscreen, and videos that are not 16:9) with the picture. */
    var ambientBars: Boolean
        get() = bool("amb_bars", true)
        set(v) = putBool("amb_bars", v)

    var gestures: Boolean
        get() = bool("gestures", true)
        set(v) = putBool("gestures", v)

    var resumePlayback: Boolean
        get() = bool("resume", true)
        set(v) = putBool("resume", v)

    var doubleTapSeekSec: Int
        get() = int("seek_sec", 10)
        set(v) = putInt("seek_sec", v)

    var wifiOnlyDownloads: Boolean
        get() = bool("wifi_only", false)
        set(v) = putBool("wifi_only", v)

    var keepHistory: Boolean
        get() = bool("keep_history", true)
        set(v) = putBool("keep_history", v)

    /** 0 = no repeat, 1 = repeat one, 2 = repeat all (queue). */
    var repeatMode: Int
        get() = int("repeat", 0)
        set(v) = putInt("repeat", v)

    var autoLandscapeFullscreen: Boolean
        get() = bool("auto_fs", true)
        set(v) = putBool("auto_fs", v)

    // ---------- layout ----------

    /** Lists as small rows (thumbnail on the left) instead of big cards. */
    var compactFeed: Boolean
        get() = bool("compact_feed", false)
        set(v) = putBool("compact_feed", v)

    /** Related / up-next videos under the player as big cards (like the YouTube app) instead of small rows. */
    var relatedCards: Boolean
        get() = bool("related_cards", true)
        set(v) = putBool("related_cards", v)

    /** Leave out videos that were watched to (nearly) the end. */
    var hideWatched: Boolean
        get() = bool("hide_watched", false)
        set(v) = putBool("hide_watched", v)

    /** The strip above the tab bar that keeps the current video one tap away. */
    var miniPlayer: Boolean
        get() = bool("mini_player", true)
        set(v) = putBool("mini_player", v)

    /** Show the duration and quality labels on thumbnails. */
    var thumbBadges: Boolean
        get() = bool("thumb_badges", true)
        set(v) = putBool("thumb_badges", v)

    // ---------- player gestures ----------

    var swipeBrightness: Boolean
        get() = bool("swipe_brightness", true)
        set(v) = putBool("swipe_brightness", v)

    var swipeVolume: Boolean
        get() = bool("swipe_volume", true)
        set(v) = putBool("swipe_volume", v)

    var swipeSeek: Boolean
        get() = bool("swipe_seek", true)
        set(v) = putBool("swipe_seek", v)

    /** How far a swipe moves brightness / volume, in percent of the default (50..200). */
    var swipeSensitivity: Int
        get() = int("swipe_sens", 100)
        set(v) = putInt("swipe_sens", v)

    /** Hold the video to play faster while the finger stays down. */
    var holdToSpeed: Boolean
        get() = bool("hold_speed_on", true)
        set(v) = putBool("hold_speed_on", v)

    var holdSpeed: Float
        get() = sp.getFloat("hold_speed", 2f)
        set(v) = sp.edit().putFloat("hold_speed", v).apply()

    /** Pinch out to fill the screen, pinch in to fit it. */
    var pinchZoom: Boolean
        get() = bool("pinch_zoom", true)
        set(v) = putBool("pinch_zoom", v)

    /** Streaming quality cap on mobile data: -1 = the same as on Wi-Fi, 0 = auto, otherwise a height. */
    var streamQualityMobile: Int
        get() = int("stream_q_mobile", -1)
        set(v) = putInt("stream_q_mobile", v)

    /** The speeds offered in the speed menu go up to 4x instead of 3x. */
    var extendedSpeeds: Boolean
        get() = bool("ext_speeds", false)
        set(v) = putBool("ext_speeds", v)

    /** true = lists show page numbers (1 2 3 ...) under them; false = endless scrolling. */
    var pagedFeed: Boolean
        get() = bool("paged_feed", true)
        set(v) = putBool("paged_feed", v)
}
