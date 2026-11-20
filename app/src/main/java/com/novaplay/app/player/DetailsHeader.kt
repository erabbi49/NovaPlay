package com.novaplay.app.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.novaplay.app.R
import com.novaplay.app.data.DlState
import com.novaplay.app.data.Library
import com.novaplay.app.data.Source
import com.novaplay.app.data.StreamInfo
import com.novaplay.app.data.Video
import com.novaplay.app.ui.BrowserActivity
import com.novaplay.app.ui.Dialogs
import com.novaplay.app.ui.Ui
import com.novaplay.app.ui.VideoMenu
import com.novaplay.app.ui.avatarColor

/** Everything between the player and the "up next" list: title, description, action chips, channel row. */
class DetailsHeader(context: Context, private val onOpenListing: (url: String, title: String) -> Unit) :
    LinearLayout(context) {

    private val titleView = Ui.textView(context, 18f, Ui.text(context), true)
    private val metaView = Ui.textView(context, 13f, Ui.textDim(context))
    private val moreView = Ui.textView(context, 13f, Ui.textDim(context), true)
    private val descView = Ui.textView(context, 14f, Ui.text(context))
    private val tagsRow = LinearLayout(context)
    private val tagsScroll = HorizontalScrollView(context)
    private val actionsRow = LinearLayout(context)
    private val channelRow = LinearLayout(context)

    private var expanded = false
    private var boundId = ""
    private var video: Video? = null
    private var info: StreamInfo? = null
    private var status = Playback.Status.IDLE

    init {
        orientation = VERTICAL
        layoutParams = android.view.ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP)
        val pad = Ui.dp(context, 14)
        val titleBox = LinearLayout(context)
        titleBox.orientation = VERTICAL
        titleBox.setPadding(pad, Ui.dp(context, 12), pad, 0)
        titleBox.addView(titleView, Ui.lp(Ui.MATCH, Ui.WRAP))
        titleBox.addView(metaView, Ui.lp(Ui.MATCH, Ui.WRAP))
        descView.setPadding(0, Ui.dp(context, 10), 0, 0)
        titleBox.addView(descView, Ui.lp(Ui.MATCH, Ui.WRAP))
        titleBox.addView(moreView, Ui.lp(Ui.MATCH, Ui.WRAP))
        titleBox.setOnClickListener { toggleExpanded() }
        addView(titleBox, Ui.lp(Ui.MATCH, Ui.WRAP))

        tagsRow.orientation = HORIZONTAL
        tagsRow.setPadding(pad, Ui.dp(context, 8), pad, 0)
        tagsScroll.isHorizontalScrollBarEnabled = false
        tagsScroll.addView(tagsRow, Ui.lp(Ui.WRAP, Ui.WRAP))
        addView(tagsScroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        val actionsScroll = HorizontalScrollView(context)
        actionsScroll.isHorizontalScrollBarEnabled = false
        actionsRow.orientation = HORIZONTAL
        actionsRow.setPadding(pad, Ui.dp(context, 12), pad, Ui.dp(context, 4))
        actionsScroll.addView(actionsRow, Ui.lp(Ui.WRAP, Ui.WRAP))
        addView(actionsScroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        channelRow.orientation = HORIZONTAL
        channelRow.gravity = Gravity.CENTER_VERTICAL
        channelRow.setPadding(pad, Ui.dp(context, 10), pad, Ui.dp(context, 10))
        addView(channelRow, Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(Ui.hr(context), Ui.lp(Ui.MATCH, Ui.dp(context, 1)))
    }

    fun bind(video: Video?, info: StreamInfo?, status: Playback.Status) {
        this.video = video
        this.info = info
        this.status = status
        if (video != null && video.id != boundId) {
            boundId = video.id
            expanded = false
        }
        render()
    }

    private fun toggleExpanded() {
        expanded = !expanded
        render()
    }

    private fun render() {
        val v = video
        if (v == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        val i = info
        titleView.text = i?.title?.takeIf { it.isNotEmpty() } ?: v.title
        titleView.maxLines = if (expanded) Int.MAX_VALUE else 2
        titleView.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END

        val meta = ArrayList<String>(3)
        val views = i?.views?.takeIf { it.isNotEmpty() } ?: v.views
        if (views.isNotEmpty()) meta.add(views)
        if (status == Playback.Status.RESOLVING) meta.add("Loading…")
        metaView.text = meta.joinToString(" • ")
        metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

        val desc = i?.description.orEmpty().trim()
        val tags = i?.tags.orEmpty()
        val hasMore = desc.isNotEmpty() || tags.isNotEmpty()
        descView.text = desc
        descView.visibility = if (expanded && desc.isNotEmpty()) View.VISIBLE else View.GONE
        moreView.text = if (expanded) "Show less" else "…more"
        moreView.visibility = if (hasMore) View.VISIBLE else View.GONE
        buildTags(if (expanded) tags else emptyList())
        buildActions(v, i)
        buildChannel(v, i)
    }

    private fun buildTags(tags: List<Source>) {
        tagsRow.removeAllViews()
        tagsScroll.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
        for (t in tags.take(24)) {
            val chip = Ui.chip(context, t.name, false)
            chip.setOnClickListener { onOpenListing(t.url, t.name) }
            val lp = Ui.lp(Ui.WRAP, Ui.WRAP)
            lp.rightMargin = Ui.dp(context, 8)
            tagsRow.addView(chip, lp)
        }
    }

    private fun buildActions(v: Video, i: StreamInfo?) {
        actionsRow.removeAllViews()
        val liked = Library.isLiked(v.id)
        add(R.drawable.ic_thumb_up, if (liked) "Liked" else (i?.likes?.takeIf { it.isNotEmpty() } ?: "Like"), liked) {
            Library.toggleLike(v)
            render()
        }
        val later = Library.isWatchLater(v.id)
        add(R.drawable.ic_watch_later, if (later) "Saved" else "Watch later", later) {
            Library.toggleWatchLater(v)
            render()
        }
        add(R.drawable.ic_playlist_add, "Playlist", false) { Dialogs.saveToPlaylist(context, v) }
        val dl = Library.downloadFor(v.id)
        val dlLabel = when {
            dl == null -> "Download"
            dl.state == DlState.DONE -> "Downloaded"
            dl.state == DlState.RUNNING -> if (dl.progress >= 0f) "${(dl.progress * 100).toInt()}%" else "Downloading"
            dl.state == DlState.QUEUED -> "Queued"
            dl.state == DlState.PAUSED -> "Paused"
            else -> "Failed"
        }
        add(R.drawable.ic_download, dlLabel, dl != null && dl.state == DlState.DONE) {
            if (dl == null) Dialogs.download(context, v) else VideoMenu.show(context, v)
        }
        add(R.drawable.ic_share, "Share", false) { VideoMenu.share(context, v) }
        add(R.drawable.ic_browser, "Browser", false) { BrowserActivity.open(context, v.url) }
    }

    private fun add(icon: Int, label: String, active: Boolean, onClick: () -> Unit) {
        val ctx = context
        val row = LinearLayout(ctx)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 16), 0)
        val fg = if (active) Ui.chipSelectedText(ctx) else Ui.text(ctx)
        row.background = Ui.rounded(if (active) Ui.chipSelected(ctx) else Ui.chipBg(ctx), Ui.dpf(ctx, 18f))
        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(fg)
        row.addView(iv, Ui.lp(Ui.dp(ctx, 20), Ui.dp(ctx, 20)))
        val tv = Ui.textView(ctx, 13f, fg, true, 1)
        tv.text = label
        val tp = Ui.lp(Ui.WRAP, Ui.WRAP)
        tp.leftMargin = Ui.dp(ctx, 8)
        row.addView(tv, tp)
        row.setOnClickListener { onClick() }
        val lp = Ui.lp(Ui.WRAP, Ui.dp(ctx, 36))
        lp.rightMargin = Ui.dp(ctx, 8)
        actionsRow.addView(row, lp)
    }

    private fun buildChannel(v: Video, i: StreamInfo?) {
        channelRow.removeAllViews()
        val name = i?.uploader?.takeIf { it.isNotEmpty() } ?: v.uploader
        val url = i?.uploaderUrl?.takeIf { it.isNotEmpty() } ?: v.uploaderUrl
        if (name.isEmpty()) {
            channelRow.visibility = View.GONE
            return
        }
        channelRow.visibility = View.VISIBLE
        val ctx = context
        val avatar = Ui.textView(ctx, 16f, Color.WHITE, true, 1)
        avatar.gravity = Gravity.CENTER
        avatar.text = name.substring(0, name.offsetByCodePoints(0, 1)).uppercase()
        avatar.background = Ui.rounded(avatarColor(name), Ui.dpf(ctx, 20f))
        channelRow.addView(avatar, Ui.lp(Ui.dp(ctx, 40), Ui.dp(ctx, 40)))
        val label: TextView = Ui.textView(ctx, 15f, Ui.text(ctx), true, 1)
        label.text = name
        val lp = Ui.lp(0, Ui.WRAP)
        lp.weight = 1f
        lp.leftMargin = Ui.dp(ctx, 12)
        lp.rightMargin = Ui.dp(ctx, 8)
        channelRow.addView(label, lp)
        if (url.isNotEmpty()) {
            channelRow.setOnClickListener { onOpenListing(url, name) }
            val subbed = Library.isSubscribed(url)
            val btn = Ui.chip(ctx, if (subbed) "Subscribed" else "Subscribe", subbed)
            btn.setOnClickListener {
                Library.toggleSub(Source(name, url))
                render()
            }
            channelRow.addView(btn, Ui.lp(Ui.WRAP, Ui.dp(ctx, 36)))
        } else {
            channelRow.setOnClickListener(null)
        }
    }
}
