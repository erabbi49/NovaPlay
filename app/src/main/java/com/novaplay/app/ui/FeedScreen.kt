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
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.SitePage
import com.novaplay.app.data.Source
import com.novaplay.app.data.Video
import com.novaplay.app.web.Paging
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
    private var currentUrl = startUrl // first page of the list on screen (the chip's address on Home)
    private var pageNo = 1 // the page that was loaded last
    private var pageMax = 0 // how many pages the site says there are, 0 = unknown
    private var template: String? = null // address pattern with {n} for the page number
    private var pageLinks: Map<Int, String> = emptyMap() // the site's own numbered links
    private var nextLink: String? = null // the site's own "next page" link
    private var more = false // another page is worth asking for
    private var lastPaged = Prefs.pagedFeed
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
    private val skeleton = SkeletonView(ctx, 4, false)
    private var lastCompact = Prefs.compactFeed
    private val state = StateBox(ctx)
    private var barTitle: TextView? = null // the title of the bar (or the search field) so it can be filled in later
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
                if (dy <= 0 || loading || Prefs.pagedFeed) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (more && lm.findLastVisibleItemPosition() >= items.size - 4) {
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
        skeleton.visibility = View.GONE
        body.addView(skeleton, Ui.frame(Ui.MATCH, Ui.MATCH))
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
        // a wide rounded badge with a play triangle, then the name set tight and bold, like the YouTube app bar
        val logo = FrameLayout(ctx)
        logo.background = Ui.rounded(Ui.accent(ctx), Ui.dpf(ctx, 6f))
        val play = ImageView(ctx)
        play.setImageResource(R.drawable.ic_play)
        play.setColorFilter(Color.WHITE)
        val pad = Ui.dp(ctx, 3)
        play.setPadding(pad, pad, pad, pad)
        logo.addView(play, Ui.frame(Ui.MATCH, Ui.MATCH))
        bar.addView(logo, Ui.lp(Ui.dp(ctx, 29), Ui.dp(ctx, 21)))
        val name = Ui.textView(ctx, 20f, Ui.text(ctx), true, 1)
        name.text = "NovaPlay"
        name.letterSpacing = -0.04f
        val np = Ui.lp(0, Ui.WRAP)
        np.weight = 1f
        np.leftMargin = Ui.dp(ctx, 6)
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

    /** Search results get the search field with the query in it, as in the YouTube app; tapping it edits the query. */
    private fun searchBar(): View {
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8))
        val back = Ui.iconButton(ctx, R.drawable.ic_back, "Back", Ui.text(ctx))
        back.setOnClickListener { activity.pop() }
        bar.addView(back)
        val field = LinearLayout(ctx)
        field.orientation = LinearLayout.HORIZONTAL
        field.gravity = Gravity.CENTER_VERTICAL
        field.background = Ui.rounded(Ui.surfaceVariant(ctx), Ui.dpf(ctx, 20f))
        field.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), 0)
        val query = Ui.textView(ctx, 16f, Ui.text(ctx), false, 1)
        query.text = screenTitle
        barTitle = query
        field.addView(query, Ui.lp(Ui.MATCH, Ui.dp(ctx, 40)).also { it.gravity = Gravity.CENTER_VERTICAL })
        query.gravity = Gravity.CENTER_VERTICAL
        field.setOnClickListener { activity.replaceTop(SearchScreen(activity, screenTitle)) }
        bar.addView(field, Ui.lp(0, Ui.WRAP).also { it.weight = 1f })
        val more = Ui.iconButton(ctx, R.drawable.ic_more, "More", Ui.text(ctx))
        more.setOnClickListener { showListingMenu(true) }
        bar.addView(more)
        return bar
    }

    private fun listingBar(): View {
        val isSearch = Site.pathOf(startUrl).startsWith("/search/")
        if (isSearch) return searchBar()
        val bar = TopBar(ctx)
        barTitle = bar.title
        bar.title.text = screenTitle
        bar.showBack { activity.pop() }
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
        pageNo = 1
        pageMax = 0
        template = null
        pageLinks = emptyMap()
        nextLink = null
        more = false
        adapter.notifyDataSetChanged()
    }

    private fun selectChip(c: Source) {
        currentUrl = c.url
        buildChips()
        clearItems()
        loadPage(1, false)
    }

    // ---------- loading ----------

    /** Where page [n] of the list on screen lives. */
    private fun urlOfPage(n: Int): String {
        if (n == pageNo + 1) nextLink?.let { return it }
        pageLinks[n]?.let { return it }
        if (n <= 1) return currentUrl
        return Paging.fill(template ?: Paging.templateFor(currentUrl), n)
    }

    private fun reload() = loadPage(if (Prefs.pagedFeed) pageNo else 1, false)

    private fun loadMore() {
        if (!more || loading) return
        loadPage(pageNo + 1, true)
    }

    /** Opens page [n]. With [append] it is added below what is on screen (endless scrolling), otherwise it replaces it. */
    private fun loadPage(n: Int, append: Boolean) {
        job?.cancel()
        val gen = ++generation
        val url = urlOfPage(n)
        loading = true
        retryAfterBrowser = false
        state.hide()
        if (append) {
            footer = Footer.LOADING
        } else {
            footer = Footer.NONE
            if (items.isEmpty()) skeleton.visibility = View.VISIBLE else swipe.isRefreshing = true
        }
        adapter.notifyDataSetChanged()
        job = activity.lifecycleScope.launch {
            try {
                val page = SiteClient.listing(url, n > 1)
                if (gen != generation) return@launch
                val fresh = if (append) page.videos.filter { it.id !in seen } else page.videos
                // "hide watched videos": a page that would be left empty by it is shown as it is
                val shown = if (Prefs.hideWatched) fresh.filter { !Library.isWatched(it) }.ifEmpty { fresh } else fresh
                if (fresh.isEmpty()) {
                    // nothing new here: the list ends before this page
                    more = false
                    if (n > 1 && (pageMax == 0 || pageMax >= n)) pageMax = n - 1
                    if (items.isEmpty()) {
                        state.show("No videos found here.", "Try again", { reload() })
                    } else if (!append) {
                        Dialogs.toast(ctx, "Page $n has no videos")
                    }
                } else {
                    if (!append) {
                        items.clear()
                        seen.clear()
                    }
                    for (v in shown) if (seen.add(v.id)) items.add(v)
                    pageNo = n
                    absorb(page, url)
                    if (!append) list.scrollToPosition(0)
                }
                if (!isHome && screenTitle.isEmpty() && page.title.isNotEmpty()) {
                    screenTitle = page.title.substringBefore(" - ").substringBefore(" | ").trim().take(60)
                    barTitle?.text = screenTitle
                }
                footer = Footer.NONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException) {
                if (gen == generation) fail(e.userMessage(), e.kind, append, n)
            } catch (e: Exception) {
                if (gen == generation) fail(e.message ?: "Could not load the page", null, append, n)
            } finally {
                if (gen == generation) {
                    loading = false
                    skeleton.visibility = View.GONE
                    swipe.isRefreshing = false
                    adapter.notifyDataSetChanged()
                }
            }
        }
        loadedOnce = true
    }

    /** Takes what [page] says about the other pages of the list. */
    private fun absorb(page: SitePage, url: String) {
        page.template?.let { template = it }
        if (template == null) {
            for ((k, v) in page.pageLinks) {
                val t = Paging.inferTemplate(k, v)
                if (t != null) {
                    template = t
                    break
                }
            }
        }
        pageLinks = page.pageLinks
        nextLink = page.nextUrl?.takeIf { it != url }
        val said = maxOf(page.pages, page.pageLinks.keys.maxOrNull() ?: 0)
        pageMax = if (said >= pageNo) said else if (pageMax >= pageNo) pageMax else 0
        // a "next" link past the last page the pager lists: the pager only shows some of the pages
        if (nextLink != null && pageMax <= pageNo) pageMax = 0
        more = when {
            nextLink != null -> true
            pageMax > 0 -> pageNo < pageMax
            else -> page.videos.size >= FULL_PAGE
        }
    }

    private fun fail(message: String, kind: SiteException.Kind?, append: Boolean, n: Int) {
        when {
            items.isEmpty() -> {
                footer = Footer.NONE
                retryAfterBrowser = kind == SiteException.Kind.CHALLENGE || kind == SiteException.Kind.NO_STREAM
                state.show(message, "Try again", { reload() }, "Open in browser", { BrowserActivity.open(ctx, currentUrl) })
            }
            kind == SiteException.Kind.EMPTY && n > 1 -> {
                // there is no such page: the list ends before it
                more = false
                if (pageMax == 0 || pageMax >= n) pageMax = n - 1
                footer = Footer.NONE
                if (!append) Dialogs.toast(ctx, "Page $n has no videos")
            }
            !append -> Dialogs.toast(ctx, message) // a failed page change or refresh keeps what is on screen
            else -> {
                footer = Footer.ERROR
                footerError = message
            }
        }
    }

    // ---------- lifecycle ----------

    override fun onShow() {
        if (isHome) buildChips()
        if (!loadedOnce) loadPage(1, false)
    }

    override fun onResume() {
        if (isHome && Site.base != lastBase) {
            // the site address was changed in Settings
            lastBase = Site.base
            currentUrl = Site.home()
            buildChips()
            clearItems()
            loadPage(1, false)
            return
        }
        if (lastPaged != Prefs.pagedFeed || lastCompact != Prefs.compactFeed) {
            lastPaged = Prefs.pagedFeed
            lastCompact = Prefs.compactFeed
            adapter.notifyDataSetChanged()
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
        private fun hasFooter(): Boolean = items.isNotEmpty() && (Prefs.pagedFeed || footer != Footer.NONE)

        override fun getItemCount(): Int = items.size + if (hasFooter()) 1 else 0

        override fun getItemViewType(position: Int): Int = when {
            position < items.size -> if (Prefs.compactFeed) 3 else 0
            Prefs.pagedFeed -> 2
            else -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> Holder(VideoCardView(parent.context))
            3 -> Holder(VideoRowView(parent.context))
            2 -> Holder(PagerView(parent.context))
            else -> Holder(footerView())
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when {
                position < items.size -> {
                    val v = holder.itemView
                    if (v is VideoCardView) v.bind(items[position], actions) else (v as VideoRowView).bind(items[position], actions)
                }
                Prefs.pagedFeed -> (holder.itemView as PagerView).bind(pageNo, pageMax, more, loading) { n -> loadPage(n, false) }
                else -> bindFooter(holder.itemView as LinearLayout)
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
        /** An unknown-length list with at least this many videos on a page probably has another page. */
        private const val FULL_PAGE = 12

        fun home(activity: MainActivity) = FeedScreen(activity, true, "", Site.home())

        fun listing(activity: MainActivity, url: String, title: String) = FeedScreen(activity, false, title, url)
    }
}
