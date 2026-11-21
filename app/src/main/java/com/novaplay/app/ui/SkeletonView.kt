package com.novaplay.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * Grey placeholder cards that pulse while a list loads (instead of a lone spinner), the way the real thing does it.
 * Show and hide it with [View.setVisibility]; the pulse only runs while it is visible.
 */
class SkeletonView(context: Context, private val cards: Int = 4, private val compact: Boolean = false) :
    LinearLayout(context) {
    private var pulse: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.bg(context))
        val grey = Ui.skeleton(context)
        repeat(cards) {
            if (compact) addView(compactRow(grey), Ui.lp(Ui.MATCH, Ui.WRAP)) else addView(card(grey), Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        isClickable = true // swallows touches meant for the (empty) list underneath
    }

    private fun bar(grey: Int, heightDp: Int): View {
        val v = View(context)
        v.background = Ui.rounded(grey, Ui.dpf(context, heightDp / 2f))
        return v
    }

    private fun card(grey: Int): View {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        val thumb = AspectFrame(context, 16f / 9f)
        val t = View(context)
        t.background = Ui.rounded(grey, Ui.dpf(context, 12f))
        thumb.addView(t, Ui.frame(Ui.MATCH, Ui.MATCH))
        val tp = Ui.lp(Ui.MATCH, Ui.WRAP)
        tp.setMargins(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), 0)
        box.addView(thumb, tp)

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 16))
        val avatar = View(context)
        avatar.background = Ui.rounded(grey, Ui.dpf(context, 18f))
        row.addView(avatar, Ui.lp(Ui.dp(context, 36), Ui.dp(context, 36)))
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        val l1 = Ui.lp(Ui.MATCH, Ui.dp(context, 12))
        l1.topMargin = Ui.dp(context, 4)
        col.addView(bar(grey, 12), l1)
        val l2 = Ui.lp(Ui.dp(context, 200), Ui.dp(context, 12))
        l2.topMargin = Ui.dp(context, 8)
        col.addView(bar(grey, 12), l2)
        val cp = Ui.lp(0, Ui.WRAP)
        cp.weight = 1f
        cp.leftMargin = Ui.dp(context, 12)
        cp.rightMargin = Ui.dp(context, 40)
        row.addView(col, cp)
        box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        return box
    }

    private fun compactRow(grey: Int): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
        val thumb = AspectFrame(context, 16f / 9f)
        val t = View(context)
        t.background = Ui.rounded(grey, Ui.dpf(context, 8f))
        thumb.addView(t, Ui.frame(Ui.MATCH, Ui.MATCH))
        row.addView(thumb, Ui.lp(Ui.dp(context, 156), Ui.WRAP))
        val col = LinearLayout(context)
        col.orientation = VERTICAL
        val l1 = Ui.lp(Ui.MATCH, Ui.dp(context, 12))
        l1.topMargin = Ui.dp(context, 4)
        col.addView(bar(grey, 12), l1)
        val l2 = Ui.lp(Ui.dp(context, 110), Ui.dp(context, 12))
        l2.topMargin = Ui.dp(context, 8)
        col.addView(bar(grey, 12), l2)
        val cp = Ui.lp(0, Ui.WRAP)
        cp.weight = 1f
        cp.leftMargin = Ui.dp(context, 10)
        row.addView(col, cp)
        return row
    }

    private fun startPulse() {
        if (pulse != null) return
        val a = ValueAnimator.ofFloat(1f, 0.45f)
        a.duration = 800
        a.repeatMode = ValueAnimator.REVERSE
        a.repeatCount = ValueAnimator.INFINITE
        a.addUpdateListener { alpha = it.animatedValue as Float }
        pulse = a
        a.start()
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        alpha = 1f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) {
            if (visibility == VISIBLE && isAttachedToWindow) startPulse() else stopPulse()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }
}
