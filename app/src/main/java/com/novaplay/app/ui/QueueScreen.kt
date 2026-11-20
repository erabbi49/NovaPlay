package com.novaplay.app.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novaplay.app.R
import com.novaplay.app.data.Library
import com.novaplay.app.data.Video
import com.novaplay.app.player.Playback
import com.novaplay.app.player.PlayerActivity

/** The play queue: the current video, what plays next, and what already played. Drag to reorder, swipe to remove. */
class QueueScreen(activity: MainActivity) : Screen(activity) {
    private val ctx = activity
    private val root = LinearLayout(ctx)
    private val list = RecyclerView(ctx)
    private val state = StateBox(ctx)
    private val bar = TopBar(ctx)
    private var cache: List<Video> = emptyList()
    private var internal = false

    private val actions = VideoActions(
        onOpen = { v ->
            val i = cache.indexOfFirst { it.id == v.id }
            if (i >= 0) {
                Playback.playIndex(i)
                PlayerActivity.show(ctx)
            }
        },
        onMore = { v ->
            val extras = ArrayList<VideoMenu.Extra>()
            val i = cache.indexOfFirst { it.id == v.id }
            if (i >= 0 && i != Playback.index) {
                extras.add(VideoMenu.Extra(R.drawable.ic_close, "Remove from queue") { Playback.removeAt(i) })
            }
            VideoMenu.show(activity, v, extras) { url, title -> activity.openListing(url, title) }
        },
        onChannel = null
    )

    private val adapter: RowAdapter = RowAdapter(
        { cache },
        actions,
        highlight = { _, pos -> pos == Playback.index },
        detail = { _, pos ->
            when {
                pos == Playback.index -> "Now playing"
                pos < Playback.index -> "Played"
                else -> ""
            }
        },
        decorate = { row, _, _ ->
            row.dragHandle.visibility = View.VISIBLE
            row.dragHandle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(list.getChildViewHolder(row))
                false
            }
        }
    )

    private val touch: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val pos = viewHolder.bindingAdapterPosition
            val swipe = if (pos == Playback.index) 0 else ItemTouchHelper.START or ItemTouchHelper.END
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, swipe)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            internal = true
            Playback.move(from, to)
            internal = false
            cache = Playback.queue()
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pos = viewHolder.bindingAdapterPosition
            if (pos < 0) return
            internal = true
            Playback.removeAt(pos)
            internal = false
            cache = Playback.queue()
            adapter.notifyItemRemoved(pos)
            updateBar()
        }

        override fun isLongPressDragEnabled(): Boolean = false

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            adapter.notifyDataSetChanged() // re-number the "Played / Now playing" labels
        }
    })

    private val playbackListener = object : Playback.Listener {
        override fun onVideoChanged() = refresh()
        override fun onQueueChanged() {
            if (!internal) refresh()
        }
    }

    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(ctx))
        bar.action(R.drawable.ic_shuffle, "Shuffle up next") {
            Playback.shuffleUpcoming()
            Dialogs.toast(ctx, "Up next shuffled")
        }
        bar.action(R.drawable.ic_playlist_add, "Save as playlist") { saveAsPlaylist() }
        bar.action(R.drawable.ic_clear_all, "Clear up next") { clearUpcoming() }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        val body = FrameLayout(ctx)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        touch.attachToRecyclerView(list)
        body.addView(list, Ui.frame(Ui.MATCH, Ui.MATCH))
        body.addView(state, Ui.frame(Ui.MATCH, Ui.MATCH))
        root.addView(body, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
        refresh()
    }

    private fun updateBar() {
        bar.title.text = if (cache.isEmpty()) "Queue" else "Queue (${cache.size})"
    }

    private fun refresh() {
        cache = Playback.queue()
        updateBar()
        adapter.notifyDataSetChanged()
        if (cache.isEmpty()) {
            state.show("Your queue is empty. Use “Play next” or “Add to queue” in any video's menu.")
        } else {
            state.hide()
        }
    }

    private fun saveAsPlaylist() {
        if (cache.isEmpty()) {
            Dialogs.toast(ctx, "The queue is empty")
            return
        }
        Dialogs.prompt(ctx, "Save queue as playlist", "Playlist name", "Queue", "Save") { name ->
            val p = Library.createPlaylist(name)
            for (v in cache) Library.addToPlaylist(p.id, v)
            Dialogs.toast(ctx, "Saved ${cache.size} videos to ${p.name}")
        }
    }

    private fun clearUpcoming() {
        if (Playback.upcoming().isEmpty()) {
            Dialogs.toast(ctx, "Nothing is waiting in the queue")
            return
        }
        Dialogs.confirm(ctx, "Clear up next?", "Videos after the current one will be removed from the queue.", "Clear") {
            Playback.clearUpcoming()
        }
    }

    override fun onShow() {
        Playback.addListener(playbackListener)
        refresh()
    }

    override fun onHide() {
        Playback.removeListener(playbackListener)
    }

    override fun onDestroy() {
        Playback.removeListener(playbackListener)
    }
}
