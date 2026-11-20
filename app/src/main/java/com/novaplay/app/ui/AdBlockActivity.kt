package com.novaplay.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.novaplay.app.R
import com.novaplay.app.adblock.FilterLists
import java.text.DateFormat
import java.util.Date

/** The ad blocker's control panel: switches, filter lists, updates and the user's own rules. */
class AdBlockActivity : BaseActivity() {
    private lateinit var content: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var userBox: EditText? = null
    private var updateLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(this))
        val bar = TopBar(this)
        bar.title.text = "Ad blocker"
        bar.showBack { finish() }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(content, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
        setContentView(root)
        build()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        FilterLists.saveStats()
        super.onDestroy()
    }

    private fun build() {
        content.removeAllViews()
        content.addView(statusCard(), Ui.lp(Ui.MATCH, Ui.WRAP))
        val f = Form(this, content)

        f.section("Protection")
        f.toggle("Ad blocker", "Block ads, trackers and pop-ups in the player pages and the in-app browser",
            { FilterLists.adBlockEnabled }, {
                FilterLists.adBlockEnabled = it
                build()
            })
        f.toggle("Hide ad elements", "Remove banners and leftover ad boxes from pages (cosmetic filters)",
            { FilterLists.cosmeticEnabled }, { FilterLists.cosmeticEnabled = it })
        f.toggle("Scriptlets", "Stop anti-ad-block scripts and ad timers, like uBlock Origin does",
            { FilterLists.scriptletsEnabled }, { FilterLists.scriptletsEnabled = it })
        f.toggle("Block pop-ups and redirects", "Close new windows that ads try to open",
            { FilterLists.popupBlockEnabled }, { FilterLists.popupBlockEnabled = it })

        f.section("Filter lists")
        for (l in FilterLists.catalog()) {
            val updated = if (l.updatedAt > 0L) {
                "updated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(l.updatedAt))
            } else {
                "built-in copy"
            }
            val rules = if (l.enabled) "%,d rules • ".format(l.ruleCount) else ""
            f.toggle(l.title, rules + updated, { l.enabled }, {
                FilterLists.setEnabled(l.id, it)
                ui.postDelayed({ build() }, 2500) // the engine rebuilds in the background; show the new counts afterwards
            })
        }
        updateLabel = f.action("Update filter lists now", "Download the newest copies (needs internet)") { update() }

        f.section("My filters")
        f.note("One rule per line, in uBlock Origin / Adblock Plus syntax. For example:  ||ads.example.com^   or   example.com##.banner")
        val box = EditText(this)
        box.setText(FilterLists.userFilters)
        box.hint = "||tracker.example.com^"
        box.setHintTextColor(Ui.textDim(this))
        box.setTextColor(Ui.text(this))
        box.typeface = Typeface.MONOSPACE
        box.textSize = 13f
        box.gravity = Gravity.TOP or Gravity.START
        box.minLines = 4
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        box.background = Ui.rounded(Ui.surfaceVariant(this), Ui.dpf(this, 8f))
        val pad = Ui.dp(this, 12)
        box.setPadding(pad, pad, pad, pad)
        val bp = Ui.lp(Ui.MATCH, Ui.WRAP)
        bp.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8))
        content.addView(box, bp)
        userBox = box
        val save = Ui.chip(this, "Save my filters", true)
        save.setOnClickListener {
            FilterLists.userFilters = box.text.toString()
            Dialogs.toast(this, "Saved")
            ui.postDelayed({ build() }, 2500)
        }
        val sp = Ui.lp(Ui.WRAP, Ui.dp(this, 40))
        sp.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 24))
        content.addView(save, sp)
    }

    private fun statusCard(): View {
        val on = FilterLists.adBlockEnabled
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = Ui.rounded(Ui.surfaceVariant(this), Ui.dpf(this, 16f))
        val pad = Ui.dp(this, 20)
        card.setPadding(pad, pad, pad, pad)
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_shield)
        icon.setColorFilter(if (on) Ui.accent(this) else Ui.textDim(this))
        card.addView(icon, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 48)))
        val title = Ui.textView(this, 18f, Ui.text(this), true)
        title.text = if (on) "Ad blocker is on" else "Ad blocker is off"
        card.addView(title, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(this, 8) })
        val blocked = Ui.textView(this, 15f, Ui.text(this))
        blocked.text = "%,d requests blocked so far".format(FilterLists.blockedCount.get())
        card.addView(blocked, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(this, 4) })
        val rules = Ui.textView(this, 13f, Ui.textDim(this))
        rules.text = FilterLists.ruleSummary()
        card.addView(rules, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(this, 2) })
        val lp = Ui.lp(Ui.MATCH, Ui.WRAP)
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4))
        card.layoutParams = lp
        return card
    }

    private fun update() {
        val label = updateLabel ?: return
        label.text = "Downloading…"
        label.visibility = View.VISIBLE
        FilterLists.updateAll { updated, failed ->
            if (isFinishing) return@updateAll
            val msg = when {
                updated == 0 && failed == 0 -> "Everything is already up to date"
                failed == 0 -> "Updated $updated list" + (if (updated == 1) "" else "s")
                else -> "Updated $updated, $failed could not be downloaded"
            }
            Dialogs.toast(this, msg)
            build()
        }
    }
}
