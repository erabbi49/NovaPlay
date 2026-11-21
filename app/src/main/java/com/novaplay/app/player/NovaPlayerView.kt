package com.novaplay.app.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.novaplay.app.R
import com.novaplay.app.data.Prefs
import com.novaplay.app.ui.BottomSheet
import com.novaplay.app.ui.Ui
import com.novaplay.app.web.SiteException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The video surface with YouTube-style controls: tap to show them, double-tap to seek, swipe on
 * the sides for brightness / volume, swipe across to scrub, hold for 2x speed.
 */
class NovaPlayerView(context: Context) : FrameLayout(context) {

    interface Host {
        fun onMinimize() {}
        fun onToggleFullscreen() {}
        fun onEnterPip() {}
        fun onToggleAmbient() {}
        fun onOpenInBrowser() {}
        fun hostWindow(): Window? = null
    }

    var host: Host? = null
    val surface = TextureView(context)

    /** 0 = fit, 1 = stretch, 2 = zoom (crop). */
    var resizeMode = 0
        set(v) {
            field = v
            requestLayout()
        }

    private val ui = Handler(Looper.getMainLooper())
    private val white = Color.WHITE
    private var videoRatio = 16f / 9f
    private var controlsShown = true
    private var locked = false
    private var pipMode = false
    private var scrubbing = false
    private var fullscreen = false
    private var ambientOn = false

    // ---------- views ----------
    private val topBar = LinearLayout(context)
    private val bottomBar = LinearLayout(context)
    private val center = LinearLayout(context)
    private val titleView = Ui.textView(context, 15f, white, true, 1)
    private val btnBack = Ui.iconButton(context, R.drawable.ic_expand, "Minimize", white)
    private val btnAmbient = Ui.iconButton(context, R.drawable.ic_ambient, "Ambient mode", white)
    private val btnPip = Ui.iconButton(context, R.drawable.ic_pip, "Picture in picture", white)
    private val btnMore = Ui.iconButton(context, R.drawable.ic_more, "More", white)
    private val btnPrev = Ui.iconButton(context, R.drawable.ic_prev, "Previous", white, 52, 12)
    private val btnPlay = Ui.iconButton(context, R.drawable.ic_play, "Play / pause", white, 68, 16)
    private val btnNext = Ui.iconButton(context, R.drawable.ic_next, "Next", white, 52, 12)
    private val btnLock = Ui.iconButton(context, R.drawable.ic_lock, "Lock controls", white)
    private val timeText = Ui.textView(context, 12f, white)
    private val btnSpeed = Ui.textView(context, 13f, white, true)
    private val btnQuality = Ui.iconButton(context, R.drawable.ic_tune, "Quality", white, 40, 9)
    private val btnLoop = Ui.iconButton(context, R.drawable.ic_repeat, "Loop", white, 40, 9)
    private val btnFullscreen = Ui.iconButton(context, R.drawable.ic_fullscreen, "Fullscreen", white, 40, 9)
    private val seekBar = SeekBar(context)
    private val spinner = ProgressBar(context)
    private val hud = Ui.textView(context, 14f, white, true)
    private val pillLeft = Ui.textView(context, 15f, white, true)
    private val pillRight = Ui.textView(context, 15f, white, true)
    private val errorBox = LinearLayout(context)
    private val errorText = Ui.textView(context, 14f, white)
    private val btnRetry = Ui.textView(context, 14f, white, true)
    private val btnBrowser = Ui.textView(context, 14f, white, true)

    // ---------- gesture state ----------
    private enum class Mode { NONE, BRIGHTNESS, VOLUME, SEEK }

    private var mode = Mode.NONE
    private var startPos = 0L
    private var scrubTarget = 0L
    private var brightness = 0.5f
    private var volumeLevel = 0.5f
    private var boosting = false
    private var speedBefore = 1f
    private var lastSeekAt = 0L
    private var lastSeekSide = 0
    private var seekAccumMs = 0L
    private var swallowConfirm = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val hideControls = Runnable { setControlsVisible(false) }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hidePills = Runnable {
        pillLeft.visibility = View.GONE
        pillRight.visibility = View.GONE
        seekAccumMs = 0L
    }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 400)
        }
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            mode = Mode.NONE
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!locked && System.currentTimeMillis() - lastSeekAt < 700) {
                val side = sideOf(e)
                if (side != 0 && side == lastSeekSide) {
                    swallowConfirm = true
                    seekSide(side)
                }
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (swallowConfirm) {
                swallowConfirm = false
                return true
            }
            if (locked) {
                showLockOnly()
            } else {
                setControlsVisible(!controlsShown)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked) return true
            val side = sideOf(e)
            if (side == 0) Playback.togglePlay() else seekSide(side)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (locked || !Prefs.gestures || !Prefs.holdToSpeed || pipMode) return
            if (Playback.player.isPlaying) {
                boosting = true
                speedBefore = Playback.speed
                Playback.player.setPlaybackSpeed(Prefs.holdSpeed)
                showHud(formatSpeed(Prefs.holdSpeed) + " speed")
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (locked || !Prefs.gestures || pipMode || e1 == null || boosting) return false
            if (mode == Mode.NONE) {
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < slop && abs(dy) < slop) return false
                val wanted = if (abs(dy) > abs(dx)) (if (e1.x < width / 2f) Mode.BRIGHTNESS else Mode.VOLUME) else Mode.SEEK
                val allowed = when (wanted) {
                    Mode.BRIGHTNESS -> Prefs.swipeBrightness
                    Mode.VOLUME -> Prefs.swipeVolume
                    else -> Prefs.swipeSeek
                }
                if (!allowed) return false
                mode = wanted
                beginGesture()
            }
            val sens = Prefs.swipeSensitivity.coerceIn(50, 200) / 100f
            when (mode) {
                Mode.BRIGHTNESS -> changeBrightness(distanceY / height * sens)
                Mode.VOLUME -> changeVolume(distanceY / height * sens)
                Mode.SEEK -> {
                    val dur = Playback.player.duration
                    if (dur > 0) {
                        val delta = ((e2.x - e1.x) / width * 120_000f).toLong()
                        scrubTarget = (startPos + delta).coerceIn(0L, dur)
                        val diff = scrubTarget - startPos
                        showHud((if (diff >= 0) "+" else "-") + Ui.formatTime(abs(diff)) + "  (" + Ui.formatTime(scrubTarget) + ")")
                    }
                }
                Mode.NONE -> {}
            }
            return true
        }
    })

    /** Pinch out: fill the screen (crop); pinch in: fit the whole picture. */
    private var pinch = 1f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinch = 1f
            return Prefs.pinchZoom && !locked && !pipMode
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinch *= d.scaleFactor
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (pinch > 1.12f && resizeMode != 2) {
                resizeMode = 2
                showHud("Zoom to fill")
            } else if (pinch < 0.88f && resizeMode != 0) {
                resizeMode = 0
                showHud("Fit to screen")
            }
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, Ui.frame(Ui.MATCH, Ui.MATCH))
        buildTop()
        buildCenter()
        buildBottom()
        buildOverlays()
        wireClicks()
        applyState()
    }

    // ---------- construction ----------

    private fun buildTop() {
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundResource(R.drawable.scrim_top)
        val p = Ui.dp(context, 4)
        topBar.setPadding(p, p, p, Ui.dp(context, 16))
        topBar.addView(btnBack)
        val tl = Ui.lp(0, Ui.WRAP)
        tl.weight = 1f
        titleView.layoutParams = tl
        titleView.setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 8), 0)
        topBar.addView(titleView)
        topBar.addView(btnAmbient)
        topBar.addView(btnPip)
        topBar.addView(btnMore)
        addView(topBar, Ui.frame(Ui.MATCH, Ui.WRAP))
    }

    private fun buildCenter() {
        center.orientation = LinearLayout.HORIZONTAL
        center.gravity = Gravity.CENTER
        btnPlay.background = Ui.rounded(0x66000000, Ui.dpf(context, 34f))
        center.addView(btnPrev)
        val m = Ui.dp(context, 20)
        val pl = Ui.lp(Ui.dp(context, 68), Ui.dp(context, 68))
        pl.setMargins(m, 0, m, 0)
        btnPlay.layoutParams = pl
        center.addView(btnPlay)
        center.addView(btnNext)
        val lp = Ui.frame(Ui.WRAP, Ui.WRAP)
        lp.gravity = Gravity.CENTER
        addView(center, lp)
        val ll = Ui.frame(Ui.dp(context, 44), Ui.dp(context, 44))
        ll.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        ll.leftMargin = Ui.dp(context, 12)
        btnLock.layoutParams = ll
        addView(btnLock)
    }

    private fun buildBottom() {
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.setBackgroundResource(R.drawable.scrim_bottom)
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 4), 0)
        val tl = Ui.lp(0, Ui.WRAP)
        tl.weight = 1f
        timeText.layoutParams = tl
        row.addView(timeText)
        btnSpeed.gravity = Gravity.CENTER
        btnSpeed.minWidth = Ui.dp(context, 44)
        btnSpeed.minHeight = Ui.dp(context, 40)
        btnSpeed.layoutParams = Ui.lp(Ui.WRAP, Ui.dp(context, 40))
        row.addView(btnSpeed)
        row.addView(btnQuality)
        row.addView(btnLoop)
        row.addView(btnFullscreen)
        bottomBar.addView(row)

        seekBar.max = 1000
        val accent = Ui.accent(context)
        seekBar.progressTintList = ColorStateList.valueOf(accent)
        seekBar.thumbTintList = ColorStateList.valueOf(accent)
        seekBar.secondaryProgressTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        seekBar.setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0)
        bottomBar.addView(seekBar, Ui.lp(Ui.MATCH, Ui.dp(context, 28)))
        val lp = Ui.frame(Ui.MATCH, Ui.WRAP)
        lp.gravity = Gravity.BOTTOM
        addView(bottomBar, lp)
    }

    private fun pill(t: TextView) {
        t.background = Ui.rounded(0x99000000.toInt(), Ui.dpf(context, 18f))
        t.setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
        t.visibility = View.GONE
    }

    private fun buildOverlays() {
        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(white)
        val sl = Ui.frame(Ui.dp(context, 44), Ui.dp(context, 44))
        sl.gravity = Gravity.CENTER
        addView(spinner, sl)

        pill(hud)
        val hl = Ui.frame(Ui.WRAP, Ui.WRAP)
        hl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        hl.topMargin = Ui.dp(context, 24)
        addView(hud, hl)

        pill(pillLeft)
        pill(pillRight)
        val ll = Ui.frame(Ui.WRAP, Ui.WRAP)
        ll.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        ll.leftMargin = Ui.dp(context, 56)
        addView(pillLeft, ll)
        val rl = Ui.frame(Ui.WRAP, Ui.WRAP)
        rl.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        rl.rightMargin = Ui.dp(context, 56)
        addView(pillRight, rl)

        errorBox.orientation = LinearLayout.VERTICAL
        errorBox.gravity = Gravity.CENTER_HORIZONTAL
        errorBox.setBackgroundColor(0xCC000000.toInt())
        errorBox.setPadding(Ui.dp(context, 24), Ui.dp(context, 16), Ui.dp(context, 24), Ui.dp(context, 16))
        errorText.gravity = Gravity.CENTER
        errorBox.addView(errorText, Ui.lp(Ui.MATCH, Ui.WRAP))
        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER
        for (b in listOf(btnRetry, btnBrowser)) {
            b.gravity = Gravity.CENTER
            b.setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8))
            b.background = Ui.rounded(Ui.accent(context), Ui.dpf(context, 8f))
            val bl = Ui.lp(Ui.WRAP, Ui.WRAP)
            bl.setMargins(Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6), 0)
            b.layoutParams = bl
            buttons.addView(b)
        }
        btnRetry.text = "Retry"
        btnBrowser.text = "Open in browser"
        errorBox.addView(buttons)
        errorBox.visibility = View.GONE
        val el = Ui.frame(Ui.MATCH, Ui.WRAP)
        el.gravity = Gravity.CENTER
        addView(errorBox, el)
    }

    private fun wireClicks() {
        btnBack.setOnClickListener { host?.onMinimize() }
        btnAmbient.setOnClickListener { host?.onToggleAmbient() }
        btnPip.setOnClickListener { host?.onEnterPip() }
        btnMore.setOnClickListener { showMoreMenu() }
        btnPlay.setOnClickListener {
            Playback.togglePlay()
            scheduleHide()
        }
        btnNext.setOnClickListener { Playback.next() }
        btnPrev.setOnClickListener { Playback.previous() }
        btnFullscreen.setOnClickListener { host?.onToggleFullscreen() }
        btnSpeed.setOnClickListener { showSpeedMenu() }
        btnQuality.setOnClickListener { showQualityMenu() }
        btnLoop.setOnClickListener { showLoopMenu() }
        btnLock.setOnClickListener { setLocked(!locked) }
        btnRetry.setOnClickListener { Playback.retry() }
        btnBrowser.setOnClickListener { host?.onOpenInBrowser() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = Playback.player.duration
                if (dur > 0) timeText.text = Ui.formatTime(dur * progress / 1000) + " / " + Ui.formatTime(dur)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                scrubbing = true
                ui.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                scrubbing = false
                val dur = Playback.player.duration
                if (dur > 0) Playback.player.seekTo(dur * s.progress / 1000)
                scheduleHide()
            }
        })
    }

    // ---------- attach / detach ----------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            applyState()
            if (isPlaying) scheduleHide() else ui.removeCallbacks(hideControls)
        }

        override fun onPlaybackStateChanged(playbackState: Int) = applyState()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = applyState()
        override fun onPlayerError(error: PlaybackException) = applyState()
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                var r = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) r = 1f / r
                videoRatio = r
                requestLayout()
            }
        }
    }

    private val playbackListener = object : Playback.Listener {
        override fun onVideoChanged() = applyState()
        override fun onStatusChanged() = applyState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val p = Playback.player
        p.addListener(playerListener)
        Playback.addListener(playbackListener)
        p.setVideoTextureView(surface)
        ui.post(tick)
        applyState()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null)
        val p = Playback.player
        p.removeListener(playerListener)
        Playback.removeListener(playbackListener)
        p.clearVideoTextureView(surface)
        if (boosting) endBoost()
        super.onDetachedFromWindow()
    }

    // ---------- layout of the video itself ----------

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return
        var sw = w
        var sh = h
        val boxRatio = w.toFloat() / h
        when (resizeMode) {
            0 -> if (videoRatio > boxRatio) sh = (w / videoRatio).roundToInt() else sw = (h * videoRatio).roundToInt()
            2 -> if (videoRatio > boxRatio) sw = (h * videoRatio).roundToInt() else sh = (w / videoRatio).roundToInt()
            else -> {}
        }
        val left = (w - sw) / 2
        val top = (h - sh) / 2
        surface.layout(left, top, left + sw, top + sh)
    }

    // ---------- state ----------

    private fun applyState() {
        val p = Playback.player
        val v = Playback.current
        titleView.text = v?.title ?: ""
        val status = Playback.status
        val err = status == Playback.Status.ERROR
        errorBox.visibility = if (err && !pipMode) View.VISIBLE else View.GONE
        if (err) {
            errorText.text = Playback.errorMessage ?: "Playback failed"
            val k = Playback.errorKind
            btnBrowser.visibility = if (k == SiteException.Kind.CHALLENGE || k == SiteException.Kind.NO_STREAM || k == SiteException.Kind.NETWORK) View.VISIBLE else View.GONE
        }
        val busy = status == Playback.Status.RESOLVING ||
            (!err && p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
        spinner.visibility = if (busy && !pipMode) View.VISIBLE else View.GONE
        val ended = p.playbackState == Player.STATE_ENDED
        val showPause = p.playWhenReady && !ended && !err
        btnPlay.setImageResource(if (ended) R.drawable.ic_refresh else if (showPause) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        btnNext.alpha = if (Playback.hasNext()) 1f else 0.4f
        btnSpeed.text = formatSpeed(Playback.speed)
        btnLoop.alpha = if (Prefs.repeatMode == 0) 0.6f else 1f
        btnLoop.setColorFilter(if (Prefs.repeatMode == 0) white else Ui.accent(context))
        btnAmbient.alpha = if (ambientOn) 1f else 0.6f
        updateProgress()
    }

    private fun formatSpeed(s: Float): String =
        (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

    private fun updateProgress() {
        if (scrubbing) return
        val p = Playback.player
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0) {
            seekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            seekBar.secondaryProgress = (p.bufferedPosition * 1000 / dur).toInt().coerceIn(0, 1000)
            timeText.text = Ui.formatTime(pos) + " / " + Ui.formatTime(dur)
        } else {
            seekBar.progress = 0
            timeText.text = Ui.formatTime(pos)
        }
    }

    fun setFullscreen(on: Boolean) {
        fullscreen = on
        btnFullscreen.setImageResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    fun setAmbientOn(on: Boolean) {
        ambientOn = on
        btnAmbient.alpha = if (on) 1f else 0.6f
    }

    /** Colour behind the video where it does not fill the view: black, or transparent to let the ambient light through. */
    fun setBarsColor(color: Int) {
        setBackgroundColor(color)
    }

    fun setPip(on: Boolean) {
        pipMode = on
        if (on) {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            center.visibility = View.GONE
            btnLock.visibility = View.GONE
            hud.visibility = View.GONE
        } else {
            controlsShown = false
            setControlsVisible(true)
        }
        applyState()
    }

    // ---------- showing / hiding controls ----------

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (controlsShown && !locked && Playback.player.isPlaying && !scrubbing) ui.postDelayed(hideControls, 3500)
    }

    fun setControlsVisible(show: Boolean) {
        if (pipMode) return
        controlsShown = show
        val v = if (show && !locked) View.VISIBLE else View.GONE
        for (g in listOf<View>(topBar, bottomBar, center)) {
            if (g.visibility != v) {
                g.visibility = v
                if (show) {
                    g.alpha = 0f
                    g.animate().alpha(1f).setDuration(150).start()
                }
            }
        }
        btnLock.visibility = if (show || locked) View.VISIBLE else View.GONE
        if (show) scheduleHide() else ui.removeCallbacks(hideControls)
    }

    private fun showLockOnly() {
        btnLock.visibility = View.VISIBLE
        ui.removeCallbacks(hideControls)
        ui.postDelayed({ if (locked) btnLock.visibility = View.GONE }, 2500)
    }

    private fun setLocked(on: Boolean) {
        locked = on
        btnLock.setImageResource(R.drawable.ic_lock)
        btnLock.alpha = if (on) 1f else 0.8f
        if (on) {
            for (g in listOf<View>(topBar, bottomBar, center)) g.visibility = View.GONE
            showLockOnly()
            showHud("Controls locked")
        } else {
            controlsShown = false
            setControlsVisible(true)
            showHud("Controls unlocked")
        }
    }

    // ---------- gestures ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (Prefs.pinchZoom && !locked && !pipMode) scaler.onTouchEvent(e)
        // two fingers belong to the pinch; the single-finger gestures only see one finger
        if (e.pointerCount == 1 && !scaler.isInProgress) detector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (boosting) endBoost()
            if (mode == Mode.SEEK && e.actionMasked == MotionEvent.ACTION_UP && Playback.player.duration > 0) {
                Playback.player.seekTo(scrubTarget)
            }
            if (mode != Mode.NONE) {
                ui.removeCallbacks(hideHud)
                ui.postDelayed(hideHud, 600)
            }
            mode = Mode.NONE
        }
        return true
    }

    private fun sideOf(e: MotionEvent): Int {
        val x = e.x / width
        return if (x < 0.4f) -1 else if (x > 0.6f) 1 else 0
    }

    private fun seekSide(side: Int) {
        val p = Playback.player
        val step = Prefs.doubleTapSeekSec * 1000L
        val dur = p.duration
        var target = p.currentPosition + side * step
        target = if (dur > 0) target.coerceIn(0L, dur) else target.coerceAtLeast(0L)
        p.seekTo(target)
        lastSeekAt = System.currentTimeMillis()
        if (side != lastSeekSide) seekAccumMs = 0L
        lastSeekSide = side
        seekAccumMs += step
        val label = (seekAccumMs / 1000).toString() + " sec"
        pillLeft.visibility = View.GONE
        pillRight.visibility = View.GONE
        val pillView = if (side < 0) pillLeft else pillRight
        pillView.text = if (side < 0) "<<  $label" else "$label  >>"
        pillView.visibility = View.VISIBLE
        ui.removeCallbacks(hidePills)
        ui.postDelayed(hidePills, 800)
    }

    private fun beginGesture() {
        val p = Playback.player
        when (mode) {
            Mode.SEEK -> {
                startPos = p.currentPosition
                scrubTarget = startPos
            }
            Mode.BRIGHTNESS -> {
                val w = host?.hostWindow()
                val cur = w?.attributes?.screenBrightness ?: -1f
                brightness = if (cur >= 0f) cur else 0.5f
            }
            Mode.VOLUME -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                volumeLevel = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            }
            Mode.NONE -> {}
        }
        ui.removeCallbacks(hideHud)
    }

    private fun changeBrightness(delta: Float) {
        val w = host?.hostWindow() ?: return
        brightness = (brightness + delta).coerceIn(0.02f, 1f)
        val lp = w.attributes
        lp.screenBrightness = brightness
        w.attributes = lp
        showHud("Brightness " + (brightness * 100).roundToInt() + "%")
    }

    private fun changeVolume(delta: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeLevel * max).roundToInt(), 0)
        showHud("Volume " + (volumeLevel * 100).roundToInt() + "%")
    }

    private fun endBoost() {
        boosting = false
        Playback.player.setPlaybackSpeed(speedBefore)
        hud.visibility = View.GONE
        applyState()
    }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud)
        if (!boosting && mode == Mode.NONE) ui.postDelayed(hideHud, 1200)
    }

    // ---------- menus ----------

    /** The speeds offered in the speed menu. */
    private fun speedSteps(): FloatArray =
        if (Prefs.extendedSpeeds) {
            floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f)
        } else {
            floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        }

    private fun showSpeedMenu() {
        val sheet = BottomSheet(context, "Playback speed")
        for (sp in speedSteps()) {
            val label = if (sp == 1f) "Normal" else formatSpeed(sp)
            sheet.choice(label, abs(sp - Playback.speed) < 0.01f) {
                Playback.setSpeed(sp)
                applyState()
            }
        }
        sheet.show()
    }

    private fun qualityLabel(): String {
        val opts = Playback.qualityOptions()
        return opts.firstOrNull { it.height == Playback.forcedHeight }?.label ?: "Auto"
    }

    private fun showQualityMenu() {
        val opts = Playback.qualityOptions()
        if (opts.size <= 1) {
            Playback.toast("Only one quality is available")
            return
        }
        val sheet = BottomSheet(context, "Quality")
        for (o in opts) sheet.choice(o.label, o.height == Playback.forcedHeight) { Playback.setQuality(o.height) }
        sheet.show()
    }

    private fun loopLabel(): String = when (Prefs.repeatMode) {
        1 -> "This video"
        2 -> "The queue"
        else -> "Off"
    }

    private fun showLoopMenu() {
        val labels = listOf("Loop off", "Loop this video", "Loop the queue")
        val sheet = BottomSheet(context, "Loop")
        for ((i, l) in labels.withIndex()) {
            sheet.choice(l, Prefs.repeatMode == i) {
                Playback.setRepeat(i)
                applyState()
            }
        }
        sheet.show()
    }

    private fun aspectLabel(): String = when (resizeMode) {
        1 -> "Stretch"
        2 -> "Zoom"
        else -> "Fit"
    }

    /** The player's settings, laid out like the gear menu of the YouTube app. */
    private fun showMoreMenu() {
        BottomSheet(context)
            .add(R.drawable.ic_tune, "Quality", qualityLabel()) { showQualityMenu() }
            .add(R.drawable.ic_speed, "Playback speed", formatSpeed(Playback.speed)) { showSpeedMenu() }
            .add(R.drawable.ic_repeat, "Loop", loopLabel()) { showLoopMenu() }
            .add(R.drawable.ic_ambient, "Ambient mode", if (ambientOn) "On" else "Off") { host?.onToggleAmbient() }
            .add(R.drawable.ic_fullscreen, "Aspect ratio", aspectLabel()) { showAspectMenu() }
            .add(R.drawable.ic_timer, "Sleep timer") { showSleepMenu() }
            .add(R.drawable.ic_lock, "Lock controls") { setLocked(true) }
            .add(R.drawable.ic_browser, "Open in browser") { host?.onOpenInBrowser() }
            .show()
    }

    private fun showSleepMenu() {
        val sheet = BottomSheet(context, "Sleep timer")
        sheet.add(R.drawable.ic_timer, "Off") { Playback.setSleepTimer(0) }
        for (m in intArrayOf(15, 30, 45, 60)) {
            sheet.add(R.drawable.ic_timer, if (m == 60) "1 hour" else "$m minutes") { Playback.setSleepTimer(m) }
        }
        sheet.add(R.drawable.ic_timer, "End of this video") { Playback.setSleepAtVideoEnd() }
        sheet.show()
    }

    private fun showAspectMenu() {
        val labels = listOf("Fit", "Stretch", "Zoom (crop)")
        val sheet = BottomSheet(context, "Aspect ratio")
        for ((i, l) in labels.withIndex()) sheet.choice(l, resizeMode == i) { resizeMode = i }
        sheet.show()
    }
}
