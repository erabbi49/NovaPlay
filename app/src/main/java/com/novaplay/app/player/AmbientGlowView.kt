package com.novaplay.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.novaplay.app.data.Prefs
import com.novaplay.app.ui.Ui

/**
 * The picture of the "enhanced ambient mode": a small copy of the current video frame stretched over the player's
 * area (so every edge of the picture lights the part of the page next to it), its edge colours carried on over the
 * rest of the view, then blurred and softened by a scrim so the text on top stays readable.
 * [AmbientController] feeds it frames.
 */
class AmbientGlowView(context: Context) : View(context) {

    enum class Mode {
        /** Behind the watch page: strongest around the player, fading into the page colour further down. */
        PAGE,

        /** Behind a fullscreen player: fills the bars; a flat dimming layer on top. */
        FULL
    }

    var mode = Mode.PAGE
        set(v) {
            if (field != v) {
                field = v
                shaderKey = ""
                invalidate()
            }
        }

    /** Top and bottom edge of the player inside this view, in pixels (the glow is strongest between them). */
    var playerTop: () -> Int = { 0 }
    var playerBottom: () -> Int = { 0 }

    private var frame: Bitmap? = null
    private var srcRows = 1
    private var buf = IntArray(0)
    private var edgeR = FloatArray(0)
    private var edgeG = FloatArray(0)
    private var edgeB = FloatArray(0)
    private var picShader: BitmapShader? = null
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val matrix = Matrix()
    private var shaderKey = ""

    init {
        setWillNotDraw(false)
        configure()
    }

    /** Reads the settings that change how the glow looks (strength, blur). Call after they change. */
    fun configure() {
        val level = Prefs.ambientBlur.coerceIn(0, 3)
        val cm = ColorMatrix()
        cm.setSaturation(SATURATION[level])
        picture.colorFilter = ColorMatrixColorFilter(cm)
        if (Build.VERSION.SDK_INT >= 31) {
            val r = RADIUS[level]
            setRenderEffect(if (r > 0f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
        }
        shaderKey = ""
        invalidate()
    }

    /** Shows a new picture: [px] holds [w] x [h] ARGB pixels. */
    fun setPixels(w: Int, h: Int, px: IntArray) {
        val total = h * 2 // the picture plus as many rows again for the light that spills below it
        var f = frame
        if (f == null || f.width != w || f.height != total) {
            f = Bitmap.createBitmap(w, total, Bitmap.Config.ARGB_8888)
            frame = f
            buf = IntArray(w * total)
        }
        srcRows = h
        System.arraycopy(px, 0, buf, 0, w * h)
        spillBelow(w, h, total - h)
        f.setPixels(buf, 0, w, 0, 0, w, total)
        // a fresh shader per picture, so the GPU can never keep showing an older copy of the pixels
        picShader = BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        invalidate()
    }

    /**
     * Fills the [rows] under the picture: first its own bottom edge, then a blurred copy of that edge, finally the
     * tint of the picture's lower half. A plain "repeat the last row" would pull every dark spot at the bottom edge
     * into a long streak down the page.
     */
    private fun spillBelow(w: Int, h: Int, rows: Int) {
        val from = h / 2
        var mr = 0f
        var mg = 0f
        var mb = 0f
        for (y in from until h) {
            for (x in 0 until w) {
                val c = buf[y * w + x]
                mr += Color.red(c)
                mg += Color.green(c)
                mb += Color.blue(c)
            }
        }
        val n = ((h - from) * w).coerceAtLeast(1)
        mr /= n
        mg /= n
        mb /= n

        if (edgeR.size != w) {
            edgeR = FloatArray(w)
            edgeG = FloatArray(w)
            edgeB = FloatArray(w)
        }
        val last = (h - 1) * w
        val half = (w / 6).coerceAtLeast(1)
        for (x in 0 until w) {
            var sr = 0f
            var sg = 0f
            var sb = 0f
            for (k in -half..half) {
                val c = buf[last + (x + k).coerceIn(0, w - 1)]
                sr += Color.red(c)
                sg += Color.green(c)
                sb += Color.blue(c)
            }
            val cnt = 2 * half + 1
            edgeR[x] = sr / cnt
            edgeG[x] = sg / cnt
            edgeB[x] = sb / cnt
        }
        for (k in 1..rows) {
            val t = k.toFloat() / rows
            val a1 = (2f * t).coerceAtMost(1f)
            val a2 = (2f * t - 1f).coerceAtLeast(0f)
            for (x in 0 until w) {
                val c = buf[last + x]
                var r = Color.red(c) * (1f - a1) + edgeR[x] * a1
                var g = Color.green(c) * (1f - a1) + edgeG[x] * a1
                var b = Color.blue(c) * (1f - a1) + edgeB[x] * a1
                r = r * (1f - a2) + mr * a2
                g = g * (1f - a2) + mg * a2
                b = b * (1f - a2) + mb * a2
                buf[(h - 1 + k) * w + x] = Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
            }
        }
    }

    fun clearFrame() {
        frame = null
        picShader = null
        buf = IntArray(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        val sh = picShader ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val wf = w.toFloat()
        val hf = h.toFloat()

        // where the picture lands: all of the view in fullscreen, the player's own area on the watch page
        var top = 0f
        var bottom = hf
        if (mode == Mode.PAGE) {
            val t = playerTop().coerceIn(0, h)
            val b = playerBottom().coerceIn(0, h)
            if (b - t > 8) {
                top = t.toFloat()
                bottom = b.toFloat()
            }
        }
        matrix.setScale(wf / f.width, (bottom - top) / srcRows)
        matrix.postTranslate(0f, top)
        sh.setLocalMatrix(matrix)
        picture.shader = sh
        canvas.drawRect(0f, 0f, wf, hf, picture)

        val s = (Prefs.ambientStrength / 100f).coerceIn(0.2f, 1f)
        if (mode == Mode.FULL) {
            scrim.shader = null
            scrim.color = Color.argb(((0.22f + (1f - s) * 0.6f) * 255).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawRect(0f, 0f, wf, hf, scrim)
            return
        }
        val bg = Ui.bg(context)
        val pb = bottom.toInt()
        val key = "$w/$h/$pb/${Prefs.ambientStrength}/${Prefs.ambientReadable}/$bg"
        if (key != shaderKey) {
            shaderKey = key
            val readable = Prefs.ambientReadable
            val near = (1f - s) * 0.85f // over the player's own area, which only shows in the bars around the video
            // right under the player the title and channel sit: with "keep text readable" the light is held back there
            val edge = if (readable) maxOf(near, 1f - s * 0.6f) else near
            val below = if (readable) (edge + 0.2f).coerceAtMost(0.93f) else (near + 0.28f).coerceAtMost(0.93f)
            val far = (below + 0.1f).coerceAtMost(0.97f)
            val p1 = (pb.toFloat() / h).coerceIn(0.05f, 0.8f)
            val p2 = (p1 + 0.16f).coerceAtMost(0.95f)
            scrim.shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(withAlpha(bg, near), withAlpha(bg, near), withAlpha(bg, edge), withAlpha(bg, below), withAlpha(bg, far)),
                floatArrayOf(0f, p1 - 0.002f, p1, p2, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, wf, hf, scrim)
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        /** Extra saturation per blur level (a blurred picture looks washed out, so it gets more colour). */
        val SATURATION = floatArrayOf(1.2f, 1.3f, 1.4f, 1.5f)

        /** Blur radius in pixels per blur level (Android 12+; older versions rely on the small picture alone). */
        val RADIUS = floatArrayOf(0f, 10f, 28f, 55f)
    }
}
