package com.novaplay.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.novaplay.app.data.Video

/** A holder that just carries a view. */
class ViewVH(view: View) : RecyclerView.ViewHolder(view)

/** Big cards, one per video. */
class CardAdapter(
    private val items: () -> List<Video>,
    private val actions: VideoActions
) : RecyclerView.Adapter<ViewVH>() {
    override fun getItemCount(): Int = items().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewVH = ViewVH(VideoCardView(parent.context))

    override fun onBindViewHolder(holder: ViewVH, position: Int) {
        (holder.itemView as VideoCardView).bind(items()[position], actions)
    }
}

/**
 * Compact rows. [highlight] marks the current video, [detail] adds a third line, and [decorate]
 * can change anything else about a row (a drag handle, a different menu button).
 */
class RowAdapter(
    private val items: () -> List<Video>,
    private val actions: VideoActions,
    private val highlight: ((Video, Int) -> Boolean)? = null,
    private val detail: ((Video, Int) -> String)? = null,
    private val decorate: ((VideoRowView, Video, Int) -> Unit)? = null
) : RecyclerView.Adapter<ViewVH>() {
    override fun getItemCount(): Int = items().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewVH = ViewVH(VideoRowView(parent.context))

    override fun onBindViewHolder(holder: ViewVH, position: Int) {
        val row = holder.itemView as VideoRowView
        val v = items()[position]
        row.bind(v, actions, highlight?.invoke(v, position) ?: false, detail?.invoke(v, position) ?: "")
        decorate?.invoke(row, v, position)
    }
}
