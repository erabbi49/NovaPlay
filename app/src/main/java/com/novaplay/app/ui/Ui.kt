package com.novaplay.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.novaplay.app.R
import com.novaplay.app.data.Prefs

/** Small helpers so screens can be built in code without XML layouts. */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    private fun amoled() = Prefs.theme == Prefs.THEME_AMOLED

    fun bg(ctx: Context): Int = if (amoled()) Color.BLACK else ctx.getColor(R.color.bg)
    fun surface(ctx: Context): Int = if (amoled()) Color.BLACK else ctx.getColor(R.color.surface)
    fun surfaceVariant(ctx: Context): Int = if (amoled()) 0xFF1A1A1A.toInt() else ctx.getColor(R.color.surface_variant)
    fun text(ctx: Context): Int = ctx.getColor(R.color.on_surface)
    fun textDim(ctx: Context): Int = ctx.getColor(R.color.on_surface_variant)
    fun divider(ctx: Context): Int = ctx.getColor(R.color.divider)
    @Suppress("UNUSED_PARAMETER")
    fun accent(ctx: Context): Int = Prefs.accent
    fun chipBg(ctx: Context): Int = if (amoled()) 0xFF1F1F1F.toInt() else ctx.getColor(R.color.chip_bg)
    fun chipSelected(ctx: Context): Int = ctx.getColor(R.color.chip_selected)
    fun chipSelectedText(ctx: Context): Int = ctx.getColor(R.color.chip_selected_text)
    fun placeholder(ctx: Context): Int = if (amoled()) 0xFF141414.toInt() else ctx.getColor(R.color.thumb_placeholder)
    fun sheet(ctx: Context): Int = if (amoled()) 0xFF1A1A1A.toInt() else ctx.getColor(R.color.sheet)
    fun skeleton(ctx: Context): Int = if (amoled()) 0xFF1A1A1A.toInt() else ctx.getColor(R.color.skeleton)

    /** Roboto Medium: the weight used for titles, chips and buttons. */
    fun <T : TextView> medium(t: T): T {
        t.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        return t
    }

    fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    /** Colours a switch with the accent colour when it is on. */
    fun tintSwitch(ctx: Context, sw: android.widget.Switch) {
        val on = intArrayOf(android.R.attr.state_checked)
        val off = intArrayOf()
        val states = arrayOf(on, off)
        val accent = accent(ctx)
        val dim = textDim(ctx)
        sw.thumbTintList = ColorStateList(states, intArrayOf(accent, dim))
        sw.trackTintList = ColorStateList(
            states,
            intArrayOf(accent and 0x00FFFFFF or 0x80000000.toInt(), dim and 0x00FFFFFF or 0x55000000)
        )
    }

    fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx
        return d
    }

    /** Touch feedback for icon buttons and rows. */
    fun ripple(ctx: Context, content: Drawable?, borderless: Boolean = false): Drawable {
        val tv = TypedValue()
        val rippleColor = if (ctx.theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)) tv.data else 0x33888888
        val mask: Drawable? = if (borderless || content != null) null else rounded(Color.WHITE, 0f)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun textView(ctx: Context, sizeSp: Float, color: Int, bold: Boolean = false, lines: Int = 0): TextView {
        val t = TextView(ctx)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        if (lines > 0) {
            t.maxLines = lines
            t.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        return t
    }

    fun iconButton(ctx: Context, res: Int, desc: String, tint: Int? = null, sizeDp: Int = 44, padDp: Int = 10): ImageButton {
        val b = ImageButton(ctx)
        b.setImageResource(res)
        b.contentDescription = desc
        b.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(ctx, padDp)
        b.setPadding(pad, pad, pad, pad)
        b.background = ripple(ctx, null, true)
        if (tint != null) b.setColorFilter(tint)
        b.layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        return b
    }

    /** A rounded "chip" label, selected or not. */
    fun chip(ctx: Context, label: String, selected: Boolean): TextView {
        val t = medium(textView(ctx, 14f, if (selected) chipSelectedText(ctx) else text(ctx)))
        t.text = label
        t.gravity = android.view.Gravity.CENTER
        t.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        t.background = rounded(if (selected) chipSelected(ctx) else chipBg(ctx), dpf(ctx, 8f))
        t.minHeight = dp(ctx, 32)
        return t
    }

    /** A fully rounded 36dp button (Subscribe, action pills), selected = inverted colours. */
    fun pill(ctx: Context, label: String, selected: Boolean): TextView {
        val t = medium(textView(ctx, 14f, if (selected) chipSelectedText(ctx) else text(ctx)))
        t.text = label
        t.gravity = android.view.Gravity.CENTER
        t.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        t.background = rounded(if (selected) chipSelected(ctx) else chipBg(ctx), dpf(ctx, 18f))
        t.minHeight = dp(ctx, 36)
        return t
    }

    fun spacer(ctx: Context, widthDp: Int = 0, heightDp: Int = 0): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(dp(ctx, widthDp), dp(ctx, heightDp))
        return v
    }

    fun hr(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(divider(ctx))
        v.layoutParams = LinearLayout.LayoutParams(MATCH, 1)
        return v
    }

    fun frame(w: Int, h: Int): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h)

    fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    fun formatSeconds(sec: Int): String = if (sec <= 0) "" else formatTime(sec * 1000L)

    fun formatBytes(b: Long): String {
        if (b < 0) return "?"
        val kb = 1024.0
        return when {
            b < kb * kb -> String.format("%.0f KB", b / kb)
            b < kb * kb * kb -> String.format("%.1f MB", b / (kb * kb))
            else -> String.format("%.2f GB", b / (kb * kb * kb))
        }
    }
}
