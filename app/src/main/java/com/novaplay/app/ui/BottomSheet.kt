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
import com.novaplay.app.R

/** A minimal bottom sheet: an optional heading and a list of icon + label rows. */
class BottomSheet(private val ctx: Context, private val heading: String? = null) {
    /** [checked] null = a normal row, otherwise a choice row that shows a check mark when it is the current one. */
    private class Item(val icon: Int, val label: String, val value: String?, val checked: Boolean?, val onClick: () -> Unit)

    private val items = ArrayList<Item>()

    fun add(icon: Int, label: String, onClick: () -> Unit): BottomSheet {
        items.add(Item(icon, label, null, null, onClick))
        return this
    }

    /** A row with the current value shown at the right, like the player's settings sheet. */
    fun add(icon: Int, label: String, value: String?, onClick: () -> Unit): BottomSheet {
        items.add(Item(icon, label, value, null, onClick))
        return this
    }

    /** One option of a list where exactly one is current. */
    fun choice(label: String, checked: Boolean, onClick: () -> Unit): BottomSheet {
        items.add(Item(R.drawable.ic_check, label, null, checked, onClick))
        return this
    }

    fun show() {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = Ui.dpf(ctx, 14f)
        val sheet = GradientDrawable()
        sheet.setColor(Ui.sheet(ctx))
        sheet.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.background = sheet
        root.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))

        // the little bar at the top that says "this sheet can be dragged away"
        val handle = View(ctx)
        handle.background = Ui.rounded(Ui.textDim(ctx) and 0x00FFFFFF or 0x66000000, Ui.dpf(ctx, 2f))
        val hp = Ui.lp(Ui.dp(ctx, 36), Ui.dp(ctx, 4))
        hp.gravity = Gravity.CENTER_HORIZONTAL
        hp.bottomMargin = Ui.dp(ctx, 6)
        root.addView(handle, hp)

        if (!heading.isNullOrEmpty()) {
            val h = Ui.medium(Ui.textView(ctx, 14f, Ui.textDim(ctx), false, 2))
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
            icon.visibility = if (item.checked == false) View.INVISIBLE else View.VISIBLE
            row.addView(icon, Ui.lp(Ui.dp(ctx, 24), Ui.dp(ctx, 24)))
            val label = Ui.textView(ctx, 16f, Ui.text(ctx), false, 1)
            label.text = item.label
            val lp = Ui.lp(0, Ui.WRAP)
            lp.weight = 1f
            lp.leftMargin = Ui.dp(ctx, 20)
            row.addView(label, lp)
            if (item.value != null) {
                val value = Ui.textView(ctx, 14f, Ui.textDim(ctx), false, 1)
                value.text = item.value
                val vp = Ui.lp(Ui.WRAP, Ui.WRAP)
                vp.leftMargin = Ui.dp(ctx, 12)
                row.addView(value, vp)
            }
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
