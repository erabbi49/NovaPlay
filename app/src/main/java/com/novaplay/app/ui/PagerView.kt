package com.novaplay.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.novaplay.app.web.Paging

/** The "‹ 1 2 [3] 4 … 40 ›" bar under a list that is read page by page. */
class PagerView(context: Context) : LinearLayout(context) {
    private val scroll = HorizontalScrollView(context)
    private val row = LinearLayout(context)
    private val caption = Ui.textView(context, 12f, Ui.textDim(context))

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP)
        setPadding(Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 24))
        scroll.isHorizontalScrollBarEnabled = false
        scroll.isFillViewport = true
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER
        scroll.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP))
        addView(scroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        caption.gravity = Gravity.CENTER
        val cp = Ui.lp(Ui.MATCH, Ui.WRAP)
        cp.topMargin = Ui.dp(context, 8)
        addView(caption, cp)
    }

    /**
     * [cur] is the page on screen, [max] the number of pages (0 = the site does not say), [hasNext] whether
     * another page is worth asking for, [busy] while a page is loading.
     */
    fun bind(cur: Int, max: Int, hasNext: Boolean, busy: Boolean, onPage: (Int) -> Unit) {
        row.removeAllViews()
        val canNext = hasNext && (max == 0 || cur < max)
        row.addView(cell("‹", false, cur > 1 && !busy) { onPage(cur - 1) })
        for (e in Paging.window(cur, max, hasNext)) {
            if (e == 0) {
                row.addView(gap("…"))
            } else {
                row.addView(cell(e.toString(), e == cur, e != cur && !busy) { onPage(e) })
            }
        }
        row.addView(cell("›", false, canNext && !busy) { onPage(cur + 1) })
        caption.text = if (max > 0) "Page $cur of $max" else "Page $cur"
        alpha = if (busy) 0.6f else 1f
    }

    private fun cell(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit): TextView {
        val c = context
        val t = Ui.textView(c, 15f, if (selected) Ui.chipSelectedText(c) else Ui.text(c), selected, 1)
        t.text = label
        t.gravity = Gravity.CENTER
        t.minWidth = Ui.dp(c, 38)
        t.minHeight = Ui.dp(c, 38)
        t.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0)
        t.background = Ui.rounded(if (selected) Ui.chipSelected(c) else Ui.chipBg(c), Ui.dpf(c, 19f))
        t.alpha = if (enabled || selected) 1f else 0.35f
        t.isClickable = enabled
        if (enabled) t.setOnClickListener { onClick() }
        val lp = Ui.lp(Ui.WRAP, Ui.WRAP)
        lp.setMargins(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0)
        t.layoutParams = lp
        return t
    }

    private fun gap(label: String): View {
        val c = context
        val t = Ui.textView(c, 15f, Ui.textDim(c))
        t.text = label
        t.gravity = Gravity.CENTER
        t.minWidth = Ui.dp(c, 22)
        return t
    }
}
