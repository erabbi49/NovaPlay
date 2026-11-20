package com.novaplay.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** A minimal bottom sheet: an optional heading and a list of icon + label rows. */
class BottomSheet(private val ctx: Context, private val heading: String? = null) {
    private class Item(val icon: Int, val label: String, val onClick: () -> Unit)

    private val items = ArrayList<Item>()

    fun add(icon: Int, label: String, onClick: () -> Unit): BottomSheet {
        items.add(Item(icon, label, onClick))
        return this
    }

    fun show() {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = Ui.dpf(ctx, 16f)
        val sheet = GradientDrawable()
        sheet.setColor(Ui.surfaceVariant(ctx))
        sheet.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.background = sheet
        root.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))

        if (!heading.isNullOrEmpty()) {
            val h = Ui.textView(ctx, 14f, Ui.textDim(ctx), true, 2)
            h.text = heading
            h.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 10), Ui.dp(ctx, 20), Ui.dp(ctx, 10))
            root.addView(h, Ui.lp(Ui.MATCH, Ui.WRAP))
            root.addView(Ui.hr(ctx), Ui.lp(Ui.MATCH, Ui.dp(ctx, 1)))
        }
        for (item in items) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 14), Ui.dp(ctx, 20), Ui.dp(ctx, 14))
            row.background = Ui.ripple(ctx, null)
            val icon = ImageView(ctx)
            icon.setImageResource(item.icon)
            icon.setColorFilter(Ui.text(ctx))
            row.addView(icon, Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)))
            val label = Ui.textView(ctx, 16f, Ui.text(ctx), false, 1)
            label.text = item.label
            val lp = Ui.lp(Ui.MATCH, Ui.WRAP)
            lp.leftMargin = Ui.dp(ctx, 20)
            row.addView(label, lp)
            row.setOnClickListener {
                dialog.dismiss()
                item.onClick()
            }
            root.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val scroll = ScrollView(ctx)
        scroll.addView(root, Ui.lp(Ui.MATCH, Ui.WRAP))
        dialog.setContentView(scroll)
        val w = dialog.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(Ui.MATCH, Ui.WRAP)
            w.setGravity(Gravity.BOTTOM)
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
    }
}
