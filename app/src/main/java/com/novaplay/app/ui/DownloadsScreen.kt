package com.novaplay.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novaplay.app.R
import com.novaplay.app.data.DlState
import com.novaplay.app.data.DownloadItem
import com.novaplay.app.data.Library
import com.novaplay.app.download.DownloadCenter
import com.novaplay.app.player.PlayerActivity

/** A download as a row: the usual video row plus a progress bar. */
class DownloadRowView(context: Context) : VideoRowView(context) {
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)

    init {
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(Ui.accent(context))
        val lp = Ui.lp(Ui.MATCH, Ui.dp(context, 3))
        lp.topMargin = Ui.dp(context, 4)
        textColumn.addView(bar, lp)
    }

    fun bindItem(item: DownloadItem, actions: VideoActions) {
        bind(item.video, actions, false, statusOf(item))
        val active = item.state != DlState.DONE
        bar.visibility = if (active) View.VISIBLE else View.GONE
        val indeterminate = item.state == DlState.RUNNING && item.progress < 0f
        bar.isIndeterminate = indeterminate
        if (!indeterminate) bar.progress = (item.progress.coerceIn(0f, 1f) * 1000).toInt()
    }

    companion object {
        fun statusOf(d: DownloadItem): String {
            val pct = if (d.progress >= 0f) "${(d.progress * 100).toInt()}%" else ""
            return when (d.state) {
                DlState.DONE -> "Downloaded • " + Ui.formatBytes(d.bytes)
                DlState.RUNNING -> when {
                    d.progress >= 0f && d.totalBytes > 0L ->
                        "$pct • ${Ui.formatBytes(d.bytes)} of ${Ui.formatBytes(d.totalBytes)}"
                    else -> "Downloading • ${Ui.formatBytes(d.bytes)}"
                }
                DlState.QUEUED -> d.error.ifEmpty { "Waiting" }
                DlState.PAUSED -> if (d.bytes > 0L) "Paused • $pct" else "Paused"
                else -> "Failed: " + d.error.ifEmpty { "unknown error" }
            }
        }
    }
}

/** Everything downloaded or on its way. Finished items play offline. */
class DownloadsScreen(activity: MainActivity) : Screen(activity) {
    private val ctx = activity
    private val root = LinearLayout(ctx)
    private val list = RecyclerView(ctx)
    private val state = StateBox(ctx)
    private val summary = Ui.textView(ctx, 13f, Ui.textDim(ctx))
    private var items: List<DownloadItem> = emptyList()

    private val actions = VideoActions(
        onOpen = { v -> items.firstOrNull { it.id == v.id }?.let { open(it) } },
        onMore = { v -> items.firstOrNull { it.id == v.id }?.let { menu(it) } },
        onChannel = null
    )

    private val adapter = object : RecyclerView.Adapter<ViewVH>() {
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewVH = ViewVH(DownloadRowView(parent.context))

        override fun onBindViewHolder(holder: ViewVH, position: Int) {
            (holder.itemView as DownloadRowView).bindItem(items[position], actions)
        }
    }

    private val libraryListener = Runnable { refresh() }

    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(ctx))
        val bar = TopBar(ctx)
        bar.title.text = "Downloads"
        bar.action(R.drawable.ic_play, "Resume all") { DownloadCenter.resumeAll() }
        bar.action(R.drawable.ic_pause, "Pause all") { DownloadCenter.pauseAll() }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        summary.setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        root.addView(summary, Ui.lp(Ui.MATCH, Ui.WRAP))
        val body = FrameLayout(ctx)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        body.addView(list, Ui.frame(Ui.MATCH, Ui.MATCH))
        body.addView(state, Ui.frame(Ui.MATCH, Ui.MATCH))
        root.addView(body, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
    }

    private fun refresh() {
        items = Library.downloadList()
        adapter.notifyDataSetChanged()
        if (items.isEmpty()) {
            summary.visibility = View.GONE
            state.show("No downloads yet. Open the menu of any video and choose Download; it will play from your phone, even without internet.")
            return
        }
        state.hide()
        summary.visibility = View.VISIBLE
        var total = 0L
        var done = 0
        for (d in items) {
            if (d.state == DlState.DONE) {
                done++
                total += d.bytes
            }
        }
        val waiting = items.size - done
        summary.text = buildString {
            append(done).append(if (done == 1) " video" else " videos").append(" • ").append(Ui.formatBytes(total))
            if (waiting > 0) append(" • ").append(waiting).append(" in progress")
        }
    }

    private fun open(item: DownloadItem) {
        when (item.state) {
            DlState.DONE -> PlayerActivity.play(ctx, item.video)
            DlState.RUNNING, DlState.QUEUED -> DownloadCenter.pause(item.id)
            else -> DownloadCenter.resume(item.id)
        }
    }

    private fun menu(item: DownloadItem) {
        val s = BottomSheet(ctx, item.video.title)
        when (item.state) {
            DlState.DONE -> {
                s.add(R.drawable.ic_play, "Play") { PlayerActivity.play(ctx, item.video) }
                s.add(R.drawable.ic_next, "Play next") { com.novaplay.app.player.Playback.playNext(item.video) }
                s.add(R.drawable.ic_download, "Save to gallery") {
                    DownloadCenter.saveToGallery(item) { msg -> Dialogs.toast(ctx, msg) }
                }
            }
            DlState.RUNNING, DlState.QUEUED -> s.add(R.drawable.ic_pause, "Pause") { DownloadCenter.pause(item.id) }
            else -> s.add(R.drawable.ic_play, if (item.state == DlState.FAILED) "Try again" else "Resume") {
                DownloadCenter.resume(item.id)
            }
        }
        s.add(R.drawable.ic_share, "Share link") { VideoMenu.share(ctx, item.video) }
        val label = if (item.state == DlState.DONE) "Delete download" else "Cancel download"
        s.add(R.drawable.ic_delete, label) {
            Dialogs.confirm(ctx, "$label?", item.video.title, "Yes") { DownloadCenter.remove(item.id) }
        }
        s.show()
    }

    override fun onShow() {
        Library.addListener(libraryListener)
        refresh()
    }

    override fun onHide() {
        Library.removeListener(libraryListener)
    }

    override fun onDestroy() {
        Library.removeListener(libraryListener)
    }
}
