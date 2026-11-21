package com.novaplay.app.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.novaplay.app.Nav
import com.novaplay.app.R
import com.novaplay.app.adblock.FilterLists
import com.novaplay.app.data.DlState
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.UserPlaylist
import com.novaplay.app.data.Video

/** The "You" tab: history, liked videos, watch later, playlists, and the way to Settings and the ad blocker. */
class LibraryScreen(activity: MainActivity) : Screen(activity) {
    private val ctx = activity
    private val root = LinearLayout(ctx)
    private val content = LinearLayout(ctx)
    private val libraryListener = Runnable { build() }

    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(ctx))
        val bar = TopBar(ctx)
        bar.title.text = "You"
        bar.action(R.drawable.ic_search, "Search") { activity.openSearch() }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        content.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(ctx)
        scroll.addView(content, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
    }

    private fun build() {
        content.removeAllViews()
        content.addView(profileHeader())
        val recent = Library.historyList()
        if (recent.isEmpty()) {
            content.addView(entry(R.drawable.ic_history, "History", "") { openHistory() })
        } else {
            content.addView(shelf("History", recent.take(12).map { it.video }) { openHistory() })
        }
        content.addView(entry(R.drawable.ic_thumb_up, "Liked videos", count(Library.likedList().size)) {
            activity.push(VideoListScreen(activity, "Liked videos", "Videos you like appear here.", { Library.likedList() }))
        })
        content.addView(entry(R.drawable.ic_watch_later, "Watch later", count(Library.watchLaterList().size)) {
            activity.push(VideoListScreen(activity, "Watch later", "Save videos to watch them later.", { Library.watchLaterList() }))
        })
        val done = Library.downloadList().count { it.state == DlState.DONE }
        content.addView(entry(R.drawable.ic_download, "Downloads", count(done)) { activity.select(Nav.TAB_DOWNLOADS) })
        content.addView(Ui.hr(ctx), Ui.lp(Ui.MATCH, Ui.dp(ctx, 1)))

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        val title = Ui.textView(ctx, 16f, Ui.text(ctx), true, 1)
        title.text = "Playlists"
        head.addView(title, Ui.lp(0, Ui.WRAP).also { it.weight = 1f })
        val add = Ui.iconButton(ctx, R.drawable.ic_add, "New playlist", Ui.text(ctx))
        add.setOnClickListener {
            Dialogs.prompt(ctx, "New playlist", "Playlist name", "", "Create") { name ->
                Library.createPlaylist(name)
            }
        }
        head.addView(add)
        content.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        val lists = Library.playlistList()
        if (lists.isEmpty()) {
            val hint = Ui.textView(ctx, 14f, Ui.textDim(ctx))
            hint.text = "Make playlists from the menu of any video."
            hint.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 4), Ui.dp(ctx, 20), Ui.dp(ctx, 12))
            content.addView(hint, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        for (p in lists) {
            content.addView(entry(R.drawable.ic_queue, p.name, count(p.videos.size)) { openPlaylist(p) })
        }
        content.addView(Ui.hr(ctx), Ui.lp(Ui.MATCH, Ui.dp(ctx, 1)))

        val blocked = FilterLists.blockedCount.get()
        val adLabel = if (FilterLists.adBlockEnabled) "%,d blocked".format(blocked) else "Off"
        content.addView(entry(R.drawable.ic_shield, "Ad blocker", adLabel) {
            ctx.startActivity(Intent(ctx, AdBlockActivity::class.java))
        })
        content.addView(entry(R.drawable.ic_tune, "Settings", "") {
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
        })
        content.addView(Ui.spacer(ctx, 0, 16))
    }

    private fun count(n: Int): String = if (n == 0) "" else n.toString()

    /** A big round initial, a name and two quick buttons, like the top of the YouTube "You" page. */
    private fun profileHeader(): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
        val avatar = Ui.medium(Ui.textView(ctx, 30f, android.graphics.Color.WHITE, false, 1))
        avatar.gravity = Gravity.CENTER
        avatar.text = "N"
        avatar.background = Ui.rounded(Ui.accent(ctx), Ui.dpf(ctx, 36f))
        box.addView(avatar, Ui.lp(Ui.dp(ctx, 72), Ui.dp(ctx, 72)))
        val name = Ui.medium(Ui.textView(ctx, 22f, Ui.text(ctx), false, 1))
        name.text = "You"
        name.gravity = Gravity.CENTER
        box.addView(name, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })
        val watched = Library.historyList().size
        val sub = Ui.textView(ctx, 13f, Ui.textDim(ctx), false, 1)
        sub.text = if (watched == 0) "Your history, playlists and downloads" else "$watched watched \u00b7 ${Library.downloadList().count { it.state == DlState.DONE }} downloaded"
        sub.gravity = Gravity.CENTER
        box.addView(sub, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 2) })
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val settings = Ui.pill(ctx, "Settings", false)
        settings.setOnClickListener { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }
        row.addView(settings, Ui.lp(Ui.WRAP, Ui.dp(ctx, 36)).also { it.rightMargin = Ui.dp(ctx, 8) })
        val ads = Ui.pill(ctx, "Ad blocker", false)
        ads.setOnClickListener { ctx.startActivity(Intent(ctx, AdBlockActivity::class.java)) }
        row.addView(ads, Ui.lp(Ui.WRAP, Ui.dp(ctx, 36)))
        box.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 14) })
        return box
    }

    /** A titled row of small video cards that scrolls sideways, with a "View all" button. */
    private fun shelf(title: String, videos: List<Video>, onAll: () -> Unit): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        val t = Ui.medium(Ui.textView(ctx, 16f, Ui.text(ctx), false, 1))
        t.text = title
        head.addView(t, Ui.lp(0, Ui.WRAP).also { it.weight = 1f })
        val all = Ui.pill(ctx, "View all", false)
        all.setOnClickListener { onAll() }
        head.addView(all, Ui.lp(Ui.WRAP, Ui.dp(ctx, 32)))
        box.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        val scroll = android.widget.HorizontalScrollView(ctx)
        scroll.isHorizontalScrollBarEnabled = false
        val strip = LinearLayout(ctx)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), Ui.dp(ctx, 8))
        for (v in videos) strip.addView(shelfCard(v), Ui.lp(Ui.dp(ctx, 164), Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        scroll.addView(strip, Ui.lp(Ui.WRAP, Ui.WRAP))
        box.addView(scroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        return box
    }

    private fun shelfCard(v: Video): View {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        val thumb = ThumbBox(ctx, 16f / 9f, 10)
        thumb.bind(v)
        card.addView(thumb, Ui.lp(Ui.MATCH, Ui.WRAP))
        val title = Ui.medium(Ui.textView(ctx, 13f, Ui.text(ctx), false, 2))
        title.text = v.title
        card.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        val meta = Ui.textView(ctx, 12f, Ui.textDim(ctx), false, 1)
        meta.text = v.uploader
        meta.visibility = if (v.uploader.isEmpty()) View.GONE else View.VISIBLE
        card.addView(meta, Ui.lp(Ui.MATCH, Ui.WRAP))
        card.setOnClickListener { activity.openVideo(v) }
        card.setOnLongClickListener {
            VideoMenu.show(ctx, v, emptyList()) { url, name -> activity.openListing(url, name) }
            true
        }
        return card
    }

    private fun entry(icon: Int, label: String, trailing: String, onClick: () -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.ripple(ctx, null)
        row.minimumHeight = Ui.dp(ctx, 52)
        row.setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20), 0)
        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(Ui.text(ctx))
        row.addView(iv, Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)))
        val text = Ui.textView(ctx, 16f, Ui.text(ctx), false, 1)
        text.text = label
        row.addView(text, Ui.lp(0, Ui.WRAP).also {
            it.weight = 1f
            it.leftMargin = Ui.dp(ctx, 20)
        })
        if (trailing.isNotEmpty()) {
            val t = Ui.textView(ctx, 14f, Ui.textDim(ctx), false, 1)
            t.text = trailing
            row.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun openHistory() {
        val removeEntry = { v: Video ->
            listOf(VideoMenu.Extra(R.drawable.ic_close, "Remove from history") { Library.removeHistory(v.id) })
        }
        activity.push(
            VideoListScreen(
                activity, "History", "Videos you watch appear here.",
                { Library.historyList().map { it.video } },
                removeEntry,
                { sheet ->
                    sheet.add(R.drawable.ic_clear_all, "Clear watch history") {
                        Dialogs.confirm(ctx, "Clear watch history?", "This also forgets where you stopped in each video.", "Clear") {
                            Library.clearHistory()
                        }
                    }
                    val on = Prefs.keepHistory
                    sheet.add(R.drawable.ic_history, if (on) "Pause watch history" else "Resume watch history") {
                        Prefs.keepHistory = !on
                        Dialogs.toast(ctx, if (on) "Watch history paused" else "Watch history on")
                    }
                }
            )
        )
    }

    private fun openPlaylist(p: UserPlaylist) {
        val id = p.id
        activity.push(
            VideoListScreen(
                activity, p.name, "This playlist is empty.",
                { Library.playlistList().firstOrNull { it.id == id }?.videos?.toList() ?: emptyList() },
                { v -> listOf(VideoMenu.Extra(R.drawable.ic_close, "Remove from playlist") { Library.removeFromPlaylist(id, v.id) }) },
                { sheet ->
                    sheet.add(R.drawable.ic_tune, "Rename") {
                        Dialogs.prompt(ctx, "Rename playlist", "Playlist name", p.name, "Rename") { name ->
                            Library.renamePlaylist(id, name)
                            activity.pop()
                        }
                    }
                    sheet.add(R.drawable.ic_delete, "Delete playlist") {
                        Dialogs.confirm(ctx, "Delete \"${p.name}\"?", "The videos stay available elsewhere.", "Delete") {
                            Library.deletePlaylist(id)
                            activity.pop()
                        }
                    }
                }
            )
        )
    }

    override fun onShow() {
        Library.addListener(libraryListener)
        build()
    }

    override fun onHide() {
        Library.removeListener(libraryListener)
    }

    override fun onResume() {
        build()
    }

    override fun onDestroy() {
        Library.removeListener(libraryListener)
    }
}
