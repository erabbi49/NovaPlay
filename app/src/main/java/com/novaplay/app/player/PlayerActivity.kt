package com.novaplay.app.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.OrientationEventListener
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novaplay.app.Nav
import com.novaplay.app.R
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.Video
import com.novaplay.app.ui.BaseActivity
import com.novaplay.app.ui.BrowserActivity
import com.novaplay.app.ui.Dialogs
import com.novaplay.app.ui.MainActivity
import com.novaplay.app.ui.SystemBars
import com.novaplay.app.ui.Ui
import com.novaplay.app.ui.VideoActions
import com.novaplay.app.ui.VideoMenu
import com.novaplay.app.web.Site

/**
 * The watch screen: a 16:9 player on top, details / up next / related below it. Landscape means
 * fullscreen. Also hosts picture-in-picture and the ambient glow.
 */
class PlayerActivity : BaseActivity(), NovaPlayerView.Host {

    /** 16:9 in portrait, fills its parent in fullscreen / picture-in-picture. */
    private class PlayerBox(ctx: Context) : FrameLayout(ctx) {
        var tall = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (tall) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            } else {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
            }
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var glow: AmbientGlowView
    private lateinit var column: LinearLayout
    private lateinit var box: PlayerBox
    private lateinit var playerView: NovaPlayerView
    private lateinit var list: RecyclerView
    private lateinit var header: DetailsHeader
    private lateinit var adapter: DetailsAdapter
    private lateinit var ambient: AmbientController
    private var pipReceiver: BroadcastReceiver? = null
    private var nightMode = 0
    private var started = false
    private val ui = Handler(Looper.getMainLooper())
    private var dimmed = false
    private val undim = Runnable { setDim(false) }

    private val upright by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val portrait = orientation <= 35 || orientation >= 325 || orientation in 145..215
                if (portrait) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    disable()
                }
            }
        }
    }

    private val playbackListener = object : Playback.Listener {
        override fun onVideoChanged() {
            // the old picture must not light the new video; the glow waits for its first frame
            ambient.clear()
            updateBars()
            rebuild()
        }

        override fun onStatusChanged() = rebuild()
        override fun onQueueChanged() = rebuild()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateKeepOn()
            updatePip()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()
    }

    private val libraryListener = Runnable {
        if (started) header.bind(Playback.current, Playback.info, Playback.status)
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Playback.current == null && Playback.queue().isEmpty()) {
            finish()
            return
        }
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        requestedOrientation = if (Prefs.autoLandscapeFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        build()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscape() && !inPip()) leaveFullscreen() else finish()
            }
        })
        applyLayout()
    }

    private fun build() {
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.bg(this))

        // the ambient light sits behind everything and fills the whole screen
        glow = AmbientGlowView(this)
        root.addView(glow, Ui.frame(Ui.MATCH, Ui.MATCH))

        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        box = PlayerBox(this)
        box.setBackgroundColor(Color.BLACK)
        playerView = NovaPlayerView(this)
        playerView.host = this
        box.addView(playerView, Ui.frame(Ui.MATCH, Ui.MATCH))
        column.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))

        header = DetailsHeader(this) { url, title -> openListing(url, title) }
        val actions = VideoActions(
            onOpen = { v -> Playback.playNow(v) },
            onMore = { v -> showMenu(v) },
            onChannel = { v -> openListing(v.uploaderUrl, v.uploader) }
        )
        adapter = DetailsAdapter(header, actions)
        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) dimWhileScrolling()
            }
        })
        val lp = Ui.lp(Ui.MATCH, 0)
        lp.weight = 1f
        column.addView(list, lp)
        root.addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        setContentView(root)

        ambient = AmbientController(playerView.surface, glow)
        glow.playerTop = { box.top }
        glow.playerBottom = { box.bottom }
        ambient.onFirstFrame = { updateBars() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        Playback.setVideoActive(true)
        Playback.addListener(playbackListener)
        Playback.player.addListener(playerListener)
        Library.addListener(libraryListener)
        rebuild()
        updateKeepOn()
        applyAmbient()
        updatePip()
    }

    override fun onStop() {
        started = false
        ambient.stop()
        ui.removeCallbacks(undim)
        Playback.removeListener(playbackListener)
        Playback.player.removeListener(playerListener)
        Library.removeListener(libraryListener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isChangingConfigurations) {
            when {
                inPip() -> Playback.player.pause() // the picture-in-picture window was closed
                !Prefs.backgroundPlay -> Playback.player.pause()
                else -> Playback.setVideoActive(false) // keep the sound, drop the picture
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterPip()
        if (this::playerView.isInitialized) playerView.host = null
        upright.disable()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        list.scrollToPosition(0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night != nightMode) {
            recreate()
            return
        }
        applyLayout()
    }

    // ---------- list ----------

    private fun rebuild() {
        val rows = ArrayList<DetailsAdapter.Row>()
        rows.add(DetailsAdapter.Row.Header)
        val up = Playback.upcoming()
        val queued = HashSet<String>()
        for (v in Playback.queue()) queued.add(v.id)
        if (up.isNotEmpty()) {
            rows.add(DetailsAdapter.Row.Section("Up next", true))
            for (v in up.take(50)) rows.add(DetailsAdapter.Row.Item(v))
        }
        val related = Playback.info?.related.orEmpty().filter { it.id !in queued }
        if (related.isNotEmpty()) {
            rows.add(DetailsAdapter.Row.Section("Related videos", up.isEmpty()))
            for (v in related.take(60)) rows.add(DetailsAdapter.Row.Item(v))
        }
        header.bind(Playback.current, Playback.info, Playback.status)
        adapter.rows = rows
    }

    private fun showMenu(video: Video) {
        val extras = ArrayList<VideoMenu.Extra>()
        val at = Playback.queue().indexOfFirst { it.id == video.id }
        if (at >= 0 && at != Playback.index) {
            extras.add(VideoMenu.Extra(R.drawable.ic_close, "Remove from queue") { Playback.removeAt(at) })
        }
        VideoMenu.show(this, video, extras) { url, title -> openListing(url, title) }
    }

    /** Channels and tags open in the main screen; the video keeps playing in its mini player. */
    private fun openListing(url: String, title: String) {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra(Nav.EXTRA_LISTING_URL, url)
        i.putExtra(Nav.EXTRA_LISTING_TITLE, title)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
        finish()
    }

    // ---------- layout / fullscreen ----------

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun inPip(): Boolean = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun applyLayout() {
        val pip = inPip()
        val full = !pip && isLandscape()
        val tall = full || pip
        box.tall = tall
        box.layoutParams = Ui.lp(Ui.MATCH, if (tall) Ui.MATCH else Ui.WRAP)
        list.visibility = if (tall) View.GONE else View.VISIBLE
        root.setBackgroundColor(if (full) Color.BLACK else Ui.bg(this))
        SystemBars.setHidden(window, full)
        SystemBars.useCutout(window, full)
        playerView.setFullscreen(full)
        applyAmbient()
    }

    /** True when the ambient light should be drawn right now (also behind a fullscreen picture, for its bars). */
    private fun glowShown(): Boolean = Prefs.ambient && !inPip() && (!isLandscape() || Prefs.ambientBars)

    private fun applyAmbient() {
        val on = Prefs.ambient
        playerView.setAmbientOn(on)
        val show = glowShown()
        glow.mode = if (isLandscape()) AmbientGlowView.Mode.FULL else AmbientGlowView.Mode.PAGE
        glow.visibility = if (show) View.VISIBLE else View.GONE
        setDim(false)
        if (show && started) {
            ambient.start()
        } else {
            ambient.stop()
            if (!on) ambient.clear()
        }
        updateBars()
    }

    /** The black bars around the video turn into ambient light once there is a picture to light them with. */
    private fun updateBars() {
        if (!this::ambient.isInitialized) return
        val lit = glowShown() && Prefs.ambientBars && ambient.hasFrame
        val bars = if (lit) Color.TRANSPARENT else Color.BLACK
        box.setBackgroundColor(bars)
        playerView.setBarsColor(bars)
    }

    /** Dims the light while the page is scrolled, and brings it back [Prefs.ambientDimMs] after the scrolling stops. */
    private fun dimWhileScrolling() {
        if (!Prefs.ambientDimScroll || glow.visibility != View.VISIBLE) return
        setDim(true)
        ui.removeCallbacks(undim)
        ui.postDelayed(undim, Prefs.ambientDimMs.toLong().coerceIn(200L, 6000L))
    }

    private fun setDim(on: Boolean) {
        if (!on) ui.removeCallbacks(undim)
        val target = if (on) DIM_ALPHA else 1f
        if (dimmed == on && glow.alpha == target) return
        dimmed = on
        glow.animate().cancel()
        glow.animate().alpha(target).setDuration(if (on) 220L else 700L).start()
    }

    private fun leaveFullscreen() {
        if (!Prefs.autoLandscapeFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
        // Go back to portrait now, and hand rotation back to the sensor once the phone is held upright again.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (upright.canDetectOrientation()) upright.enable() else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun updateKeepOn() {
        if (Playback.player.isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------- NovaPlayerView.Host ----------

    override fun onMinimize() {
        if (isLandscape() && !inPip()) leaveFullscreen() else finish()
    }

    override fun onToggleFullscreen() {
        if (isLandscape()) {
            leaveFullscreen()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onEnterPip() {
        if (!enterPip()) Dialogs.toast(this, "Picture-in-picture is not available")
    }

    override fun onToggleAmbient() {
        Prefs.ambient = !Prefs.ambient
        if (Prefs.ambient) ambient.clear()
        applyAmbient()
        Dialogs.toast(this, if (Prefs.ambient) "Ambient mode on" else "Ambient mode off")
    }

    override fun onOpenInBrowser() {
        BrowserActivity.open(this, Playback.current?.url ?: Site.home())
    }

    override fun hostWindow(): Window = window

    // ---------- picture-in-picture ----------

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip(): Boolean {
        if (!pipSupported()) return false
        return try {
            enterPictureInPictureMode(pipParams())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
        val size = Playback.player.videoSize
        var ratio = if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height else 16f / 9f
        ratio = ratio.coerceIn(0.42f, 2.38f) // the system refuses anything wider than 2.39:1 or taller than 1:2.39
        b.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        val r = Rect()
        if (box.getGlobalVisibleRect(r) && !r.isEmpty) b.setSourceRectHint(r)
        b.setActions(listOf(pipAction(ACTION_PIP_TOGGLE, if (Playback.player.playWhenReady) R.drawable.ic_pip_pause else R.drawable.ic_pip_play, "Play / pause", 1),
            pipAction(ACTION_PIP_NEXT, R.drawable.ic_pip_next, "Next", 2)))
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(Prefs.pipOnLeave && Playback.player.isPlaying)
        }
        return b.build()
    }

    private fun pipAction(action: String, icon: Int, title: String, code: Int): RemoteAction {
        val intent = Intent(action).setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi)
    }

    /** Keeps the picture-in-picture parameters (and auto-enter on Android 12+) up to date. */
    private fun updatePip() {
        if (!pipSupported() || !started) return
        try {
            setPictureInPictureParams(pipParams())
        } catch (e: Exception) {
            // not allowed right now; the next change will try again
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && Prefs.pipOnLeave && Playback.player.isPlaying && !isFinishing) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.setPip(isInPictureInPictureMode)
        if (isInPictureInPictureMode) registerPip() else unregisterPip()
        applyLayout()
    }

    private fun registerPip() {
        if (pipReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PIP_TOGGLE -> {
                        Playback.togglePlay()
                        updatePip()
                    }
                    ACTION_PIP_NEXT -> Playback.next()
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction(ACTION_PIP_TOGGLE)
        filter.addAction(ACTION_PIP_NEXT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        pipReceiver = receiver
    }

    private fun unregisterPip() {
        val r = pipReceiver ?: return
        pipReceiver = null
        try {
            unregisterReceiver(r)
        } catch (e: Exception) {
            // already gone
        }
    }

    companion object {
        private const val ACTION_PIP_TOGGLE = "com.novaplay.app.action.PIP_TOGGLE"
        private const val ACTION_PIP_NEXT = "com.novaplay.app.action.PIP_NEXT"
        private const val DIM_ALPHA = 0.3f

        /** Starts [video] and opens the watch screen. */
        fun play(ctx: Context, video: Video) {
            Playback.playNow(video)
            show(ctx)
        }

        /** Replaces the queue with [videos] and starts at [start]. */
        fun playAll(ctx: Context, videos: List<Video>, start: Int = 0) {
            Playback.playAll(videos, start)
            show(ctx)
        }

        /** Opens the watch screen for whatever is playing now. */
        fun show(ctx: Context) {
            val i = Intent(ctx, PlayerActivity::class.java)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
