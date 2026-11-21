package com.novaplay.app.player

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import androidx.recyclerview.widget.RecyclerView
import com.novaplay.app.data.Prefs
import com.novaplay.app.data.Video
import com.novaplay.app.ui.Ui
import com.novaplay.app.ui.VideoActions
import com.novaplay.app.ui.VideoCardView
import com.novaplay.app.ui.VideoRowView

/** The list under the player: the details header, then "Up next" and related videos. */
class DetailsAdapter(
    private val header: DetailsHeader,
    private val actions: VideoActions
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        object Header : Row()
        class Section(val text: String, val autoplay: Boolean) : Row()
        class Item(val video: Video) : Row()
    }

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Section -> TYPE_SECTION
        is Row.Item -> if (Prefs.relatedCards) TYPE_CARD else TYPE_ITEM
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (viewType) {
            TYPE_HEADER -> Holder(FrameLayout(ctx).also { it.layoutParams = ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP) })
            TYPE_SECTION -> Holder(sectionView(ctx))
            TYPE_CARD -> Holder(VideoCardView(ctx))
            else -> Holder(VideoRowView(ctx))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                val box = holder.itemView as FrameLayout
                if (header.parent !== box) {
                    (header.parent as? ViewGroup)?.removeView(header)
                    box.removeAllViews()
                    box.addView(header, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
                }
            }
            is Row.Section -> bindSection(holder.itemView as LinearLayout, row)
            is Row.Item -> {
                val v = holder.itemView
                if (v is VideoCardView) v.bind(row.video, actions) else (v as VideoRowView).bind(row.video, actions)
            }
        }
    }

    private fun sectionView(ctx: android.content.Context): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP)
        row.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 4))
        val title = Ui.medium(Ui.textView(ctx, 16f, Ui.text(ctx), false, 1))
        val tp = Ui.lp(0, Ui.WRAP)
        tp.weight = 1f
        row.addView(title, tp)
        val label = Ui.textView(ctx, 13f, Ui.textDim(ctx))
        label.text = "Autoplay"
        val lp = Ui.lp(Ui.WRAP, Ui.WRAP)
        lp.rightMargin = Ui.dp(ctx, 8)
        row.addView(label, lp)
        row.addView(Switch(ctx), Ui.lp(Ui.WRAP, Ui.WRAP))
        return row
    }

    private fun bindSection(view: LinearLayout, row: Row.Section) {
        (view.getChildAt(0) as android.widget.TextView).text = row.text
        val label = view.getChildAt(1)
        val toggle = view.getChildAt(2) as Switch
        val show = if (row.autoplay) View.VISIBLE else View.GONE
        label.visibility = show
        toggle.visibility = show
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = Prefs.autoplayNext
        toggle.setOnCheckedChangeListener { _, checked -> Prefs.autoplayNext = checked }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SECTION = 1
        const val TYPE_ITEM = 2
        const val TYPE_CARD = 3
    }
}
