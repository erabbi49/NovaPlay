package com.novaplay.app.player

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import com.novaplay.app.ui.Ui

/**
 * "Ambient mode": a soft glow behind and below the player that follows the colours of the picture.
 * It reads a tiny 16x9 copy of the current frame a few times a second and eases a gradient towards it.
 */
class AmbientController(private val surface: TextureView, private val glow: View) {
    private val ui = Handler(Looper.getMainLooper())
    private val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val px = IntArray(W * H)
    private val gradient = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0, 0))
    private var top = 0
    private var bottom = 0
    private var primed = false
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (surface.isAvailable && Playback.player.isPlaying && glow.isShown) sample()
            ui.postDelayed(this, 250)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (!primed) reset()
        glow.background = gradient
        paint()
        ui.post(tick)
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
    }

    /** Fades the glow out (used when the feature is switched off). */
    fun reset() {
        val bg = Ui.bg(glow.context)
        top = bg
        bottom = bg
        primed = true
        paint()
    }

    /** Re-applies the colours, e.g. after the app theme changed. */
    fun refresh() = paint()

    private fun sample() {
        try {
            surface.getBitmap(bmp)
        } catch (e: Exception) {
            return
        }
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        val upper = average(0, 4)
        val lower = average(5, H)
        top = mix(top, upper, 0.4f)
        bottom = mix(bottom, lower, 0.4f)
        paint()
    }

    private fun average(fromRow: Int, toRow: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in fromRow until toRow) {
            for (x in 0 until W) {
                val c = px[y * W + x]
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
        }
        if (n == 0) return Color.BLACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun paint() {
        val bg = Ui.bg(glow.context)
        gradient.colors = intArrayOf(mix(bg, top, 0.75f), mix(bg, bottom, 0.5f), bg)
        glow.invalidate()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = Color.red(a) + (Color.red(b) - Color.red(a)) * t
        val g = Color.green(a) + (Color.green(b) - Color.green(a)) * t
        val bl = Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t
        return Color.rgb(r.toInt(), g.toInt(), bl.toInt())
    }

    private companion object {
        const val W = 16
        const val H = 9
    }
}
