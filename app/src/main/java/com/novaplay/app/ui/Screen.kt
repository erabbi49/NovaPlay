package com.novaplay.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.novaplay.app.R

/** One page of [MainActivity]: a tab, or something pushed on top of a tab (a channel, search results ...). */
abstract class Screen(protected val activity: MainActivity) {
    abstract val view: View

    open fun onShow() {}

    open fun onHide() {}

    /** The activity came back to the foreground (e.g. from the in-app browser or Settings). */
    open fun onResume() {}

    /** Return true when the back press was used up (e.g. a search box was cleared). */
    open fun onBack(): Boolean = false

    open fun onDestroy() {}
}

/** The bar at the top of a screen: optional back arrow, a title and any number of icon buttons. */
class TopBar(context: Context) : LinearLayout(context) {
    private val back: ImageButton = Ui.iconButton(context, R.drawable.ic_back, "Back", Ui.text(context))
    val title: TextView = Ui.textView(context, 20f, Ui.text(context), true, 1)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
        minimumHeight = Ui.dp(context, 56)
        back.visibility = View.GONE
        addView(back)
        val lp = Ui.lp(0, Ui.WRAP)
        lp.weight = 1f
        lp.leftMargin = Ui.dp(context, 12)
        lp.rightMargin = Ui.dp(context, 4)
        addView(title, lp)
    }

    fun showBack(onClick: () -> Unit) {
        back.visibility = View.VISIBLE
        back.setOnClickListener { onClick() }
    }

    fun action(icon: Int, description: String, onClick: () -> Unit): ImageButton {
        val b = Ui.iconButton(context, icon, description, Ui.text(context))
        b.setOnClickListener { onClick() }
        addView(b)
        return b
    }
}
