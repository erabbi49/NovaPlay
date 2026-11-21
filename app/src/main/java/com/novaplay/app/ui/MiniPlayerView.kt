package com.novaplay.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import com.novaplay.app.R
import com.novaplay.app.data.Prefs
import com.novaplay.app.player.Playback

/** The strip above the tab bar that keeps the current video one tap away. */
class MiniPlayerView(context: Context, private val onOpen: () -> Unit) : LinearLayout(context) {
    private val ui = Handler(Looper.getMainLooper())
    private val thumb = ImageView(context)
    private val title = Ui.medium(Ui.textView(context, 14f, Ui.text(context), false, 1))
    private val meta = Ui.textView(context, 12f, Ui.textDim(context), false, 1)
    private val play = Ui.iconButton(context, R.drawable.ic_play, "Play / pause", Ui.text(context), 44, 10)
    private val next = Ui.iconButton(context, R.drawable.ic_next, "Next", Ui.text(context), 44, 10)
    private val close = Ui.iconButton(context, R.drawable.ic_close, "Close", Ui.textDim(context), 44, 12)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)

    private val playbackListener = object : Playback.Listener {
        override fun onVideoChanged() = refresh()
        override fun onStatusChanged() = refresh()
        override fun onQueueChanged() = refresh()
    }
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
    }
    private val tick = object : Runnable {
        override fun run() {
            progress()
            ui.postDelayed(this, 500)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.surfaceVariant(context))
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(Ui.accent(context))
        bar.progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        addView(bar, Ui.lp(Ui.MATCH, Ui.dp(context, 2)))

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 2), Ui.dp(context, 4))
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(Ui.placeholder(context))
        thumb.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, Ui.dpf(v.context, 6f))
            }
        }
        thumb.clipToOutline = true
        row.addView(thumb, Ui.lp(Ui.dp(context, 88), Ui.dp(context, 50)))
        val texts = LinearLayout(context)
        texts.orientation = VERTICAL
        texts.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
        texts.addView(meta, Ui.lp(Ui.MATCH, Ui.WRAP))
        val tp = Ui.lp(0, Ui.WRAP)
        tp.weight = 1f
        tp.leftMargin = Ui.dp(context, 10)
        row.addView(texts, tp)
        row.addView(play)
        row.addView(next)
        row.addView(close)
        addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))

        setOnClickListener { onOpen() }
        play.setOnClickListener { Playback.togglePlay() }
        next.setOnClickListener { Playback.next() }
        close.setOnClickListener { Playback.stop() }
        visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Playback.addListener(playbackListener)
        Playback.player.addListener(playerListener)
        ui.post(tick)
        refresh()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(tick)
        Playback.removeListener(playbackListener)
        Playback.player.removeListener(playerListener)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        val v = Playback.current
        if (!Prefs.miniPlayer || !Playback.hasSession || v == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        title.text = v.title
        meta.text = when {
            Playback.status == Playback.Status.RESOLVING -> "Loading…"
            Playback.status == Playback.Status.ERROR -> Playback.errorMessage ?: "Playback failed"
            else -> v.uploader
        }
        Thumbs.load(thumb, v.thumb)
        val p = Playback.player
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && Playback.status != Playback.Status.ERROR
        play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        next.alpha = if (Playback.hasNext()) 1f else 0.4f
        progress()
    }

    private fun progress() {
        if (visibility != View.VISIBLE) return
        val p = Playback.player
        val dur = p.duration
        bar.progress = if (dur > 0) (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000) else 0
    }
}
