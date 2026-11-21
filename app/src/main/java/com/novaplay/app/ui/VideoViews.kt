package com.novaplay.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import coil.load
import com.novaplay.app.R
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.Video
import com.novaplay.app.web.Site
import kotlin.math.abs

/** What a list row can do. Screens create one and share it between all rows. */
class VideoActions(
    val onOpen: (Video) -> Unit,
    val onMore: (Video) -> Unit,
    val onChannel: ((Video) -> Unit)? = null
)

/** Thumbnails: Coil, with the headers the image CDN expects. */
object Thumbs {
    private var agent: String? = null

    /** [alt] is tried once when [url] fails to load. */
    fun load(view: ImageView, url: String, alt: String = "") {
        if (url.isEmpty()) {
            view.dispose()
            view.setImageDrawable(null)
            return
        }
        val ua = agent ?: Site.browserUserAgent(WebSettings.getDefaultUserAgent(view.context)).also { agent = it }
        view.load(url) {
            crossfade(true)
            addHeader("Referer", Site.base + "/")
            addHeader("User-Agent", ua)
            if (alt.isNotEmpty() && alt != url) {
                listener(onError = { _, _ ->
                    view.load(alt) {
                        crossfade(true)
                        addHeader("Referer", Site.base + "/")
                        addHeader("User-Agent", ua)
                    }
                })
            }
        }
    }
}

/** A FrameLayout that keeps a fixed width : height ratio. */
open class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w / ratio).toInt(), MeasureSpec.EXACTLY))
    }
}

fun Video.metaLine(): String {
    val parts = ArrayList<String>(2)
    if (uploader.isNotEmpty()) parts.add(uploader)
    if (views.isNotEmpty()) parts.add(views)
    return parts.joinToString(" • ")
}

private fun clipRounded(view: View, radiusDp: Int) {
    val r = Ui.dpf(view.context, radiusDp.toFloat())
    view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
            outline.setRoundRect(0, 0, v.width, v.height, r)
        }
    }
    view.clipToOutline = true
}

private fun badge(ctx: Context): TextView {
    val t = Ui.medium(Ui.textView(ctx, 12f, Color.WHITE, false, 1))
    t.setBackgroundResource(R.drawable.bg_badge)
    t.setPadding(Ui.dp(ctx, 5), Ui.dp(ctx, 1), Ui.dp(ctx, 5), Ui.dp(ctx, 2))
    t.visibility = View.GONE
    return t
}

/** The thumbnail with its duration / quality badges and the "already watched" bar. */
class ThumbBox(context: Context, ratio: Float, cornerDp: Int) : AspectFrame(context, ratio) {
    private val image = ImageView(context)
    private val duration = badge(context)
    private val quality = badge(context)
    private val watched = View(context)

    init {
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setBackgroundColor(Ui.placeholder(context))
        addView(image, Ui.frame(Ui.MATCH, Ui.MATCH))
        val m = Ui.dp(context, 6)
        val dp = Ui.frame(Ui.WRAP, Ui.WRAP)
        dp.gravity = Gravity.BOTTOM or Gravity.END
        dp.setMargins(m, m, m, m + Ui.dp(context, 2))
        addView(duration, dp)
        val qp = Ui.frame(Ui.WRAP, Ui.WRAP)
        qp.gravity = Gravity.TOP or Gravity.START
        qp.setMargins(m, m, m, m)
        addView(quality, qp)
        val wp = Ui.frame(Ui.MATCH, Ui.dp(context, 3))
        wp.gravity = Gravity.BOTTOM or Gravity.START
        watched.setBackgroundColor(Ui.accent(context))
        watched.visibility = View.GONE
        addView(watched, wp)
        clipRounded(this, cornerDp)
    }

    fun bind(v: Video) {
        Thumbs.load(image, v.thumb, v.thumbAlt)
        val d = if (Prefs.thumbBadges) Ui.formatSeconds(v.durationSec) else ""
        duration.text = d
        duration.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
        val q = if (Prefs.thumbBadges) v.quality else ""
        quality.text = q
        quality.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        val pos = Library.positionFor(v.id)
        val total = v.durationSec * 1000L
        if (pos > 0L && total > 0L) {
            val frac = (pos.toFloat() / total).coerceIn(0f, 1f)
            watched.visibility = View.VISIBLE
            watched.post {
                val lp = watched.layoutParams
                lp.width = (width * frac).toInt()
                watched.layoutParams = lp
            }
        } else {
            watched.visibility = View.GONE
        }
    }
}

internal fun avatarColor(name: String): Int {
    val hue = (abs(name.hashCode()) % 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.55f))
}

/**
 * Big feed card, laid out like the current YouTube app: a rounded thumbnail inset from the screen edges, then the
 * channel avatar, a two-line title, the meta line and a "more" button.
 */
class VideoCardView(context: Context) : LinearLayout(context) {
    private val thumb = ThumbBox(context, 16f / 9f, 12)
    private val avatar = Ui.medium(Ui.textView(context, 15f, Color.WHITE, false, 1))
    private val title = Ui.medium(Ui.textView(context, 14f, Ui.text(context), false, 2))
    private val meta = Ui.textView(context, 12f, Ui.textDim(context), false, 2)
    private val more = Ui.iconButton(context, R.drawable.ic_more, "More", Ui.textDim(context), 40, 8)

    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP)
        title.setLineSpacing(0f, 1.1f)
        val side = Ui.dp(context, 12)
        val tp = Ui.lp(Ui.MATCH, Ui.WRAP)
        tp.setMargins(side, Ui.dp(context, 6), side, 0)
        addView(thumb, tp)
        val info = LinearLayout(context)
        info.orientation = HORIZONTAL
        info.gravity = Gravity.TOP
        info.setPadding(side, Ui.dp(context, 12), Ui.dp(context, 0), Ui.dp(context, 12))
        avatar.gravity = Gravity.CENTER
        val size = Ui.dp(context, 36)
        info.addView(avatar, Ui.lp(size, size))
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        col.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(meta, Ui.lp(Ui.MATCH, Ui.WRAP))
        val cp = Ui.lp(0, Ui.WRAP)
        cp.weight = 1f
        cp.leftMargin = Ui.dp(context, 12)
        info.addView(col, cp)
        val mp = Ui.lp(Ui.dp(context, 40), Ui.dp(context, 40))
        mp.topMargin = -Ui.dp(context, 8)
        info.addView(more, mp)
        addView(info, Ui.lp(Ui.MATCH, Ui.WRAP))
        background = Ui.ripple(context, null)
    }

    fun bind(v: Video, actions: VideoActions, highlight: Boolean = false) {
        thumb.bind(v)
        title.text = v.title
        title.setTextColor(if (highlight) Ui.accent(context) else Ui.text(context))
        meta.text = v.metaLine()
        val name = v.uploader.ifEmpty { "?" }
        avatar.text = name.substring(0, name.offsetByCodePoints(0, 1)).uppercase()
        avatar.background = Ui.rounded(avatarColor(name), Ui.dpf(context, 18f))
        val channel = actions.onChannel
        if (channel != null && v.uploaderUrl.isNotEmpty()) {
            avatar.setOnClickListener { channel(v) }
            avatar.isClickable = true
        } else {
            avatar.setOnClickListener(null)
            avatar.isClickable = false
        }
        setOnClickListener { actions.onOpen(v) }
        setOnLongClickListener {
            actions.onMore(v)
            true
        }
        more.setOnClickListener { actions.onMore(v) }
    }
}

/** Compact row: small thumbnail on the left, text on the right. Used for related, queue, history, downloads. */
open class VideoRowView(context: Context) : LinearLayout(context) {
    protected val thumb = ThumbBox(context, 16f / 9f, 8)
    protected val title = Ui.textView(context, 14f, Ui.text(context), false, 2)
    protected val meta = Ui.textView(context, 12f, Ui.textDim(context), false, 1)
    protected val extra = Ui.textView(context, 12f, Ui.textDim(context), false, 1)
    protected val textColumn = LinearLayout(context)
    val dragHandle: ImageButton = Ui.iconButton(context, R.drawable.ic_drag, "Reorder", Ui.textDim(context), 36, 6)
    val more: ImageButton = Ui.iconButton(context, R.drawable.ic_more, "More", Ui.textDim(context), 40, 8)

    init {
        orientation = HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP)
        setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6))
        gravity = Gravity.TOP
        dragHandle.visibility = View.GONE
        addView(dragHandle, Ui.lp(Ui.dp(context, 36), Ui.dp(context, 36)))
        addView(thumb, Ui.lp(Ui.dp(context, 156), Ui.WRAP))
        textColumn.orientation = VERTICAL
        textColumn.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
        textColumn.addView(meta, Ui.lp(Ui.MATCH, Ui.WRAP))
        textColumn.addView(extra, Ui.lp(Ui.MATCH, Ui.WRAP))
        val cp = Ui.lp(0, Ui.WRAP)
        cp.weight = 1f
        cp.leftMargin = Ui.dp(context, 10)
        addView(textColumn, cp)
        addView(more, Ui.lp(Ui.dp(context, 40), Ui.dp(context, 40)))
        background = Ui.ripple(context, null)
    }

    /** [detail] is an optional third line (e.g. download progress). */
    fun bind(v: Video, actions: VideoActions, highlight: Boolean = false, detail: String = "") {
        thumb.bind(v)
        title.text = v.title
        title.setTextColor(if (highlight) Ui.accent(context) else Ui.text(context))
        meta.text = v.metaLine()
        extra.text = detail
        extra.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        setOnClickListener { actions.onOpen(v) }
        setOnLongClickListener {
            actions.onMore(v)
            true
        }
        more.setOnClickListener { actions.onMore(v) }
    }
}
