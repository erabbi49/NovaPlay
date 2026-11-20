package com.novaplay.app.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novaplay.app.R
import com.novaplay.app.data.Library
import com.novaplay.app.data.Video

/** A plain list of saved videos: history, liked, watch later, one playlist. */
class VideoListScreen(
    activity: MainActivity,
    private val title: String,
    private val emptyText: String,
    private val supplier: () -> List<Video>,
    private val extrasFor: ((Video) -> List<VideoMenu.Extra>)? = null,
    private val overflow: ((BottomSheet) -> Unit)? = null
) : Screen(activity) {
    private val ctx = activity
    private val root = LinearLayout(ctx)
    private val list = RecyclerView(ctx)
    private val state = StateBox(ctx)
    private var items: List<Video> = emptyList()
    private val libraryListener = Runnable { refresh() }

    private val actions = VideoActions(
        onOpen = { v ->
            val at = items.indexOfFirst { it.id == v.id }
            // starting from a list plays the rest of it afterwards, like a playlist
            com.novaplay.app.player.PlayerActivity.playAll(ctx, items, if (at < 0) 0 else at)
        },
        onMore = { v ->
            VideoMenu.show(activity, v, extrasFor?.invoke(v) ?: emptyList()) { url, t -> activity.openListing(url, t) }
        },
        onChannel = null
    )

    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(ctx))
        val bar = TopBar(ctx)
        bar.title.text = title
        bar.showBack { activity.pop() }
        bar.action(R.drawable.ic_play, "Play all") { PlayAll.start(ctx, items, false) }
        bar.action(R.drawable.ic_shuffle, "Shuffle") { PlayAll.start(ctx, items, true) }
        if (overflow != null) {
            bar.action(R.drawable.ic_more, "More") {
                val sheet = BottomSheet(ctx, title)
                overflow.invoke(sheet)
                sheet.show()
            }
        }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        val body = FrameLayout(ctx)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = RowAdapter({ items }, actions)
        list.itemAnimator = null
        body.addView(list, Ui.frame(Ui.MATCH, Ui.MATCH))
        body.addView(state, Ui.frame(Ui.MATCH, Ui.MATCH))
        root.addView(body, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
    }

    private fun refresh() {
        items = supplier()
        list.adapter?.notifyDataSetChanged()
        if (items.isEmpty()) state.show(emptyText) else state.hide()
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
