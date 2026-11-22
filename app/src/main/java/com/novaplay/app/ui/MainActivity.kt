package com.novaplay.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.novaplay.app.Nav
import com.novaplay.app.R
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.Video
import com.novaplay.app.player.PlayerActivity
import com.novaplay.app.web.Site

/**
 * The shell of the app: five tabs, a stack of pushed screens on top of each tab, and the mini player.
 * Everything inside is built in code (see [Screen]).
 */
class MainActivity : BaseActivity() {
    private class Tab(val id: String, val icon: Int, val iconOn: Int, val label: String) {
        lateinit var image: ImageView
        lateinit var text: TextView
        lateinit var box: LinearLayout
    }

    private val tabs = listOf(
        Tab(Nav.TAB_HOME, R.drawable.ic_nav_home, R.drawable.ic_nav_home_on, "Home"),
        Tab(Nav.TAB_SUBS, R.drawable.ic_nav_subs, R.drawable.ic_nav_subs_on, "Subscriptions"),
        Tab(Nav.TAB_QUEUE, R.drawable.ic_nav_queue, R.drawable.ic_nav_queue_on, "Queue"),
        Tab(Nav.TAB_DOWNLOADS, R.drawable.ic_nav_dl, R.drawable.ic_nav_dl_on, "Downloads"),
        Tab(Nav.TAB_LIBRARY, R.drawable.ic_nav_you, R.drawable.ic_nav_you_on, "You")
    )

    private lateinit var content: FrameLayout
    private lateinit var mini: MiniPlayerView
    private val roots = HashMap<String, Screen>()
    private val stacks = HashMap<String, ArrayList<Screen>>()
    private var tab = Prefs.startTab.takeIf { id -> tabs.any { it.id == id } } ?: Nav.TAB_HOME
    private var shown: Screen? = null
    private var started = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("nova_tab", tab)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("nova_tab")?.let { saved -> if (tabs.any { it.id == saved }) tab = saved }
        build()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val top = topScreen()
                when {
                    top.onBack() -> Unit
                    pop() -> Unit
                    tab != Nav.TAB_HOME -> select(Nav.TAB_HOME)
                    else -> finish()
                }
            }
        })
        if (Prefs.ageConfirmed) begin(savedInstanceState == null) else askAge(savedInstanceState == null)
    }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(this))
        content = FrameLayout(this)
        val cp = Ui.lp(Ui.MATCH, 0)
        cp.weight = 1f
        root.addView(content, cp)
        mini = MiniPlayerView(this) { PlayerActivity.show(this) }
        root.addView(mini, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(Ui.hr(this), Ui.lp(Ui.MATCH, Ui.dp(this, 1)))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Ui.bg(this))
        for (t in tabs) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.background = Ui.ripple(this, null)
            box.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
            val image = ImageView(this)
            image.setImageResource(t.icon)
            box.addView(image, Ui.lp(Ui.dp(this, 24), Ui.dp(this, 24)))
            val text = Ui.textView(this, 10f, Ui.text(this), false, 1)
            text.text = t.label
            text.gravity = Gravity.CENTER
            box.addView(text, Ui.lp(Ui.WRAP, Ui.WRAP))
            box.setOnClickListener { onTab(t.id) }
            t.image = image
            t.text = text
            t.box = box
            val lp = Ui.lp(0, Ui.WRAP)
            lp.weight = 1f
            nav.addView(box, lp)
        }
        root.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP))
        setContentView(root)
    }

    private fun askAge(fresh: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Adults only")
            .setMessage("NovaPlay shows adult videos. Continue only if you are 18 or older and it is legal where you live.")
            .setCancelable(false)
            .setPositiveButton("I am 18 or older") { _, _ ->
                Prefs.ageConfirmed = true
                begin(fresh)
            }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    private fun begin(fresh: Boolean) {
        started = true
        render()
        if (fresh) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (started) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        mini.refresh()
        if (started) shown?.onResume()
    }

    override fun onDestroy() {
        for (s in roots.values) s.onDestroy()
        for (st in stacks.values) for (s in st) s.onDestroy()
        super.onDestroy()
    }

    // ---------- navigation ----------

    private fun rootFor(id: String): Screen = roots.getOrPut(id) {
        when (id) {
            Nav.TAB_SUBS -> SubsScreen(this)
            Nav.TAB_QUEUE -> QueueScreen(this)
            Nav.TAB_DOWNLOADS -> DownloadsScreen(this)
            Nav.TAB_LIBRARY -> LibraryScreen(this)
            else -> FeedScreen.home(this)
        }
    }

    private fun topScreen(): Screen {
        val st = stacks[tab]
        return if (st != null && st.isNotEmpty()) st[st.size - 1] else rootFor(tab)
    }

    private fun render() {
        val next = topScreen()
        // like the current YouTube app: every item has the same colour, the open one has a filled icon and a bolder label
        for (t in tabs) {
            val on = t.id == tab
            t.image.setImageResource(if (on) t.iconOn else t.icon)
            t.image.setColorFilter(Ui.text(this))
            t.text.setTextColor(Ui.text(this))
            if (on) Ui.medium(t.text) else t.text.typeface = android.graphics.Typeface.DEFAULT
        }
        if (next === shown) return
        shown?.onHide()
        content.removeAllViews()
        content.addView(next.view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        shown = next
        next.onShow()
    }

    private fun onTab(id: String) {
        if (id == tab) {
            // tapping the current tab again returns to its first page
            val st = stacks[id]
            if (st != null && st.isNotEmpty()) {
                val old = ArrayList(st)
                st.clear()
                render()
                for (s in old) s.onDestroy()
            }
            return
        }
        select(id)
    }

    fun select(id: String) {
        tab = id
        render()
    }

    fun push(screen: Screen) {
        stacks.getOrPut(tab) { ArrayList() }.add(screen)
        render()
    }

    /** Takes the place of the screen on top (used when search results replace the search box). */
    fun replaceTop(screen: Screen) {
        val st = stacks.getOrPut(tab) { ArrayList() }
        val old = if (st.isNotEmpty()) st.removeAt(st.size - 1) else null
        st.add(screen)
        render()
        old?.onDestroy()
    }

    fun pop(): Boolean {
        val st = stacks[tab]
        if (st == null || st.isEmpty()) return false
        val old = st.removeAt(st.size - 1)
        render()
        old.onDestroy()
        return true
    }

    fun openListing(url: String, title: String) {
        push(FeedScreen.listing(this, url, title))
    }

    fun openSearch() {
        push(SearchScreen(this))
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val link = Site.asUrl(q)
        if (link != null) {
            openUrl(link)
        } else {
            com.novaplay.app.data.Library.addSearch(q)
            openListing(Site.search(q), q)
        }
    }

    fun openVideo(video: Video) {
        PlayerActivity.play(this, video)
    }

    /** A pasted or shared link: a video plays, any other page of the site opens as a list. */
    fun openUrl(url: String) {
        val u = url.trim()
        when {
            Site.isVideoUrl(u) -> openVideo(Video(Video.idFor(Site.rebase(u)), Site.rebase(u), "Video"))
            Site.isSiteUrl(u) -> openListing(u, "")
            else -> Dialogs.toast(this, "That link is not from the supported site")
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val open = i.getStringExtra(Nav.EXTRA_OPEN)
        if (open != null) {
            i.removeExtra(Nav.EXTRA_OPEN)
            select(open)
        }
        val listing = i.getStringExtra(Nav.EXTRA_LISTING_URL)
        if (listing != null) {
            val title = i.getStringExtra(Nav.EXTRA_LISTING_TITLE).orEmpty()
            i.removeExtra(Nav.EXTRA_LISTING_URL)
            i.removeExtra(Nav.EXTRA_LISTING_TITLE)
            openListing(listing, title)
        }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                i.action = null
                val link = Regex("https?://\\S+").find(text)?.value
                if (link != null) openUrl(link) else if (text.isNotBlank()) search(text)
            }
            Intent.ACTION_VIEW -> {
                val data = i.dataString
                i.action = null
                if (data != null) openUrl(data)
            }
        }
    }
}
