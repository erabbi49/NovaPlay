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
}
