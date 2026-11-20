package com.novaplay.app.ui

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.novaplay.app.R
import com.novaplay.app.data.Library
import com.novaplay.app.data.Source
import com.novaplay.app.data.Video
import com.novaplay.app.web.Site
import com.novaplay.app.web.SiteClient
import com.novaplay.app.web.SiteException
import com.novaplay.app.web.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A scrolling feed of video cards loaded from the site: the Home tab (with chips) or any list page
 * (search results, a channel, a category) pushed on top of a tab.
 */
class FeedScreen private constructor(
    activity: MainActivity,
    private val isHome: Boolean,
    private var screenTitle: String,
    private val startUrl: String
) : Screen(activity) {

    private enum class Footer { NONE, LOADING, ERROR }

    private val ctx = activity
    private val items = ArrayList<Video>()
    private val seen = HashSet<String>()
    private var nextUrl: String? = null
    private var currentUrl = startUrl
    private var footer = Footer.NONE
    private var footerError = ""
    private var loading = false
    private var generation = 0
    private var job: Job? = null
    private var loadedOnce = false
    private var retryAfterBrowser = false
    private var lastBase = Site.base

    private val root = LinearLayout(ctx)
    private val chipsRow = LinearLayout(ctx)
    private val chipsScroll = HorizontalScrollView(ctx)
    private val list = RecyclerView(ctx)
    private val swipe = SwipeRefreshLayout(ctx)
    private val spinner = ProgressBar(ctx)
    private val state = StateBox(ctx)
    private lateinit var topBar: TopBar
    private var subButton: ImageButton? = null
    private val adapter = FeedAdapter()

    private val actions = VideoActions(
        onOpen = { v -> activity.openVideo(v) },
        onMore = { v -> VideoMenu.show(activity, v, emptyList()) { url, title -> activity.openListing(url, title) } },
        onChannel = { v -> activity.openListing(v.uploaderUrl, v.uploader) }
    )

    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(ctx))
        if (isHome) {
            root.addView(homeBar(), Ui.lp(Ui.MATCH, Ui.WRAP))
            chipsScroll.isHorizontalScrollBarEnabled = false
            chipsRow.orientation = LinearLayout.HORIZONTAL
            chipsRow.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
            chipsScroll.addView(chipsRow, Ui.lp(Ui.WRAP, Ui.WRAP))
            root.addView(chipsScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        } else {
            root.addView(listingBar(), Ui.lp(Ui.MATCH, Ui.WRAP))
        }

        val body = FrameLayout(ctx)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (nextUrl != null && lm.findLastVisibleItemPosition() >= items.size - 4) {
                    // the adapter must not change inside a scroll callback, so continue on the next frame
                    recyclerView.post { if (!loading) loadMore() }
                }
            }
        })
        swipe.addView(list, ViewGroup.LayoutParams(Ui.MATCH, Ui.MATCH))
        swipe.setColorSchemeColors(Ui.accent(ctx))
        swipe.setProgressBackgroundColorSchemeColor(Ui.surfaceVariant(ctx))
        swipe.setOnRefreshListener { reload() }
        body.addView(swipe, Ui.frame(Ui.MATCH, Ui.MATCH))
        val sp = Ui.frame(Ui.dp(ctx, 40), Ui.dp(ctx, 40))
        sp.gravity = Gravity.CENTER
        spinner.visibility = View.GONE
        body.addView(spinner, sp)
        body.addView(state, Ui.frame(Ui.MATCH, Ui.MATCH))
        root.addView(body, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
        if (isHome) buildChips()
    }

    // ---------- bars ----------

    private fun homeBar(): View {
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 4), 0)
        bar.minimumHeight = Ui.dp(ctx, 56)
        val logo = FrameLayout(ctx)
        logo.background = Ui.rounded(Ui.accent(ctx), Ui.dpf(ctx, 8f))
        val play = ImageView(ctx)
        play.setImageResource(R.drawable.ic_play)
        play.setColorFilter(Color.WHITE)
        val pad = Ui.dp(ctx, 4)
        play.setPadding(pad, pad, pad, pad)
        logo.addView(play, Ui.frame(Ui.MATCH, Ui.MATCH))
        bar.addView(logo, Ui.lp(Ui.dp(ctx, 30), Ui.dp(ctx, 30)))
        val name = Ui.textView(ctx, 20f, Ui.text(ctx), true, 1)
        name.text = "NovaPlay"
        val np = Ui.lp(0, Ui.WRAP)
        np.weight = 1f
        np.leftMargin = Ui.dp(ctx, 10)
        bar.addView(name, np)
        for ((icon, desc, act) in listOf(
            Triple(R.drawable.ic_search, "Search", { activity.openSearch() }),
            Triple(R.drawable.ic_shield, "Ad blocker", { ctx.startActivity(Intent(ctx, AdBlockActivity::class.java)) }),
            Triple(R.drawable.ic_more, "More", { showHomeMenu() })
        )) {
            val b = Ui.iconButton(ctx, icon, desc, Ui.text(ctx))
            b.setOnClickListener { act() }
            bar.addView(b)
        }
        return bar
    }

    private fun showHomeMenu() {
        BottomSheet(ctx)
            .add(R.drawable.ic_refresh, "Refresh") { reload() }
            .add(R.drawable.ic_tune, "Settings") { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }
            .add(R.drawable.ic_shield, "Ad blocker") { ctx.startActivity(Intent(ctx, AdBlockActivity::class.java)) }
            .add(R.drawable.ic_browser, "Open site in browser") { BrowserActivity.open(ctx, Site.home()) }
            .show()
    }

    private fun listingBar(): View {
        val bar = TopBar(ctx)
        topBar = bar
        bar.title.text = screenTitle
        bar.showBack { activity.pop() }
        val isSearch = Site.pathOf(startUrl).startsWith("/search/")
        if (!isSearch) {
            subButton = bar.action(R.drawable.ic_subs, "Subscribe") { toggleSubscription() }
            updateSubButton()
        }
        bar.action(R.drawable.ic_more, "More") { showListingMenu(isSearch) }
        return bar
    }

    private fun sourceName(): String = screenTitle.ifEmpty { Site.hostOf(startUrl) }

    private fun toggleSubscription() {
        val now = Library.toggleSub(Source(sourceName(), startUrl))
        Dialogs.toast(ctx, if (now) "Subscribed to ${sourceName()}" else "Unsubscribed")
        updateSubButton()
    }

    private fun updateSubButton() {
        val b = subButton ?: return
        val on = Library.isSubscribed(startUrl)
        b.setColorFilter(if (on) Ui.accent(ctx) else Ui.text(ctx))
        b.contentDescription = if (on) "Unsubscribe" else "Subscribe"
    }

    private fun showListingMenu(isSearch: Boolean) {
        val sheet = BottomSheet(ctx, screenTitle)
        sheet.add(R.drawable.ic_refresh, "Refresh") { reload() }
        val chip = Library.chipList().any { it.url == startUrl }
        if (chip) {
            sheet.add(R.drawable.ic_close, "Remove from Home chips") {
                Library.removeChip(startUrl)
                Dialogs.toast(ctx, "Removed from Home")
            }
        } else {
            sheet.add(R.drawable.ic_add, "Add to Home chips") {
                Library.addChip(Source(sourceName(), startUrl))
                Dialogs.toast(ctx, "Added to Home")
            }
        }
        if (items.isNotEmpty()) {
            sheet.add(R.drawable.ic_play, "Play all") { PlayAll.start(ctx, items, false) }
            sheet.add(R.drawable.ic_shuffle, "Shuffle") { PlayAll.start(ctx, items, true) }
        }
        sheet.add(R.drawable.ic_browser, "Open in browser") { BrowserActivity.open(ctx, startUrl) }
        sheet.show()
    }

    // ---------- chips (Home) ----------

    private fun chipList(): List<Source> = Site.defaultChips() + Library.chipList()

    private fun buildChips() {
        chipsRow.removeAllViews()
        for (c in chipList()) {
            val selected = c.url == currentUrl
            val chip = Ui.chip(ctx, c.name, selected)
            chip.setOnClickListener { selectChip(c) }
            if (Library.chipList().any { it.url == c.url }) {
                chip.setOnLongClickListener {
                    Dialogs.confirm(ctx, "Remove \"${c.name}\"?", "It will no longer show on Home.", "Remove") {
                        Library.removeChip(c.url)
                        if (currentUrl == c.url) selectChip(Site.defaultChips()[0]) else buildChips()
                    }
                    true
                }
            }
            val lp = Ui.lp(Ui.WRAP, Ui.dp(ctx, 32))
            lp.rightMargin = Ui.dp(ctx, 8)
            chipsRow.addView(chip, lp)
        }
    }

    private fun clearItems() {
        items.clear()
        seen.clear()
        nextUrl = null
        adapter.notifyDataSetChanged()
    }

    private fun selectChip(c: Source) {
        currentUrl = c.url
        buildChips()
        clearItems()
        load(c.url, true)
    }

    // ---------- loading ----------

    private fun reload() = load(currentUrl, true)

    private fun loadMore() {
        val n = nextUrl ?: return
        load(n, false)
    }

    private fun load(url: String, reset: Boolean) {
        job?.cancel()
        val gen = ++generation
        loading = true
        retryAfterBrowser = false
        state.hide()
        if (reset) {
            footer = Footer.NONE
            if (items.isEmpty()) spinner.visibility = View.VISIBLE
        } else {
            footer = Footer.LOADING
            adapter.notifyDataSetChanged()
        }
        job = activity.lifecycleScope.launch {
            try {
                val page = SiteClient.listing(url)
                if (gen != generation) return@launch
                if (reset) {
                    items.clear()
                    seen.clear()
                }
                for (v in page.videos) if (seen.add(v.id)) items.add(v)
                nextUrl = page.nextUrl?.takeIf { it != url }
                if (!isHome && screenTitle.isEmpty() && page.title.isNotEmpty()) {
                    screenTitle = page.title.substringBefore(" - ").substringBefore(" | ").trim().take(60)
                    topBar.title.text = screenTitle
                }
                footer = Footer.NONE
                if (items.isEmpty()) state.show("No videos found here.", "Try again", { reload() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException) {
                if (gen == generation) fail(e.userMessage(), e.kind, reset)
            } catch (e: Exception) {
                if (gen == generation) fail(e.message ?: "Could not load the page", null, reset)
            } finally {
                if (gen == generation) {
                    loading = false
                    spinner.visibility = View.GONE
                    swipe.isRefreshing = false
                    adapter.notifyDataSetChanged()
                }
            }
        }
        loadedOnce = true
    }

    private fun fail(message: String, kind: SiteException.Kind?, wasReset: Boolean) {
        when {
            items.isEmpty() -> {
                footer = Footer.NONE
                retryAfterBrowser = kind == SiteException.Kind.CHALLENGE || kind == SiteException.Kind.NO_STREAM
                state.show(message, "Try again", { reload() }, "Open in browser", { BrowserActivity.open(ctx, currentUrl) })
            }
            wasReset -> Dialogs.toast(ctx, message) // a failed pull-to-refresh keeps what is on screen
            else -> {
                footer = Footer.ERROR
                footerError = message
            }
        }
    }

    // ---------- lifecycle ----------

    override fun onShow() {
        if (isHome) buildChips()
        if (!loadedOnce) load(currentUrl, true)
    }

    override fun onResume() {
        if (isHome && Site.base != lastBase) {
            // the site address was changed in Settings
            lastBase = Site.base
            currentUrl = Site.home()
            buildChips()
            clearItems()
            load(currentUrl, true)
            return
        }
        if (retryAfterBrowser) reload()
        if (!isHome) updateSubButton()
    }

    override fun onDestroy() {
        job?.cancel()
    }

    // ---------- list ----------

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    private inner class FeedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = items.size + if (footer == Footer.NONE) 0 else 1

        override fun getItemViewType(position: Int): Int = if (position < items.size) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) Holder(VideoCardView(parent.context)) else Holder(footerView())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position < items.size) {
                (holder.itemView as VideoCardView).bind(items[position], actions)
            } else {
                bindFooter(holder.itemView as LinearLayout)
            }
        }
    }

    private fun footerView(): LinearLayout {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.layoutParams = ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP)
        box.setPadding(0, Ui.dp(ctx, 16), 0, Ui.dp(ctx, 24))
        box.addView(ProgressBar(ctx), Ui.lp(Ui.dp(ctx, 32), Ui.dp(ctx, 32)))
        val msg = Ui.textView(ctx, 14f, Ui.textDim(ctx))
        msg.gravity = Gravity.CENTER
        box.addView(msg, Ui.lp(Ui.MATCH, Ui.WRAP))
        val retry = Ui.chip(ctx, "Try again", true)
        retry.setOnClickListener { loadMore() }
        val rp = Ui.lp(Ui.WRAP, Ui.dp(ctx, 36))
        rp.topMargin = Ui.dp(ctx, 8)
        box.addView(retry, rp)
        return box
    }

    private fun bindFooter(box: LinearLayout) {
        val busy = footer == Footer.LOADING
        box.getChildAt(0).visibility = if (busy) View.VISIBLE else View.GONE
        val msg = box.getChildAt(1) as TextView
        msg.text = footerError
        msg.visibility = if (busy) View.GONE else View.VISIBLE
        box.getChildAt(2).visibility = if (busy) View.GONE else View.VISIBLE
    }

    companion object {
        fun home(activity: MainActivity) = FeedScreen(activity, true, "", Site.home())

        fun listing(activity: MainActivity, url: String, title: String) = FeedScreen(activity, false, title, url)
    }
}
