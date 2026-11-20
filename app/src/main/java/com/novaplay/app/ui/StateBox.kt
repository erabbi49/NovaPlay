package com.novaplay.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** A centred message with up to two buttons: empty lists, errors, "nothing here yet". */
class StateBox(context: Context) : LinearLayout(context) {
    private val message: TextView = Ui.textView(context, 15f, Ui.textDim(context))
    private val primary: TextView = Ui.chip(context, "", true)
    private val secondary: TextView = Ui.chip(context, "", false)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = Ui.dp(context, 32)
        setPadding(pad, pad, pad, pad)
        message.gravity = Gravity.CENTER
        addView(message, Ui.lp(Ui.MATCH, Ui.WRAP))
        val gap = Ui.dp(context, 16)
        val p = Ui.lp(Ui.WRAP, Ui.dp(context, 40))
        p.topMargin = gap
        addView(primary, p)
        val s = Ui.lp(Ui.WRAP, Ui.dp(context, 40))
        s.topMargin = Ui.dp(context, 8)
        addView(secondary, s)
        visibility = View.GONE
    }

    fun show(
        text: String,
        primaryLabel: String? = null,
        onPrimary: (() -> Unit)? = null,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null
    ) {
        message.text = text
        bindButton(primary, primaryLabel, onPrimary)
        bindButton(secondary, secondaryLabel, onSecondary)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun bindButton(b: TextView, label: String?, action: (() -> Unit)?) {
        if (label == null || action == null) {
            b.visibility = View.GONE
            return
        }
        b.text = label
        b.visibility = View.VISIBLE
        b.setOnClickListener { action() }
    }
}
