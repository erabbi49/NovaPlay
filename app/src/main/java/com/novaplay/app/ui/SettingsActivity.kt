package com.novaplay.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.novaplay.app.CrashLog
import com.novaplay.app.NovaApp
import com.novaplay.app.data.Library
import com.novaplay.app.data.Prefs
import com.novaplay.app.web.Site

/** Every setting of the app on one page. */
class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(this))
        val bar = TopBar(this)
        bar.title.text = "Settings"
        bar.showBack { finish() }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(content, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, Ui.lp(Ui.MATCH, 0).also { it.weight = 1f })
        setContentView(root)
        build(Form(this, content))
    }

    private fun build(f: Form) {
        f.section("Appearance")
        f.choice(
            "Theme",
            listOf(
                "Follow system" to Prefs.THEME_SYSTEM,
                "Light" to Prefs.THEME_LIGHT,
                "Dark" to Prefs.THEME_DARK,
                "AMOLED black" to Prefs.THEME_AMOLED
            ),
            { Prefs.theme },
            { Prefs.theme = it },
            onChanged = {
                NovaApp.applyTheme()
                recreate()
            }
        )

        f.section("Playback")
        f.toggle("Autoplay next", "Keep playing the queue, then related videos", { Prefs.autoplayNext }, { Prefs.autoplayNext = it })
        f.toggle("Resume where you stopped", "Continue a video from its last position", { Prefs.resumePlayback }, { Prefs.resumePlayback = it })
        f.toggle("Background play", "Keep the sound going when you leave the app or lock the screen", { Prefs.backgroundPlay }, { Prefs.backgroundPlay = it })
        f.toggle("Picture-in-picture", "Shrink the video into a small window when you leave the app", { Prefs.pipOnLeave }, { Prefs.pipOnLeave = it })
        f.toggle("Ambient mode", "A soft glow around the player that follows the colours of the video", { Prefs.ambient }, { Prefs.ambient = it })
        f.toggle("Gestures", "Swipe the sides for brightness and volume, double-tap to skip", { Prefs.gestures }, { Prefs.gestures = it })
        f.toggle("Fullscreen on rotate", "Turn the phone sideways to watch fullscreen", { Prefs.autoLandscapeFullscreen }, { Prefs.autoLandscapeFullscreen = it })
        f.choice(
            "Skip time",
            listOf("5 seconds" to 5, "10 seconds" to 10, "15 seconds" to 15, "30 seconds" to 30),
            { Prefs.doubleTapSeekSec },
            { Prefs.doubleTapSeekSec = it }
        )
        f.choice(
            "Default speed",
            listOf(
                "0.5x" to 0.5f, "0.75x" to 0.75f, "Normal" to 1f, "1.25x" to 1.25f,
                "1.5x" to 1.5f, "1.75x" to 1.75f, "2x" to 2f
            ),
            { Prefs.defaultSpeed },
            { Prefs.defaultSpeed = it }
        )
        f.toggle("Remember speed", "Use the last speed you picked for the next video", { Prefs.rememberSpeed }, { Prefs.rememberSpeed = it })
        f.choice(
            "Streaming quality",
            listOf("Auto" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360, "240p" to 240),
            { Prefs.streamQuality },
            { Prefs.streamQuality = it }
        )

        f.section("Downloads")
        f.choice(
            "Download quality",
            listOf(
                "Ask every time" to -1, "Best available" to 0, "1080p" to 1080, "720p" to 720,
                "480p" to 480, "360p" to 360, "240p" to 240
            ),
            { Prefs.downloadQuality },
            { Prefs.downloadQuality = it }
        )
        f.toggle("Wi-Fi only", "Wait for Wi-Fi instead of using mobile data", { Prefs.wifiOnlyDownloads }, { Prefs.wifiOnlyDownloads = it })

        f.section("Ad blocker")
        f.action("Ad blocker", "Filter lists, switches and your own rules") {
            startActivity(Intent(this, AdBlockActivity::class.java))
        }

        f.section("Privacy")
        f.toggle("Keep watch history", null, { Prefs.keepHistory }, { Prefs.keepHistory = it })
        f.action("Clear watch history", null) {
            Dialogs.confirm(this, "Clear watch history?", "This also forgets where you stopped in each video.", "Clear") {
                Library.clearHistory()
                Dialogs.toast(this, "Watch history cleared")
            }
        }
        f.action("Clear search history", null) {
            Library.clearSearches()
            Dialogs.toast(this, "Search history cleared")
        }

        f.section("Site")
        val address = f.action("Site address", Prefs.baseUrl) { editAddress() }
        addressView = address
        f.note(
            "If the site does not open, try another address of it here (a mirror), or turn off Private DNS / switch " +
                "your DNS in the phone's network settings. If it asks for a human check, open it once in the browser: " +
                "NovaPlay then reuses the result."
        )
        f.action("Open site in browser", "Solve a human check or sign in once") { BrowserActivity.open(this, Site.home()) }

        f.section("About")
        f.note("NovaPlay 1.0. Videos are loaded from the site you choose above; NovaPlay itself hosts nothing.")
        f.action("Last crash report", "If the app closed by itself, copy this report and send it on") { showCrash() }
    }

    private fun showCrash() {
        val report = CrashLog.read()
        if (report == null) {
            Dialogs.toast(this, "No crash has been saved")
            return
        }
        val text = TextView(this)
        text.text = report
        text.typeface = Typeface.MONOSPACE
        text.textSize = 11f
        text.setTextColor(Ui.text(this))
        text.setTextIsSelectable(true)
        val pad = Ui.dp(this, 16)
        text.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this)
        scroll.addView(text)
        AlertDialog.Builder(this)
            .setTitle("Last crash report")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("NovaPlay crash report", report))
                Dialogs.toast(this, "Copied")
            }
            .setNeutralButton("Clear") { _, _ -> CrashLog.clear() }
            .setNegativeButton("Close", null)
            .show()
    }

    private var addressView: TextView? = null

    private fun editAddress() {
        Dialogs.prompt(this, "Site address", Prefs.DEFAULT_BASE_URL, Prefs.baseUrl, "Save") { text ->
            val fixed = normalize(text)
            if (fixed == null) {
                Dialogs.toast(this, "That does not look like a web address")
            } else {
                Prefs.baseUrl = fixed
                addressView?.text = fixed
                Dialogs.toast(this, "Saved")
            }
        }
    }

    /** "xhamster.desi/some/page" -> "https://xhamster.desi"; null when it cannot be an address. */
    private fun normalize(input: String): String? {
        var t = input.trim()
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "https://$t"
        val host = Site.hostOf(t)
        if (host.isEmpty() || !host.contains('.') || host.contains(' ')) return null
        return t.substringBefore("://") + "://" + host
    }
}
