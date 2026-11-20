package com.novaplay.app.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.novaplay.app.Nav
import com.novaplay.app.R
import com.novaplay.app.data.Library
import com.novaplay.app.data.Source
import com.novaplay.app.data.Video
import com.novaplay.app.web.SiteClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The newest videos of the followed channels, mixed together. It reads each channel page in turn
 * (one hidden page load per channel), so the list fills up gradually.
 */
class SubsScreen(activity: MainActivity) : Screen(activity) {
    private val ctx = activity
    private val root = LinearLayout(ctx)
    private val strip = LinearLayout(ctx)
    private val stripScroll = HorizontalScrollView(ctx)
    private val list = RecyclerView(ctx)
    private val swipe = SwipeRefreshLayout(ctx)
    private val state = StateBox(ctx)
    private val progress = ProgressBar(ctx)
    private val items = ArrayList<Video>()
    private var job: Job? = null
    private var loadedAt = 0L
    private var signature = ""

    private val actions = VideoActions(
        onOpen = { v -> activity.openVideo(v) },
        onMore = { v -> VideoMenu.show(activity, v, emptyList()) { url, title -> activity.openListing(url, title) } },
        onChannel = { v -> activity.openListing(v.uploaderUrl, v.uploader) }
    )

    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(ctx))
        val bar = TopBar(ctx)
        bar.title.text = "Subscriptions"
        bar.action(R.drawable.ic_search, "Search") { activity.openSearch() }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        stripScroll.isHorizontalScrollBarEnabled = false
        strip.orientation = LinearLayout.HORIZONTAL
        strip.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        stripScroll.addView(strip, Ui.lp(Ui.WRAP, Ui.WRAP))
        root.addView(stripScroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        val body = FrameLayout(ctx)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = CardAdapter({ items }, actions)
        list.itemAnimator = null
        swipe.addView(list, ViewGroup.LayoutParams(Ui.MATCH, Ui.MATCH))
        swipe.setColorSchemeColors(Ui.accent(ctx))
        swipe.setProgressBackgroundColorSchemeColor(Ui.surfaceVariant(ctx))
        swipe.setOnRefreshListener { loadFeed() }
        body.addView(swipe, Ui.frame(Ui.MATCH, Ui.MATCH))
        val pp = Ui.frame(Ui.dp(ctx, 40), Ui.dp(ctx, 40))
        pp.gravity = Gravity.CENTER
        progress.visibility = View.GONE
        body.addView(progress, pp)
        body.addView(state, Ui.frame(Ui.MATCH, Ui.MATCH))
        root.addView(body, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
    }

    override fun onShow() {
        val subs = Library.subList()
        val sig = subs.joinToString("|") { it.url }
        buildStrip(subs)
        val stale = System.currentTimeMillis() - loadedAt > 15 * 60 * 1000L
        if (sig != signature || stale) {
            signature = sig
            loadFeed()
        }
    }

    private fun buildStrip(subs: List<Source>) {
        strip.removeAllViews()
        stripScroll.visibility = if (subs.isEmpty()) View.GONE else View.VISIBLE
        for (s in subs) {
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0)
            val name = s.name.ifEmpty { "?" }
            val avatar = Ui.textView(ctx, 20f, Color.WHITE, true, 1)
            avatar.gravity = Gravity.CENTER
            avatar.text = name.substring(0, name.offsetByCodePoints(0, 1)).uppercase()
            avatar.background = Ui.rounded(avatarColor(name), Ui.dpf(ctx, 28f))
            box.addView(avatar, Ui.lp(Ui.dp(ctx, 56), Ui.dp(ctx, 56)))
            val label = Ui.textView(ctx, 11f, Ui.textDim(ctx), false, 1)
            label.text = name
            label.gravity = Gravity.CENTER
            val lp = Ui.lp(Ui.dp(ctx, 68), Ui.WRAP)
            lp.topMargin = Ui.dp(ctx, 4)
            box.addView(label, lp)
            box.setOnClickListener { activity.openListing(s.url, s.name) }
            box.setOnLongClickListener {
                Dialogs.confirm(ctx, "Unsubscribe from ${s.name}?", "It will no longer appear here.", "Unsubscribe") {
                    Library.toggleSub(s)
                    onShow()
                }
                true
            }
            strip.addView(box, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
    }

    private fun loadFeed() {
        job?.cancel()
        val subs = Library.subList().take(8)
        if (subs.isEmpty()) {
            items.clear()
            list.adapter?.notifyDataSetChanged()
            swipe.isRefreshing = false
            progress.visibility = View.GONE
            state.show(
                "Subscribe to channels from a video or a channel page and their newest videos will show up here.",
                "Browse Home",
                { activity.select(Nav.TAB_HOME) }
            )
            return
        }
        state.hide()
        progress.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        job = activity.lifecycleScope.launch {
            val lists = ArrayList<List<Video>>()
            try {
                for (s in subs) {
                    try {
                        val page = SiteClient.listing(s.url)
                        lists.add(page.videos.take(8))
                        merge(lists)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // one channel failing must not hide the others
                    }
                    progress.visibility = View.GONE
                }
                loadedAt = System.currentTimeMillis()
                if (items.isEmpty()) {
                    state.show("Could not load your channels right now.", "Try again", { loadFeed() })
                }
            } finally {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
            }
        }
    }

    /** Takes one video from each channel in turn, so no single channel fills the top of the list. */
    private fun merge(lists: List<List<Video>>) {
        items.clear()
        val seen = HashSet<String>()
        var round = 0
        while (true) {
            var any = false
            for (l in lists) {
                if (round < l.size) {
                    any = true
                    if (seen.add(l[round].id)) items.add(l[round])
                }
            }
            if (!any) break
            round++
        }
        list.adapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        job?.cancel()
    }
}
