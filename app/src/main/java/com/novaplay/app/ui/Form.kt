package com.novaplay.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Builds settings-style pages (sections, switches, pickers, buttons) into a vertical LinearLayout. */
class Form(private val ctx: Context, private val parent: LinearLayout) {

    fun section(title: String) {
        val t = Ui.textView(ctx, 13f, Ui.accent(ctx), true, 1)
        t.text = title
        t.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 20), Ui.dp(ctx, 6))
        parent.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    fun note(text: String) {
        val t = Ui.textView(ctx, 13f, Ui.textDim(ctx))
        t.text = text
        t.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 4), Ui.dp(ctx, 20), Ui.dp(ctx, 12))
        parent.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    fun divider() {
        parent.addView(Ui.hr(ctx), Ui.lp(Ui.MATCH, Ui.dp(ctx, 1)))
    }

    private class Row(val box: LinearLayout, val title: TextView, val subtitle: TextView)

    private fun row(title: String, subtitle: String?): Row {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = Ui.ripple(ctx, null)
        box.minimumHeight = Ui.dp(ctx, 56)
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), Ui.dp(ctx, 8))
        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val t = Ui.textView(ctx, 16f, Ui.text(ctx))
        t.text = title
        val s = Ui.textView(ctx, 13f, Ui.textDim(ctx))
        texts.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP))
        texts.addView(s, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(texts, Ui.lp(0, Ui.WRAP).also { it.weight = 1f })
        setSubtitle(s, subtitle)
        parent.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
        return Row(box, t, s)
    }

    private fun setSubtitle(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun toggle(title: String, subtitle: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val r = row(title, subtitle)
        val sw = Switch(ctx)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
        r.box.addView(sw, Ui.lp(Ui.WRAP, Ui.WRAP))
        r.box.setOnClickListener { sw.toggle() }
    }

    /** A button row that shows the current value underneath and opens a list of options. */
    fun <T> choice(
        title: String,
        options: List<Pair<String, T>>,
        get: () -> T,
        set: (T) -> Unit,
        onChanged: (() -> Unit)? = null
    ) {
        val r = row(title, null)
        fun currentLabel(): String = options.firstOrNull { it.second == get() }?.first ?: options.first().first
        setSubtitle(r.subtitle, currentLabel())
        r.box.setOnClickListener {
            val labels = Array<CharSequence>(options.size) { options[it].first }
            val current = options.indexOfFirst { it.second == get() }.coerceAtLeast(0)
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    set(options[which].second)
                    setSubtitle(r.subtitle, currentLabel())
                    dialog.dismiss()
                    onChanged?.invoke()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** A row with an icon in front that opens another page, like the categories of the YouTube settings. */
    fun nav(icon: Int, title: String, subtitle: String?, onClick: () -> Unit): TextView {
        val r = row(title, subtitle)
        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(Ui.text(ctx))
        r.box.addView(iv, 0, Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)).also { it.rightMargin = Ui.dp(ctx, 24) })
        r.box.setOnClickListener { onClick() }
        return r.subtitle
    }

    fun action(title: String, subtitle: String?, onClick: () -> Unit): TextView {
        val r = row(title, subtitle)
        r.box.setOnClickListener { onClick() }
        return r.subtitle
    }
}
