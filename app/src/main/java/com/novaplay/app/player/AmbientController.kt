package com.novaplay.app.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.media3.common.Player
import com.novaplay.app.data.Prefs

/**
 * "Enhanced ambient mode". About ten times a second it copies a tiny version of the current video frame,
 * eases the colours towards it (so the light glides instead of flickering) and hands the result to
 * [AmbientGlowView], which lights the page and the black bars with it.
 */
class AmbientController(private val surface: TextureView, private val glow: AmbientGlowView) {
    private val ui = Handler(Looper.getMainLooper())
    private var bmp: Bitmap? = null
    private var raw = IntArray(0)
    private var out = IntArray(0)
    private var cur = FloatArray(0)
    private var w = 0
    private var h = 0
    private var running = false
    private var interval = FAST_MS
    private var lastSample = 0L

    /** True once a picture of the current video has been handed to the glow. */
    var hasFrame = false
        private set

    /** Called (on the main thread) when the first picture of a video arrives. */
    var onFirstFrame: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t0 = SystemClock.uptimeMillis()
            if (surface.isAvailable && glow.isShown) {
                val p = Playback.player
                if (p.isPlaying) {
                    sample()
                } else if (p.playbackState == Player.STATE_READY && (!hasFrame || t0 - lastSample > IDLE_MS)) {
                    // paused: the picture only changes when the person seeks, so look now and then
                    sample()
                }
            }
            // a slow phone gets a slower light instead of a slower app
            interval = if (SystemClock.uptimeMillis() - t0 > SLOW_COST_MS) SLOW_MS else FAST_MS
            ui.postDelayed(this, interval)
        }
    }

    fun start() {
        if (running) return
        running = true
        configure()
        ui.post(tick)
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
    }

    /** Forgets the picture (a new video started, or the feature was switched off). */
    fun clear() {
        hasFrame = false
        glow.clearFrame()
    }

    /** Re-reads the settings (picture size, blur, strength). */
    fun configure() {
        val (nw, nh) = when (Prefs.ambientBlur.coerceIn(0, 3)) {
            0 -> 64 to 36
            1 -> 32 to 18
            2 -> 16 to 9
            else -> 8 to 5
        }
        if (nw != w || bmp == null) {
            w = nw
            h = nh
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raw = IntArray(w * h)
            out = IntArray(w * h)
            cur = FloatArray(w * h * 3)
            hasFrame = false
        }
        glow.configure()
    }

    private fun sample() {
        val b = bmp ?: return
        lastSample = SystemClock.uptimeMillis()
        try {
            surface.getBitmap(b)
        } catch (e: Exception) {
            return
        }
        b.getPixels(raw, 0, w, 0, 0, w, h)
        val k = when (Prefs.ambientSmooth.coerceIn(0, 3)) {
            0 -> 1f
            1 -> 0.55f
            2 -> 0.28f
            else -> 0.13f
        }
        val first = !hasFrame
        for (i in 0 until w * h) {
            val c = raw[i]
            val j = i * 3
            if (first) {
                cur[j] = Color.red(c).toFloat()
                cur[j + 1] = Color.green(c).toFloat()
                cur[j + 2] = Color.blue(c).toFloat()
            } else {
                cur[j] += (Color.red(c) - cur[j]) * k
                cur[j + 1] += (Color.green(c) - cur[j + 1]) * k
                cur[j + 2] += (Color.blue(c) - cur[j + 2]) * k
            }
            out[i] = Color.rgb(cur[j].toInt(), cur[j + 1].toInt(), cur[j + 2].toInt())
        }
        glow.setPixels(w, h, out)
        if (first) {
            hasFrame = true
            onFirstFrame?.invoke()
        }
    }

    private companion object {
        const val FAST_MS = 100L
        const val SLOW_MS = 200L
        const val SLOW_COST_MS = 14L
        const val IDLE_MS = 600L
    }
}
